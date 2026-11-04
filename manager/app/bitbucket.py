import subprocess
from pathlib import Path

import httpx

API = "https://api.bitbucket.org/2.0"
GIT_USER = "x-bitbucket-api-token-auth"
PR_FIELDS = ",".join(
    [
        "next",
        "values.id",
        "values.title",
        "values.author.display_name",
        "values.source.branch.name",
        "values.source.commit.hash",
        "values.destination.branch.name",
        "values.links.html.href",
    ]
)


def list_open_pull_requests(token: str, workspace: str, repo: str) -> list[dict]:
    url = f"{API}/repositories/{workspace}/{repo}/pullrequests"
    params = {"state": "OPEN", "pagelen": 50, "fields": PR_FIELDS}
    pull_requests = []
    with httpx.Client(headers={"Authorization": f"Bearer {token}"}, timeout=30) as http:
        while url:
            response = http.get(url, params=params)
            response.raise_for_status()
            page = response.json()
            pull_requests += [
                {
                    "workspace": workspace,
                    "repo": repo,
                    "id": pr["id"],
                    "title": pr["title"],
                    "author": pr["author"]["display_name"],
                    "source_branch": pr["source"]["branch"]["name"],
                    "source_commit": pr["source"]["commit"]["hash"],
                    "destination_branch": pr["destination"]["branch"]["name"],
                    "url": pr["links"]["html"]["href"],
                }
                for pr in page["values"]
            ]
            url, params = page.get("next"), None
    return pull_requests


def pull_request_diff(token: str, workspace: str, repo: str, pr_id: int) -> str:
    url = f"{API}/repositories/{workspace}/{repo}/pullrequests/{pr_id}/diff"
    response = httpx.get(url, headers={"Authorization": f"Bearer {token}"}, timeout=60, follow_redirects=True)
    response.raise_for_status()
    return response.text


def clone_branch(token: str, workspace: str, repo: str, branch: str, target: Path) -> None:
    public_url = f"https://bitbucket.org/{workspace}/{repo}.git"
    authenticated_url = public_url.replace("https://", f"https://{GIT_USER}:{token}@")
    git = ["git", "-c", "credential.helper="]
    clone = subprocess.run(
        [*git, "clone", "--quiet", "--depth", "50", "--single-branch", "--branch", branch, authenticated_url, str(target)],
        capture_output=True,
        text=True,
        env={"GIT_TERMINAL_PROMPT": "0", "PATH": "/usr/bin:/bin"},
    )
    if clone.returncode != 0:
        error = clone.stderr.replace(token, "***").strip()
        raise RuntimeError(f"git clone of {public_url} ({branch}) failed: {error}")
    subprocess.run([*git, "-C", str(target), "remote", "set-url", "origin", public_url], check=True)
