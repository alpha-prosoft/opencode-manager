#!/usr/bin/env python3
"""End-to-end test of a running platform (docker compose up + scripts/seed-local-repos.sh).

Usage: python3 tests/e2e.py [--base http://localhost:8080]
Optional: E2E_REVIEW=<team>/<workflow>/<pull request> also runs a real code review.
"""

import argparse
import http.cookiejar
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

RESULTS: list[tuple[str, bool, str]] = []
RUN_TIMEOUT = 900


class Client:
    def __init__(self, base: str, username: str):
        self.base = base
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), NoRedirect())
        status, _ = self.call("POST", "/api/auth/login", {"username": username})
        assert status == 200, f"login {username} failed: {status}"

    def call(self, method: str, path: str, body=None, raw: bool = False):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method, headers={"Content-Type": "application/json"})
        try:
            with self.opener.open(request, timeout=60) as response:
                payload = response.read()
                return response.status, payload if raw else (json.loads(payload) if payload else None)
        except urllib.error.HTTPError as error:
            payload = error.read()
            try:
                return error.code, payload if raw else json.loads(payload)
            except ValueError:
                return error.code, payload
        except urllib.error.URLError as error:
            return 0, str(error)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def check(name: str, condition: bool, detail: str = "") -> bool:
    RESULTS.append((name, bool(condition), detail))
    print(f"  {'PASS' if condition else 'FAIL'}  {name}{f'  ({detail})' if detail and not condition else ''}", flush=True)
    return bool(condition)


