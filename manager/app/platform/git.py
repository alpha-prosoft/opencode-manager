import base64
import os
import re
import shutil
import subprocess
import tempfile
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import urlsplit

GIT_CONFIG = Path(tempfile.gettempdir()) / "platform.gitconfig"
GIT_CONFIG.write_text("[safe]\n\tdirectory = *\n")
KNOWN_HOSTS = os.environ.get("PLATFORM_KNOWN_HOSTS", "/data/known_hosts")
SCP_LIKE = re.compile(r"^(?P<user>[A-Za-z0-9._-]+)@(?P<host>[A-Za-z0-9.-]+):(?P<path>[^/].*)$")
GIT_ENV = {
    "GIT_TERMINAL_PROMPT": "0",
    "PATH": "/usr/bin:/bin",
    "HOME": tempfile.gettempdir(),
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_CONFIG_GLOBAL": str(GIT_CONFIG),
}


class GitError(RuntimeError):
    pass


def is_ssh(url: str) -> bool:
    return url.startswith("ssh://") or bool(SCP_LIKE.match(url))


def host_of(url: str) -> str:
    match = SCP_LIKE.match(url)
    return match.group("host") if match else (urlsplit(url).hostname or "")


def check_url(url: str, local_roots: list[str]) -> None:
    if is_ssh(url):
        return
    parts = urlsplit(url)
    if parts.scheme == "file":
        path = Path(parts.path).resolve()
        if not any(path.is_relative_to(Path(root).resolve()) for root in local_roots):
            raise GitError(f"file:// repositories must live under {', '.join(local_roots) or 'an allowed root (none configured)'}")
    elif parts.scheme != "https":
        raise GitError("only https://, ssh (git@host:repo) and file:// repositories are supported")


@contextmanager
def _credentials(url: str, username: str | None, token: str | None):
    if not token:
        yield [], {}
    elif is_ssh(url):
        with tempfile.NamedTemporaryFile("w", delete=False, prefix="key-") as key:
            key.write(token.strip() + "\n")
        os.chmod(key.name, 0o600)
        try:
            command = (
                f"ssh -i {key.name} -o IdentitiesOnly=yes -o BatchMode=yes "
                f"-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile={KNOWN_HOSTS}"
            )
            yield [], {"GIT_SSH_COMMAND": command}
        finally:
            os.unlink(key.name)
    else:
        encoded = base64.b64encode(f"{username or 'x-token-auth'}:{token}".encode()).decode()
        yield ["-c", f"http.extraHeader=Authorization: Basic {encoded}"], {}


def _run(args: list[str], token: str | None, cwd: Path | None = None, env: dict | None = None) -> str:
    result = subprocess.run(
        ["git", "-c", "credential.helper=", "-c", "protocol.file.allow=always", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        env={**GIT_ENV, **(env or {})},
        timeout=300,
    )
    if result.returncode != 0:
        message = result.stderr.strip()
        if token:
            message = message.replace(token, "***")
        fatal = [line for line in message.splitlines() if line.startswith(("fatal:", "error:"))]
        raise GitError((fatal or message.splitlines() or [f"git {args[0]} failed"])[0])
    return result.stdout.strip()


def checkout(
    url: str,
    branch: str,
    target: Path,
    username: str | None = None,
    token: str | None = None,
    depth: int = 1,
    local_roots: list[str] = (),
) -> str:
    """Clone or fast-forward `branch` of `url` into `target` and return the commit id."""
    check_url(url, list(local_roots))
    with _credentials(url, username, token) as (auth, env):
        if target.exists() and not (target / ".git").is_dir():
            shutil.rmtree(target)
        if (target / ".git").is_dir():
            _run(["remote", "set-url", "origin", url], token, cwd=target)
            _run([*auth, "fetch", "--quiet", "--depth", str(depth), "origin", branch], token, cwd=target, env=env)
            _run(["reset", "--quiet", "--hard", "FETCH_HEAD"], token, cwd=target)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            _run([*auth, "clone", "--quiet", "--depth", str(depth), "--single-branch", "--branch", branch, url, str(target)], token, env=env)
    return _run(["rev-parse", "HEAD"], token, cwd=target)


def diff(target: Path) -> str:
    _run(["add", "--all", "--intent-to-add"], None, cwd=target)
    return _run(["diff", "--no-color", "HEAD"], None, cwd=target)
