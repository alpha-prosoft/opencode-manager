import json
import logging
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from pydantic import ValidationError

from ..config import Settings
from ..db import Database
from ..store import now
from . import git, sources
from .catalog import CATALOG
from .secrets import SecretStore
from .spec import (
    DEFINITION_KINDS,
    AgentSpec,
    ArchiveSource,
    GitSource,
    HarnessSpec,
    PathSource,
    PlatformSpec,
    PullRequestTrigger,
    TeamEntry,
    WorkflowSpec,
)

log = logging.getLogger(__name__)

FILE_OPTIONS = ("guidelines_file", "instructions_file", "template_file")


@dataclass
class Definition:
    team_id: str
    kind: str
    id: str
    source: str
    spec: dict | None
    errors: list[str]

    @property
    def valid(self) -> bool:
        return not self.errors and self.spec is not None


def _errors(error: ValidationError) -> list[str]:
    return [f"{'.'.join(str(p) for p in e['loc']) or 'file'}: {e['msg']}" for e in error.errors()]


class PlatformRegistry:
    def __init__(self, settings: Settings, db: Database, secrets: SecretStore):
        self.settings = settings
        self.db = db
        self.secrets = secrets
        self.repos_dir = Path(settings.repos_dir)

    # ---- reads -------------------------------------------------------------------------

    def _state(self, key: str, default=None):
        with self.db.connect() as db:
            row = db.execute("SELECT value FROM platform_state WHERE key = ?", (key,)).fetchone()
        return json.loads(row["value"]) if row else default

    def _set_state(self, db, key: str, value) -> None:
        db.execute(
            "INSERT INTO platform_state (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            (key, json.dumps(value)),
        )

    def platform(self) -> PlatformSpec:
        spec = self._state("platform")
        return PlatformSpec.model_validate(spec) if spec else PlatformSpec()

    def status(self) -> dict:
        return self._state("status", {"commit": None, "synced_at": None, "error": None, "configured": self.configured})

    @property
    def configured(self) -> bool:
        return bool(self.settings.onboarding_url)

    def teams(self) -> list[dict]:
        with self.db.connect() as db:
            rows = db.execute("SELECT * FROM teams ORDER BY id").fetchall()
        return [{**dict(row), "spec": json.loads(row["spec"])} for row in rows]

    def team(self, team_id: str) -> TeamEntry | None:
        with self.db.connect() as db:
            row = db.execute("SELECT spec FROM teams WHERE id = ?", (team_id,)).fetchone()
        return TeamEntry.model_validate_json(row["spec"]) if row else None

    def role(self, username: str, team_id: str) -> str | None:
        if username in self.platform().admins:
            return "admin"
        with self.db.connect() as db:
            row = db.execute(
                "SELECT role FROM team_members WHERE team_id = ? AND username = ?", (team_id, username)
            ).fetchone()
        return row["role"] if row else None

    def secret_host_allowed(self, secret: str, url: str, platform: PlatformSpec | None = None) -> bool:
        if urlsplit(url).scheme == "file":
            return True
        return git.host_of(url) in (platform or self.platform()).secret_hosts.get(secret, [])

    def _token(self, secret: str | None, url: str, platform: PlatformSpec | None) -> str | None:
        if not secret:
            return None
        if not self.secret_host_allowed(secret, url, platform):
            raise sources.SourceError(f"secret {secret} may not be sent to {git.host_of(url)} (see secret_hosts)")
        return self.secrets.require(secret)

    def _source_root(self, key: str, source) -> Path:
        if isinstance(source, PathSource):
            return Path(source.location).resolve()
        return self.repos_dir / key

    def _fetch(self, key: str, source, platform: PlatformSpec | None) -> str:
        roots = self.settings.local_repo_roots
        if isinstance(source, PathSource):
            return sources.fingerprint(sources.local_directory(source.location, roots))
        if isinstance(source, ArchiveSource):
            return sources.fetch_archive(source.url, self.repos_dir / key, self._token(source.credentials, source.url, platform))
        token = self._token(source.credentials, source.url, platform)
        return git.checkout(source.url, source.branch, self.repos_dir / key, source.username, token, local_roots=roots)

    def onboarding_source(self):
        location, settings = self.settings.onboarding_url, self.settings
        kind = settings.onboarding_type or (
            "path" if location.startswith("/") else "url" if location.endswith((".tar.gz", ".tgz", ".zip")) else "git"
        )
        if kind == "path":
            return PathSource(type="path", location=location, path=".")
        if kind == "url":
            return ArchiveSource(type="url", url=location, credentials=settings.onboarding_credentials, path=".")
        return GitSource(
            url=location, branch=settings.onboarding_branch, credentials=settings.onboarding_credentials,
            username=settings.onboarding_username, path=".",
        )

    def is_platform_admin(self, username: str) -> bool:
        return username in self.platform().admins

    def teams_of(self, username: str) -> set[str]:
        if self.is_platform_admin(username):
            return {t["id"] for t in self.teams()}
        with self.db.connect() as db:
            rows = db.execute("SELECT team_id FROM team_members WHERE username = ?", (username,)).fetchall()
        return {row["team_id"] for row in rows}

    def definitions(self, team_id: str, kind: str | None = None) -> list[Definition]:
        query, params = "SELECT * FROM definitions WHERE team_id = ?", [team_id]
        if kind:
            query, params = query + " AND kind = ?", [team_id, kind]
        with self.db.connect() as db:
            rows = db.execute(query + " ORDER BY kind, id", params).fetchall()
        return [
            Definition(r["team_id"], r["kind"], r["id"], r["source"], json.loads(r["spec"]) if r["spec"] else None, json.loads(r["errors"]))
            for r in rows
        ]

    def definition(self, team_id: str, kind: str, def_id: str):
        for item in self.definitions(team_id, kind):
            if item.id == def_id and item.valid:
                return DEFINITION_KINDS[kind].model_validate(item.spec)
        return None

    def workflows(self, team_id: str) -> list[Definition]:
        return self.definitions(team_id, "workflows")

    def team_dir(self, team_id: str) -> Path:
        team = self.team(team_id)
        if team is None:
            return self.repos_dir / "teams" / team_id / ".platform"
        return self._source_root(f"teams/{team_id}", team.repository) / team.repository.path

    def read_team_file(self, team_id: str, relative: str) -> str:
        base = self.team_dir(team_id).resolve()
        path = (base / relative).resolve()
        if not path.is_relative_to(base) or not path.is_file():
            raise FileNotFoundError(f"{relative} not found in the team definitions")
        return path.read_text()

    # ---- sync --------------------------------------------------------------------------

    def sync(self) -> dict:
        if not self.configured:
            return {"error": "No onboarding repository configured (MANAGER_ONBOARDING_URL)"}
        try:
            source = self.onboarding_source()
            token = self.secrets.get(self.settings.onboarding_credentials) if self.settings.onboarding_credentials else None
            roots = self.settings.local_repo_roots
            if isinstance(source, PathSource):
                commit = sources.fingerprint(sources.local_directory(source.location, roots))
            elif isinstance(source, ArchiveSource):
                commit = sources.fetch_archive(source.url, self.repos_dir / "onboarding", token)
            else:
                commit = git.checkout(source.url, source.branch, self.repos_dir / "onboarding", source.username, token, local_roots=roots)
            target = self._source_root("onboarding", source)
            raw = json.loads((target / self.settings.onboarding_file).read_text())
            raw.pop("$schema", None)
            platform = PlatformSpec.model_validate(raw)
        except (git.GitError, sources.SourceError, OSError, ValueError, ValidationError) as error:
            message = "; ".join(_errors(error)) if isinstance(error, ValidationError) else str(error)
            log.warning("Onboarding sync failed: %s", message)
            with self.db.connect() as db:
                self._set_state(db, "status", {**self.status(), "error": message, "synced_at": now(), "configured": True})
            return {"error": message}

        with self.db.connect() as db:
            self._set_state(db, "platform", platform.model_dump(mode="json"))
            self._set_state(db, "status", {"commit": commit, "synced_at": now(), "error": None, "configured": True})
            known = {row["id"] for row in db.execute("SELECT id FROM teams")}
            for stale in known - {t.id for t in platform.teams}:
                db.execute("DELETE FROM teams WHERE id = ?", (stale,))
                db.execute("DELETE FROM team_members WHERE team_id = ?", (stale,))
                db.execute("DELETE FROM definitions WHERE team_id = ?", (stale,))

        report = {"commit": commit, "teams": {}}
        for team in platform.teams:
            report["teams"][team.id] = self._sync_team(platform, team)
        return report

    def _sync_team(self, platform: PlatformSpec, team: TeamEntry) -> dict:
        commit, error, definitions = None, None, []
        try:
            commit = self._fetch(f"teams/{team.id}", team.repository, platform)
            definitions = self._load_definitions(platform, team)
        except (git.GitError, sources.SourceError, LookupError, OSError) as failure:
            error = str(failure)
            log.warning("Sync of team %s failed: %s", team.id, error)

        with self.db.connect() as db:
            db.execute(
                "INSERT INTO teams (id, spec, commit_id, synced_at, status, error) VALUES (?, ?, ?, ?, ?, ?) "
                "ON CONFLICT (id) DO UPDATE SET spec = excluded.spec, commit_id = COALESCE(excluded.commit_id, teams.commit_id), "
                "synced_at = excluded.synced_at, status = excluded.status, error = excluded.error",
                (team.id, team.model_dump_json(), commit, now(), "error" if error else "ok", error),
            )
            db.execute("DELETE FROM team_members WHERE team_id = ?", (team.id,))
            for username in team.members:
                db.execute("INSERT INTO team_members VALUES (?, ?, 'member')", (team.id, username))
            for username in team.admins:
                db.execute(
                    "INSERT INTO team_members VALUES (?, ?, 'admin') ON CONFLICT (team_id, username) DO UPDATE SET role = 'admin'",
                    (team.id, username),
                )
            if not error:
                db.execute("DELETE FROM definitions WHERE team_id = ?", (team.id,))
                for d in definitions:
                    db.execute(
                        "INSERT INTO definitions VALUES (?, ?, ?, ?, ?, ?)",
                        (d.team_id, d.kind, d.id, d.source, json.dumps(d.spec) if d.spec else None, json.dumps(d.errors)),
                    )
        invalid = [f"{d.kind}/{d.id}" for d in definitions if not d.valid]
        return {"commit": commit, "error": error, "definitions": len(definitions), "invalid": invalid}

    def _load_definitions(self, platform: PlatformSpec, team: TeamEntry) -> list[Definition]:
        base = self._source_root(f"teams/{team.id}", team.repository) / team.repository.path
        loaded: dict[str, dict[str, Definition]] = {kind: {} for kind in DEFINITION_KINDS}
        for kind, model in DEFINITION_KINDS.items():
            for path in sorted((base / kind).glob("*.json")) if (base / kind).is_dir() else []:
                def_id, source = path.stem, f"{team.repository.path}/{kind}/{path.name}"
                try:
                    raw = json.loads(path.read_text())
                    raw.pop("$schema", None)
                    spec = model.model_validate(raw)
                    loaded[kind][def_id] = Definition(team.id, kind, def_id, source, spec.model_dump(mode="json"), [])
                except json.JSONDecodeError as error:
                    loaded[kind][def_id] = Definition(team.id, kind, def_id, source, None, [f"invalid JSON: {error}"])
                except ValidationError as error:
                    loaded[kind][def_id] = Definition(team.id, kind, def_id, source, None, _errors(error))

        for agent in loaded["agents"].values():
            if agent.valid:
                spec = AgentSpec.model_validate(agent.spec)
                if spec.prompt_file and not _inside(base, spec.prompt_file):
                    agent.errors.append(f"prompt_file {spec.prompt_file} not found")
        for harness in loaded["harnesses"].values():
            if harness.valid:
                harness.errors += self._check_harness(platform, team, HarnessSpec.model_validate(harness.spec))
        for workflow in loaded["workflows"].values():
            if workflow.valid:
                workflow.errors += self._check_workflow(platform, team, base, loaded, WorkflowSpec.model_validate(workflow.spec))
        return [d for group in loaded.values() for d in group.values()]

    def _check_harness(self, platform: PlatformSpec, team: TeamEntry, harness: HarnessSpec) -> list[str]:
        errors = []
        if harness.image and harness.image not in platform.allowed_images:
            errors.append(f"image {harness.image} is not in the platform's allowed_images")
        connection = next((c for c in platform.llm_connections if c.id == harness.llm.connection), None)
        if connection is None:
            errors.append(f"llm.connection {harness.llm.connection} does not exist")
        else:
            if connection.teams != "*" and team.id not in connection.teams:
                errors.append(f"team {team.id} may not use llm connection {connection.id}")
            unknown = [m for m in harness.llm.models if m not in connection.models]
            if unknown:
                errors.append(f"models {unknown} are not offered by connection {connection.id}")
        return errors

    def _check_workflow(self, platform: PlatformSpec, team: TeamEntry, base: Path, loaded: dict, workflow: WorkflowSpec) -> list[str]:
        errors = []
        activity = CATALOG.get(workflow.activity)
        if activity is None:
            return [f"unknown marketplace activity {workflow.activity}"]
        for kind, ref in (("agents", workflow.agent), ("harnesses", workflow.harness)):
            if ref not in loaded[kind]:
                errors.append(f"{kind[:-1]} {ref} does not exist")
            elif not loaded[kind][ref].valid:
                errors.append(f"{kind[:-1]} {ref} is invalid")
        try:
            options = activity.options.model_validate(workflow.options)
        except ValidationError as error:
            return errors + [f"options.{e}" for e in _errors(error)]
        for name in FILE_OPTIONS:
            relative = getattr(options, name, None)
            if relative and not _inside(base, relative):
                errors.append(f"options.{name}: {relative} not found in the team definitions")
        repository = getattr(options, "repository", None)
        if repository:
            try:
                git.check_url(repository.url, self.settings.local_repo_roots)
            except git.GitError as error:
                errors.append(f"options.repository.url: {error}")
            if repository.credentials and not self.secret_host_allowed(repository.credentials, repository.url, platform):
                errors.append(
                    f"options.repository: secret {repository.credentials} may not be sent to "
                    f"{git.host_of(repository.url)} (see secret_hosts in the onboarding repository)"
                )
        used_secrets = {s for s in (_credentials(options), _trigger_credentials(workflow)) if s}
        for secret in used_secrets - set(team.secrets):
            errors.append(f"secret {secret} is not granted to team {team.id} in the onboarding repository")
        if isinstance(workflow.trigger, PullRequestTrigger) and workflow.activity != "review":
            errors.append("pull_request triggers can only start the review activity")
        return errors


def _inside(base: Path, relative: str) -> bool:
    path = (base / relative).resolve()
    return path.is_relative_to(base.resolve()) and path.is_file()


def _credentials(options) -> str | None:
    repository = getattr(options, "repository", None)
    scm = getattr(options, "scm", None)
    return (repository.credentials if repository else None) or (scm.credentials if scm else None)


def _trigger_credentials(workflow: WorkflowSpec) -> str | None:
    return workflow.trigger.credentials if isinstance(workflow.trigger, PullRequestTrigger) else None
