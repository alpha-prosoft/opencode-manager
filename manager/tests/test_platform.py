import json
import subprocess
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.config import Settings
from app.db import Database
from app.platform import harness
from app.platform.audit import AuditStore
from app.platform.registry import PlatformRegistry
from app.platform.secrets import SecretStore
from app.platform.spec import AgentSpec, HarnessSpec, PlatformSpec, WorkflowSpec
from app.review_post import ReviewSubmission, changed_lines, plan_post


def git_repo(path: Path, files: dict[str, str]) -> Path:
    path.mkdir(parents=True)
    for name, content in files.items():
        (path / name).parent.mkdir(parents=True, exist_ok=True)
        (path / name).write_text(content)
    for args in (["init", "-q", "-b", "main"], ["add", "-A"], ["-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init"]):
        subprocess.run(["git", *args], cwd=path, check=True)
    return path


HARNESS = {"llm": {"connection": "llm", "models": ["model-a"]}}
AGENT = {"prompt": "You are helpful."}


@pytest.fixture
def platform_env(tmp_path):
    roots = tmp_path / "repos"

    def build(platform: dict, team_files: dict[str, str]):
        git_repo(roots / "team", {f".platform/{name}": content for name, content in team_files.items()})
        git_repo(roots / "onboarding", {"platform.json": json.dumps(platform)})
        settings = Settings(
            onboarding_url=f"file://{roots}/onboarding",
            onboarding_branch="main",
            database_path=str(tmp_path / "db.sqlite"),
            repos_dir=str(tmp_path / "data"),
            secrets_dir=str(tmp_path / "secrets"),
            local_repo_roots=[str(roots)],
        )
        (tmp_path / "secrets").mkdir(exist_ok=True)
        (tmp_path / "secrets" / "GIT_TOKEN").write_text("secret-token")
        registry = PlatformRegistry(settings, Database(settings.database_path), SecretStore(settings))
        return registry, registry.sync()

    def platform(**team):
        return {
            "admins": ["root"],
            "llm_connections": [{"id": "llm", "base_url": "https://llm.example", "secret": "LLM_KEY", "models": ["model-a"]}],
            "secret_hosts": {"GIT_TOKEN": ["git.example.com"]},
            "teams": [{"id": "team", "name": "Team", "repository": {"url": f"file://{roots}/team"}, "members": ["dev"], **team}],
        }

    return build, platform, roots


def errors(registry, kind: str, def_id: str) -> list[str]:
    return next(d.errors for d in registry.definitions("team", kind) if d.id == def_id)


def test_valid_definitions_sync(platform_env):
    build, platform, _ = platform_env
    workflow = {"name": "Analyse", "activity": "analysis", "agent": "a", "harness": "h"}
    registry, report = build(platform(), {"agents/a.json": json.dumps(AGENT), "harnesses/h.json": json.dumps(HARNESS), "workflows/w.json": json.dumps(workflow)})
    assert report["teams"]["team"]["invalid"] == []
    assert registry.role("dev", "team") == "member" and registry.role("root", "team") == "admin"
    assert registry.role("stranger", "team") is None


def test_secret_cannot_be_sent_to_foreign_host(platform_env):
    build, platform, _ = platform_env
    workflow = {
        "name": "Exfiltrate",
        "activity": "implement",
        "agent": "a",
        "harness": "h",
        "options": {"repository": {"url": "https://attacker.example/x.git", "credentials": "GIT_TOKEN"}},
    }
    registry, _ = build(
        platform(secrets=["GIT_TOKEN"]),
        {"agents/a.json": json.dumps(AGENT), "harnesses/h.json": json.dumps(HARNESS), "workflows/w.json": json.dumps(workflow)},
    )
    assert any("may not be sent to attacker.example" in e for e in errors(registry, "workflows", "w"))


def test_secret_must_be_granted_to_team(platform_env):
    build, platform, _ = platform_env
    workflow = {
        "name": "Clone",
        "activity": "implement",
        "agent": "a",
        "harness": "h",
        "options": {"repository": {"url": "https://git.example.com/x.git", "credentials": "GIT_TOKEN"}},
    }
    registry, _ = build(platform(), {"agents/a.json": json.dumps(AGENT), "harnesses/h.json": json.dumps(HARNESS), "workflows/w.json": json.dumps(workflow)})
    assert any("not granted" in e for e in errors(registry, "workflows", "w"))


def test_file_urls_are_confined_to_local_roots(platform_env):
    build, platform, _ = platform_env
    workflow = {"name": "Read", "activity": "implement", "agent": "a", "harness": "h", "options": {"repository": {"url": "file:///etc"}}}
    registry, _ = build(platform(), {"agents/a.json": json.dumps(AGENT), "harnesses/h.json": json.dumps(HARNESS), "workflows/w.json": json.dumps(workflow)})
    assert any("must live under" in e for e in errors(registry, "workflows", "w"))


