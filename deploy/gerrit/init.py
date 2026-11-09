"""Prepares the local Gerrit for the platform; safe to run again.

- sets the HTTP password of the admin account Gerrit's development mode creates (account 1000000),
- creates the platform's service account with its HTTP password,
- creates the demo project and pushes the seed sources to its main branch.

Configuration comes from the environment (see compose.ci.yml).
"""

import http.cookiejar
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from base64 import b64encode
from urllib.parse import quote

GERRIT = os.environ.get("GERRIT_URL", "http://gerrit:8080").rstrip("/")
ADMIN = os.environ.get("GERRIT_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.environ["GERRIT_ADMIN_PASSWORD"]
PLATFORM = os.environ.get("GERRIT_PLATFORM_USER", "platform")
PLATFORM_PASSWORD = os.environ["GERRIT_PLATFORM_PASSWORD"]
PROJECT = os.environ.get("GERRIT_PROJECT", "demo-service")
SEED = os.environ.get("GERRIT_SEED_DIR", "/seed/demo-service")
XSSI = ")]}'"


def log(message):
    print(f"gerrit-init: {message}", flush=True)


def parse(body):
    text = body.decode()
    if text.startswith(XSSI):
        text = text[len(XSSI):]
    return json.loads(text) if text.strip() else None


def call(method, path, body=None, auth=(ADMIN, ADMIN_PASSWORD), opener=None, headers=None):
    request = urllib.request.Request(GERRIT + path, method=method, data=None if body is None else json.dumps(body).encode())
    request.add_header("Content-Type", "application/json")
    if auth:
        request.add_header("Authorization", "Basic " + b64encode(f"{auth[0]}:{auth[1]}".encode()).decode())
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        with (opener or urllib.request.build_opener()).open(request, timeout=30) as response:
            return response.status, parse(response.read())
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(errors="replace")


def wait_for_gerrit():
    for _ in range(180):
        try:
            if call("GET", "/config/server/version", auth=None)[0] == 200:
                return
        except OSError:
            pass
        time.sleep(2)
    sys.exit("gerrit-init: Gerrit did not start")


def ensure_admin_password():
    if call("GET", "/a/accounts/self")[0] == 200:
        log("admin password already set")
        return
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    opener.open(GERRIT + "/login/?account_id=1000000", timeout=30).read()
    opener.open(GERRIT + "/", timeout=30).read()
    xsrf = next((c.value for c in jar if c.name == "XSRF_TOKEN"), None)
    if not xsrf:
        sys.exit("gerrit-init: development login did not return an XSRF token")
    xsrf_header = {"X-Gerrit-Auth": xsrf}
    status, account = call("GET", "/accounts/self", auth=None, opener=opener, headers=xsrf_header)
    if status != 200:
        sys.exit(f"gerrit-init: development login failed: {status} {account}")
    if not account.get("username"):
        call("PUT", "/accounts/self/username", {"username": ADMIN}, auth=None, opener=opener, headers=xsrf_header)
    status, answer = call("PUT", "/accounts/self/password.http", {"http_password": ADMIN_PASSWORD},
                          auth=None, opener=opener, headers=xsrf_header)
    if status != 200:
        sys.exit(f"gerrit-init: setting the admin password failed: {status} {answer}")
    log("admin password set")


def ensure_platform_account():
    status, _ = call("GET", f"/a/accounts/{PLATFORM}")
    if status == 404:
        status, answer = call("PUT", f"/a/accounts/{PLATFORM}", {
            "name": "Agent Platform",
            "email": f"{PLATFORM}@example.com",
            "http_password": PLATFORM_PASSWORD,
        })
        if status not in (200, 201):
            sys.exit(f"gerrit-init: creating account {PLATFORM} failed: {status} {answer}")
        log(f"account {PLATFORM} created")
    else:
        call("PUT", f"/a/accounts/{PLATFORM}/password.http", {"http_password": PLATFORM_PASSWORD})
        log(f"account {PLATFORM} exists, password refreshed")


def ensure_project():
    status, _ = call("GET", f"/a/projects/{quote(PROJECT, safe='')}")
    if status == 404:
        status, answer = call("PUT", f"/a/projects/{quote(PROJECT, safe='')}", {
            "description": "Demo service of the agent platform examples",
            "branches": ["main"],
            "create_empty_commit": False,
            "owners": ["Administrators"],
        })
        if status not in (200, 201):
            sys.exit(f"gerrit-init: creating project {PROJECT} failed: {status} {answer}")
        log(f"project {PROJECT} created")
    configure_access()
    if call("GET", f"/a/projects/{quote(PROJECT, safe='')}/branches/main")[0] == 200:
        log(f"project {PROJECT} already has main")
        return
    push_seed()


def configure_access():
    """Administrators may push the seed to main; the platform's commits carry the person's name and e-mail, so
    registered users may forge author and committer."""
    status, group = call("GET", "/a/groups/Administrators")
    if status != 200:
        sys.exit(f"gerrit-init: reading the Administrators group failed: {status} {group}")
    admins = {"rules": {group["id"]: {"action": "ALLOW"}}}
    registered = {"rules": {"global:Registered-Users": {"action": "ALLOW"}}}
    status, answer = call("POST", f"/a/projects/{quote(PROJECT, safe='')}/access", {
        "add": {
            "refs/heads/*": {"permissions": {"push": admins, "create": admins}},
            "refs/*": {"permissions": {"forgeAuthor": registered, "forgeCommitter": registered}},
        }
    })
    if status != 200:
        sys.exit(f"gerrit-init: configuring access of {PROJECT} failed: {status} {answer}")


def push_seed():
    work = tempfile.mkdtemp()
    try:
        shutil.copytree(SEED, work, dirs_exist_ok=True)
        auth = b64encode(f"{ADMIN}:{ADMIN_PASSWORD}".encode()).decode()
        env = dict(os.environ, GIT_AUTHOR_NAME="Agent Platform", GIT_AUTHOR_EMAIL="platform@agent-platform.local",
                   GIT_COMMITTER_NAME="Agent Platform", GIT_COMMITTER_EMAIL="platform@agent-platform.local")

        def git(*args):
            subprocess.run(["git", "-c", f"http.extraHeader=Authorization: Basic {auth}", *args],
                           cwd=work, env=env, check=True)

        git("init", "--quiet", "--initial-branch=main")
        git("add", "--all")
        git("commit", "--quiet", "-m", "Initial import of the demo service")
        git("push", "--quiet", f"{GERRIT}/a/{PROJECT}", "HEAD:refs/heads/main")
        log(f"seeded {PROJECT} main from {SEED}")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    wait_for_gerrit()
    ensure_admin_password()
    ensure_platform_account()
    ensure_project()
    log("ready")