def sh(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(list(args), capture_output=True, text=True, timeout=120)


def manager_python(code: str) -> str:
    result = sh("docker", "compose", "exec", "-T", "manager", "python", "-c", code)
    return (result.stdout + result.stderr).strip()


def wait_for_run(client: Client, run_id: str) -> dict:
    deadline = time.time() + RUN_TIMEOUT
    while time.time() < deadline:
        status, run = client.call("GET", f"/api/runs/{run_id}")
        if status == 200 and run["status"] in ("completed", "failed"):
            return run
        time.sleep(5)
    return {"status": "timeout", "id": run_id}


def start(client: Client, team: str, workflow: str, inputs: dict) -> str | None:
    status, run = client.call("POST", f"/api/teams/{team}/workflows/{workflow}/runs", inputs)
    check(f"start {team}/{workflow}", status == 201, str(run))
    return run.get("id") if status == 201 else None


def audit_ok(client: Client, session_id: str) -> None:
    _, verification = client.call("GET", f"/api/sessions/{session_id}/audit/verify")
    _, events = client.call("GET", f"/api/sessions/{session_id}/audit?limit=1000")
    tools = [e["payload"].get("tool") for e in events if e["type"] == "tool"]
    check("audit hash chain is valid", verification.get("valid") is True, str(verification))
    check("audit recorded tool calls", len(tools) > 0, str(tools))


TOKEN_FILE = Path(__file__).resolve().parent.parent / "local" / "secrets" / "BITBUCKET_TOKEN"

GATEWAY_PROBE = """
import json, httpx
from app.main import build_platform
from app.platform.harness import derive_key
p = build_platform()
key = derive_key(p.signing_key, "tools", "{session}")
def call(name, key=key):
    body = {{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {{"name": name, "arguments": {{}}}}}}
    r = httpx.post("http://localhost:8000/mcp-gw/bitbucket", json=body, headers={{"X-Platform-Key": key, "Accept": "application/json, text/event-stream"}})
    return r.status_code, r.text[:300]
print(json.dumps({{"allowed": call("whoami"), "blocked": call("delete_repository"), "forged": call("whoami", "{session}.forged")}}))
"""


def credentials_and_tools(base: str, alice: Client, bob: Client, carol: Client) -> None:
    print("Credentials, MCP gateway, fork and lock")
    erin = Client(base, "erin")
    _, mine = erin.call("GET", "/api/me/credentials")
    states = {c["id"]: c for c in (mine or {}).get("credentials", [])}
    check("people see the credentials their teams need", "JIRA_TOKEN" in states and states["JIRA_TOKEN"]["required"], str(states.keys()))
    check("new credentials are offered at login", "JIRA_TOKEN" in (mine or {}).get("pending", []))
    status, body = bob.call("PUT", "/api/me/credentials/BITBUCKET_TOKEN", {"secret": "definitely-not-a-valid-token"})
    check("invalid credentials are rejected before saving", status == 422 and "Not saved" in str(body), str(body))
    status, _ = carol.call("PUT", "/api/admin/credentials/BITBUCKET_TOKEN", {"secret": "x"})
    check("only admins set platform values", status == 403)
    if not TOKEN_FILE.is_file():
        check("Bitbucket token available for the tool checks (local/secrets/BITBUCKET_TOKEN)", False)
        return
    status, body = bob.call("PUT", "/api/me/credentials/BITBUCKET_TOKEN", {"secret": TOKEN_FILE.read_text().strip()})
    check("a valid personal credential is checked and saved", status == 200, str(body))
    _, admin_view = alice.call("GET", "/api/admin/credentials")
    leaked = TOKEN_FILE.read_text().strip() in json.dumps(admin_view)
    check("credential values are never returned by the API", not leaked)

    run = wait_for_run(bob, start(bob, "demo", "bitbucket-workspace", {"title": "e2e tools"}))
    session_id = run.get("session_id")
    if not check("a workspace with MCP tools launches", run["status"] == "completed", run.get("error") or run["status"]):
        return
    probe = json.loads(manager_python(GATEWAY_PROBE.format(session=session_id)).splitlines()[-1])
    check("the gateway calls allowed tools with the person's credential", probe["allowed"][0] == 200 and "display_name" in probe["allowed"][1], str(probe["allowed"]))
    check("the gateway blocks tools outside the allow-list", "not allowed" in probe["blocked"][1], str(probe["blocked"]))
    check("the gateway rejects forged keys", probe["forged"][0] == 401, str(probe["forged"]))
    _, events = bob.call("GET", f"/api/sessions/{session_id}/audit?limit=1000")
    sources = [e["payload"].get("source") for e in events if e["type"] == "mcp"]
    check("MCP calls are audited with whose credential was used", "user" in sources, str(sources))
    output = sh("docker", "exec", f"opencode-{session_id}", "sh", "-c", "wget -q -T 5 -O- http://mcp-bitbucket:8000/ >/dev/null 2>&1 && echo reachable || echo blocked").stdout
    check("sandboxes cannot reach MCP servers directly", "blocked" in output, output)
    output = sh("docker", "exec", f"opencode-{session_id}", "sh", "-c", "echo x >> /etc/opencode/opencode/skills/pr-triage/SKILL.md 2>/dev/null && echo writable || echo denied").stdout
    check("skills are mounted read-only", "denied" in output, output)

    _, seen = alice.call("GET", f"/api/sessions/{session_id}")
    check("others cannot work in a session that uses personal credentials", seen.get("open_blocked") == "personal", str(seen.get("open_blocked")))
    check("they cannot start it either", alice.call("POST", f"/api/sessions/{session_id}/start")[0] == 409)
    status, fork = alice.call("POST", f"/api/sessions/{session_id}/fork")
    check("they can fork it into their own private copy", status == 201 and fork.get("sub") == "alice" and fork.get("visibility") == "private", str(fork))
    check("non-members cannot fork it", carol.call("POST", f"/api/sessions/{session_id}/fork")[0] == 404)
    check("only the owner can lock it", alice.call("POST", f"/api/sessions/{session_id}/lock")[0] == 403)
    status, locked = bob.call("POST", f"/api/sessions/{session_id}/lock")
    check("the owner can lock it", status == 200 and locked.get("locked_by") == "bob", str(locked))
    check("nobody can start a locked session", bob.call("POST", f"/api/sessions/{session_id}/start")[0] == 409)
    check("a locked session can still be forked", bob.call("POST", f"/api/sessions/{session_id}/fork")[0] == 201)
    bob.call("DELETE", "/api/me/credentials/BITBUCKET_TOKEN")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8080")
    base = parser.parse_args().base

    print("Platform and access control")
    alice, bob, carol = Client(base, "alice"), Client(base, "bob"), Client(base, "carol")
    status, report = alice.call("POST", "/api/platform/sync")
    check("admin can sync the onboarding repository", status == 200 and not report.get("error"), str(report))
    check("non-admin cannot sync", bob.call("POST", "/api/platform/sync")[0] == 403)
    _, team = alice.call("GET", "/api/teams/demo")
    invalid = {d["id"]: d["errors"] for d in team["definitions"] if not d["valid"]}
    check("invalid workflow is rejected with a reason", "github-review" in invalid and "not granted" in str(invalid))
    check("non-member cannot see a team", carol.call("GET", "/api/teams/demo")[0] == 404)
    check("non-member cannot start a run", carol.call("POST", "/api/teams/demo/workflows/requirements/runs", {"brief": "x" * 20})[0] == 404)
    status, error = alice.call("POST", "/api/teams/demo/workflows/implement-change/runs", {"task": "short"})
    check("run inputs are validated", status == 422, str(error))
    _, catalog = alice.call("GET", "/api/marketplace")
    check("marketplace lists the activities", {a["id"] for a in catalog} >= {"session", "review", "implement", "analysis"})

    print("Roles and administration")
    dave, erin = Client(base, "dave"), Client(base, "erin")
    personas = {p["username"]: p["roles"] for p in alice.call("GET", "/api/auth/personas")[1]}
    check("login offers personas with their roles", "Platform admin" in personas.get("alice", []) and any("Payments" in r for r in personas.get("dave", [])), str(personas))
    teams = {t["id"]: t for t in alice.call("GET", "/api/teams")[1]}
    check("teams can be synced from a local path", teams.get("payments", {}).get("status") == "ok" and str(teams["payments"]["commit"]).startswith("files:"), str(teams.get("payments")))
    check("team admins see only their team", [t["id"] for t in dave.call("GET", "/api/me")[1]["teams"]] == ["payments"])
    check("team members cannot use the admin API", erin.call("GET", "/api/admin/overview")[0] == 403)
    status, overview = alice.call("GET", "/api/admin/overview")
    check("platform admins get an overview", status == 200 and overview["teams"] >= 3, str(overview))
    users = {u["username"]: u for u in alice.call("GET", "/api/admin/users")[1]}
    check("admin user matrix shows team roles", users.get("dave", {}).get("teams", {}).get("payments") == "admin", str(users.get("dave")))
    credentials = alice.call("GET", "/api/admin/credentials")[1]
    check("credential status never includes values", credentials and all("secret" not in c and "value" not in c for c in credentials), str(credentials)[:200])
    connection = alice.call("GET", "/api/platform")[1]["llm_connections"][0]["id"]
    status, tested = alice.call("POST", f"/api/admin/llm/{connection}/test")
    check("admins can test an LLM connection", status == 200 and tested.get("ok"), str(tested))
    check("admins can list Temporal schedules", any(s["id"] == "platform-sync" for s in alice.call("GET", "/api/admin/schedules")[1]))

    print("Activities")
    analysis = start(alice, "demo", "requirements", {"brief": "Members get 5% off orders over 100 EUR, stackable with other discounts up to 20% in total."})
    implement = start(
        alice,
        "demo",
        "implement-change",
        {"task": "Add a keyword argument member: bool = False to total(). Members get an extra 5% off when the subtotal is over 100; the combined discount must not exceed 20%. Add unit tests and run them."},
    )
    workspace = start(bob, "demo", "workspace", {"title": "e2e workspace", "prompt": "Run the unit tests in repo/ and tell me the result."})
    review = None
    if os.environ.get("E2E_REVIEW"):
        team_id, workflow_id, number = os.environ["E2E_REVIEW"].split("/")
        review = (team_id, start(alice, team_id, workflow_id, {"pull_request": int(number)}))

    run = wait_for_run(bob, workspace)

    print("Monitoring and policy")
    policy = start(bob, "demo", "workspace", {"title": "e2e policy", "prompt": "Use the bash tool to run exactly: git push origin main --dry-run   Then use the bash tool to run: ls repo. Report what happened."})
    watched = wait_for_run(bob, policy)
    activity = {}
    for _ in range(60):
        activity = bob.call("GET", f"/api/sessions/{watched['session_id']}/activity")[1]
        if activity.get("state") == "idle" and activity.get("tools"):
            break
        time.sleep(3)
    check("monitor plugin reports tool calls", any(t["tool"] == "bash" for t in activity.get("tools", [])), str(activity.get("tools")))
    check("harness policy blocks denied commands", activity.get("blocked", 0) >= 1, str(activity.get("blocked")))
    check("carol cannot read the session activity", carol.call("GET", f"/api/sessions/{watched['session_id']}/activity")[0] == 404)
    output = sh("docker", "exec", f"opencode-{watched['session_id']}", "sh", "-c", "echo x >> /etc/opencode/opencode/plugins/platform-monitor.js 2>&1 || echo denied").stdout
    check("monitor plugin is read-only", "denied" in output or "Permission" in output, output)
    bob.call("POST", f"/api/sessions/{watched['session_id']}/stop")

    cancel = start(alice, "demo", "requirements", {"brief": "This run is cancelled straight away by the e2e test."})
    check("other users cannot cancel a run", erin.call("POST", f"/api/runs/{cancel}/cancel")[0] == 404)
    status, cancelled = alice.call("POST", f"/api/runs/{cancel}/cancel")
    time.sleep(10)
    cancelled_run = alice.call("GET", f"/api/runs/{cancel}")[1]
    check("runs can be cancelled", status == 200 and cancelled_run["status"] == "cancelled", cancelled_run.get("error") or cancelled_run["status"])
    status, retried = alice.call("POST", f"/api/runs/{cancel}/retry")
    check("runs can be retried", status == 201 and retried["trigger"].get("retry_of") == cancel, str(retried))
    if status == 201:
        alice.call("POST", f"/api/runs/{retried['id']}/cancel")
    check("interactive session launches", run["status"] == "completed", run.get("error") or run["status"])
    session = run.get("session") or {}
    check("interactive session is private to its owner", session.get("visibility") == "private" and session.get("sub") == "bob")
    status, seen = alice.call("GET", f"/api/sessions/{session.get('id')}")
    check("team admins see a private session but cannot work in it", status == 200 and seen.get("open_blocked") == "private", str(seen))
    check("non-members cannot see the session", carol.call("GET", f"/api/sessions/{session.get('id')}")[0] == 404)
    status, _ = bob.call("GET", session.get("open_url", "/"))
    check("session entry redirects through the handoff", status == 303)

    print("Sandbox security (probes from inside the session container)")
    container = f"opencode-{session.get('id')}"
    probe = sh("docker", "exec", container, "sh", "-c", "wget -q -T 5 -O /dev/null https://example.com && echo REACHABLE || echo blocked")
    check("session has no internet access", "blocked" in probe.stdout, probe.stdout + probe.stderr)
    probe = sh("docker", "exec", container, "sh", "-c", "echo x >> /etc/opencode/opencode/opencode.json && echo WRITABLE || echo denied")
    check("OpenCode config is read-only", "denied" in probe.stdout, probe.stdout)
    probe = sh("docker", "exec", container, "sh", "-c", "touch /usr/x 2>/dev/null && echo WRITABLE || echo denied; id -u")
    check("root filesystem is read-only and the agent is not root", "denied" in probe.stdout and probe.stdout.strip().endswith("1000"), probe.stdout)
    probe = sh(
        "docker", "exec", container, "sh", "-c",
        "wget -q -O- --header 'Authorization: Bearer forged.key' --header 'Content-Type: application/json' "
        "--post-data '{\"model\":\"claude-sonnet-5.5\",\"messages\":[]}' http://manager:8000/llm/v1/chat/completions 2>&1; true",
    )
    check("gateway rejects a forged key", "401" in probe.stdout, probe.stdout)
    probe = sh(
        "docker", "exec", container, "sh", "-c",
        "KEY=$(sed -n 's/.*\"apiKey\": \"\\([^\"]*\\)\".*/\\1/p' /etc/opencode/opencode/opencode.json); "
        "wget -q -O- --header \"Authorization: Bearer $KEY\" --header 'Content-Type: application/json' "
        "--post-data '{\"model\":\"gpt-not-allowed\",\"messages\":[]}' http://manager:8000/llm/v1/chat/completions 2>&1; true",
    )
    check("gateway rejects models outside the harness", "403" in probe.stdout, probe.stdout)
    output = manager_python(
        "import httpx;h={'Content-Type':'application/json','Accept':'application/json, text/event-stream',"
        f"'X-Platform-Session':'{session.get('id')}','X-Platform-Key':'forged'}};"
        "r=httpx.post('http://localhost:8000/mcp/',headers=h,json={'jsonrpc':'2.0','id':1,'method':'tools/call',"
        "'params':{'name':'submit_document','arguments':{'title':'t','markdown':'m'}}}).json();print(r['result']['content'][0]['text'])"
    )
    check("result tools reject forged session keys", "not allowed" in output, output)
    output = manager_python(
        "import sqlite3;db=sqlite3.connect('/data/manager.db')\n"
        "try:\n db.execute(\"UPDATE audit_events SET payload='{}'\");print('MODIFIED')\n"
        "except Exception as e:\n print('blocked', e)"
    )
    check("audit events are append-only", output.startswith("blocked"), output)

    print("Results")
    for name, run_id, verify in (("analysis", analysis, "document"), ("implement", implement, "implementation")):
        run = wait_for_run(alice, run_id)
        ok = check(f"{name} run completes", run["status"] == "completed", run.get("error") or run["status"])
        if not ok:
            continue
        check(f"{name} run used the metered gateway", run["usage"]["calls"] > 0 and run["usage"]["prompt_tokens"] > 0, str(run["usage"]))
        check(f"{name} session stopped afterwards", (run.get("session") or {}).get("status") == "stopped", str((run.get("session") or {}).get("status")))
        audit_ok(alice, run["session_id"])
        if verify == "document":
            check("analysis produced a document", len(run["result"]["document"]["markdown"]) > 300)
        else:
            patch = run["result"]["patch"]
            check("implementation produced a patch with tests", "pricing.py" in patch and "def test_" in patch, run["result"]["files"])
            tests = " ".join(run["result"]["implementation"]["tests"]).lower()
            check("implementation ran the tests", any(word in tests for word in ("ok", "pass", "ran")), tests)
    if review:
        client = alice
        run = wait_for_run(client, review[1])
        if check("review run completes", run["status"] == "completed", run.get("error") or run["status"]):
            plan = run["result"]["plan"]
            check("review is structured and capped", run["result"]["review"]["verdict"] and plan["comments"] <= 100, str(plan))
            check("review posted nothing in dry-run", run["result"]["posted"] == 0 or plan["mode"] == "auto")

    credentials_and_tools(base, alice, bob, carol)

    print("Metrics")
    status, body = alice.call("GET", "/metrics", raw=True)
    check("admins can read Prometheus metrics", status == 200 and b"opencode_llm_tokens_total" in body)
    check("non-admins cannot read metrics", bob.call("GET", "/metrics", raw=True)[0] == 403)

    failed = [name for name, ok, _ in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    if failed:
        print("Failed:", *failed, sep="\n  - ")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