def test_referenced_files_cannot_escape_the_repository(platform_env, tmp_path):
    build, platform, roots = platform_env
    workflow = {"name": "Leak", "activity": "analysis", "agent": "a", "harness": "h", "options": {"template_file": "../../../../etc/passwd"}}
    registry, _ = build(platform(), {"agents/a.json": json.dumps(AGENT), "harnesses/h.json": json.dumps(HARNESS), "workflows/w.json": json.dumps(workflow)})
    assert any("not found" in e for e in errors(registry, "workflows", "w"))
    with pytest.raises(FileNotFoundError):
        registry.read_team_file("team", "../../../../etc/passwd")


def test_symlinks_out_of_the_repository_are_rejected(platform_env, tmp_path):
    build, platform, roots = platform_env
    registry, _ = build(platform(), {"agents/a.json": json.dumps(AGENT)})
    link = registry.team_dir("team") / "secrets-link"
    link.symlink_to(tmp_path / "secrets" / "GIT_TOKEN")
    with pytest.raises(FileNotFoundError):
        registry.read_team_file("team", "secrets-link")


def test_harness_rules(platform_env):
    build, platform, _ = platform_env
    bad = {"llm": {"connection": "llm", "models": ["model-b"]}, "image": "evil/image:latest"}
    registry, _ = build(platform(), {"harnesses/h.json": json.dumps(bad)})
    problems = " ".join(errors(registry, "harnesses", "h"))
    assert "allowed_images" in problems and "not offered" in problems


def test_specs_reject_unknown_fields_and_bad_values():
    with pytest.raises(ValidationError):
        HarnessSpec.model_validate({**HARNESS, "privileged": True})
    with pytest.raises(ValidationError):
        AgentSpec.model_validate({"prompt": "x", "prompt_file": "y"})
    with pytest.raises(ValidationError):
        WorkflowSpec.model_validate({"name": "x", "activity": "review", "agent": "a", "harness": "h", "trigger": {"type": "schedule", "every_minutes": 1}})
    with pytest.raises(ValidationError):
        PlatformSpec.model_validate({"teams": [{"id": "t", "name": "T", "repository": {"url": "ftp://x/y"}}]})


def test_harness_config_is_locked_down():
    settings = Settings()
    spec = HarnessSpec.model_validate({**HARNESS, "permissions": {"bash": "allow"}})
    config = harness.opencode_config(settings, "s1", "a", AgentSpec(prompt="p"), "p", spec, False, "llm-key", "mcp-key")
    assert config["enabled_providers"] == ["platform"] and config["plugin"] == []
    assert config["permission"]["question"] == "deny" and config["permission"]["webfetch"] == "deny"
    assert config["provider"]["platform"]["options"]["baseURL"].endswith("/llm/v1")
    sandbox = harness.sandbox(settings, spec, config, "pw", "team", "run", "telemetry-key")
    assert sandbox.env["OPENCODE_DISABLE_PROJECT_CONFIG"] == "1" and sandbox.env["XDG_CONFIG_HOME"] == harness.CONFIG_MOUNT
    assert "llm-key" not in json.dumps(sandbox.env)


def test_derived_keys_are_session_bound():
    key = harness.derive_key("signing", "llm", "session1")
    assert harness.verify_key("signing", "llm", key) == "session1"
    assert harness.verify_key("signing", "mcp", key) is None
    assert harness.verify_key("other", "llm", key) is None
    assert harness.verify_key("signing", "llm", "session2." + key.split(".")[1]) is None


def test_audit_chain_detects_tampering(tmp_path):
    db = Database(str(tmp_path / "audit.sqlite"))
    store = AuditStore(db)
    for i in range(3):
        assert store.append("s", "message", f"k{i}", {"n": i})
    assert not store.append("s", "message", "k0", {"n": 0})
    assert store.verify("s") == {"valid": True, "checked": 3, "head": store.events("s")[-1]["hash"]}
    with db.connect() as conn, pytest.raises(Exception):
        conn.execute("UPDATE audit_events SET payload = '{}'")
    with db.connect() as conn:
        conn.execute("DROP TRIGGER audit_events_no_update")
        conn.execute("UPDATE audit_events SET payload = '{\"n\": 42}' WHERE key = 'k1'")
    assert store.verify("s")["valid"] is False


DIFF = """diff --git a/a.py b/a.py
--- a/a.py
+++ b/a.py
@@ -1,2 +1,3 @@
 x = 1
-y = 2
+y = 3
+z = 4
"""


