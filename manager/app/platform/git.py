import base64
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

GIT_CONFIG = Path(tempfile.gettempdir()) / "platform.gitconfig"
GIT_CONFIG.write_text("[safe]\n\tdirectory = *\n")
GIT_ENV = {
    "GIT_TERMINAL_PROMPT": "0",
    "PATH": "/usr/bin:/bin",
    "HOME": tempfile.gettempdir(),
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_CONFIG_GLOBAL": str(GIT_CONFIG),
}


class GitError(RuntimeError):
    pass


def _auth_args(username: str | None, token: str | None) -> list[str]:
    if not token:
        return []
    credentials = base64.b64encode(f"{username or 'x-token-auth'}:{token}".encode()).decode()
    return ["-c", f"http.extraHeader=Authorization: Basic {credentials}"]


def _run(args: list[str], token: str | None, cwd: Path | None = None) -> str:
    result = subprocess.run(
        ["git", "-c", "credential.helper=", "-c", "protocol.file.allow=always", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        env=GIT_ENV,
        timeout=300,
    )
    if result.returncode != 0:
        message = result.stderr.strip()
        if token:
            message = message.replace(token, "***")
        fatal = [line for line in message.splitlines() if line.startswith(("fatal:", "error:"))]
        raise GitError((fatal or message.splitlines() or [f"git {args[0]} failed"])[0])
    return result.stdout.strip()


def check_url(url: str, local_roots: list[str]) -> None:
    parts = urlsplit(url)
    if parts.scheme == "file":
        path = Path(parts.path).resolve()
        if not any(path.is_relative_to(Path(root).resolve()) for root in local_roots):
            raise GitError(f"file:// repositories must live under {', '.join(local_roots) or 'an allowed root (none configured)'}")
    elif parts.scheme != "https":
        raise GitError("only https:// and file:// repositories are supported")


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
    auth = _auth_args(username, token)
    if (target / ".git").is_dir():
        _run([*auth, "fetch", "--quiet", "--depth", str(depth), "origin", branch], token, cwd=target)
        _run(["reset", "--quiet", "--hard", "FETCH_HEAD"], token, cwd=target)
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        _run([*auth, "clone", "--quiet", "--depth", str(depth), "--single-branch", "--branch", branch, url, str(target)], token)
    return _run(["rev-parse", "HEAD"], token, cwd=target)


def diff(target: Path) -> str:
    _run(["add", "--all", "--intent-to-add"], None, cwd=target)
    return _run(["diff", "--no-color", "HEAD"], None, cwd=target)
