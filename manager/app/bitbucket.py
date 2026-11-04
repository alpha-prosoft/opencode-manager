import httpx

API = "https://api.bitbucket.org/2.0"
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