def test_review_plan_respects_limits_and_diff_lines():
    findings = [{"severity": s, "title": t, "body": "b", "path": "a.py", "line": line} for s, t, line in (
        ("nit", "n", 2), ("blocker", "b", 3), ("major", "outside", 99), ("minor", "m", 1))]
    review = ReviewSubmission(verdict="REQUEST_CHANGES", summary="s", findings=findings)
    plan = plan_post(review, DIFF, "dry-run", 3, "http://session")
    assert changed_lines(DIFF) == {"a.py": {1, 2, 3}}
    assert len(plan.comments) == 3 and plan.inline_count == 2 and plan.folded_count == 2
    assert [c.line for c in plan.comments[1:]] == [3, 1]


def test_team_source_types_default_to_git():
    from app.platform.spec import TeamEntry

    kinds = [
        TeamEntry.model_validate({"id": "t", "name": "T", "repository": repository}).repository.type
        for repository in (
            {"url": "https://git.example.com/x.git"},
            {"type": "git", "url": "git@git.example.com:org/x.git"},
            {"type": "url", "url": "https://artifacts.example.com/x.tar.gz"},
            {"type": "path", "location": "/repos/x"},
        )
    ]
    assert kinds == ["git", "git", "url", "path"]
    with pytest.raises(ValidationError):
        TeamEntry.model_validate({"id": "t", "name": "T", "repository": {"type": "url", "url": "http://insecure/x.tgz"}})


def test_path_source_must_stay_under_local_roots(platform_env):
    build, platform, roots = platform_env
    registry, report = build(
        {**platform(), "teams": [{"id": "team", "name": "T", "repository": {"type": "path", "location": "/etc"}}]},
        {"agents/a.json": json.dumps(AGENT)},
    )
    assert "must live under" in report["teams"]["team"]["error"]


def test_path_source_syncs_definitions(platform_env, tmp_path):
    build, platform, roots = platform_env
    registry, _ = build(platform(), {"agents/a.json": json.dumps(AGENT)})
    spec = {**platform(), "teams": [{"id": "team", "name": "T", "repository": {"type": "path", "location": str(roots / "team")}}]}
    git_repo(roots / "onboarding2", {"platform.json": json.dumps(spec)})
    registry.settings.onboarding_url = f"file://{roots}/onboarding2"
    report = registry.sync()
    assert report["teams"]["team"]["commit"].startswith("files:")
    assert [d.id for d in registry.definitions("team", "agents")] == ["a"]


def test_archive_extraction_blocks_path_traversal(tmp_path):
    import io
    import tarfile
    import zipfile

    from app.platform import sources

    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        data = b"owned"
        info = tarfile.TarInfo("../../escaped.txt")
        info.size = len(data)
        archive.addfile(info, io.BytesIO(data))
    with pytest.raises(sources.SourceError):
        sources._extract(buffer.getvalue(), tmp_path / "t1")
    assert not (tmp_path / "escaped.txt").exists()

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("../escaped.txt", "owned")
    with pytest.raises(sources.SourceError):
        sources._extract(buffer.getvalue(), tmp_path / "t2")


def test_ssh_urls_are_bound_to_their_host(platform_env):
    from app.platform import git

    assert git.host_of("git@bitbucket.org:acme/repo.git") == "bitbucket.org"
    assert git.host_of("ssh://git@github.com/acme/repo.git") == "github.com"
    build, platform, _ = platform_env
    workflow = {
        "name": "Clone",
        "activity": "implement",
        "agent": "a",
        "harness": "h",
        "options": {"repository": {"url": "git@attacker.example:x/y.git", "credentials": "GIT_TOKEN"}},
    }
    registry, _ = build(
        platform(secrets=["GIT_TOKEN"]),
        {"agents/a.json": json.dumps(AGENT), "harnesses/h.json": json.dumps(HARNESS), "workflows/w.json": json.dumps(workflow)},
    )
    assert any("attacker.example" in e for e in errors(registry, "workflows", "w"))


def test_harness_policy_rejects_invalid_regex():
    with pytest.raises(ValidationError):
        HarnessSpec.model_validate({**HARNESS, "policy": {"deny_commands": ["("]}})


def test_monitor_plugin_is_generated_with_policy_and_scoped_key():
    settings = Settings()
    spec = HarnessSpec.model_validate({**HARNESS, "policy": {"deny_commands": ["\\bgit\\s+push\\b"]}})
    key = harness.derive_key("signing", "telemetry", "s1")
    config = harness.opencode_config(settings, "s1", "a", AgentSpec(prompt="p"), "p", spec, True, "llm", None)
    sandbox = harness.sandbox(settings, spec, config, "pw", "team", "run", key)
    plugin = sandbox.files["opencode/plugins/platform-monitor.js"]
    assert "__" not in plugin.split("WATCHED")[0]
    assert json.dumps(key) in plugin and "git\\\\s+push" in plugin
    assert harness.verify_key("signing", "telemetry", key) == "s1"
    assert harness.verify_key("signing", "llm", key) is None
    assert "OPENCODE_PURE" not in sandbox.env and sandbox.env["OPENCODE_DISABLE_DEFAULT_PLUGINS"] == "1"
