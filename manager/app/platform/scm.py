from dataclasses import asdict, dataclass

import httpx


@dataclass
class PullRequest:
    id: int
    title: str
    author: str
    source_branch: str
    source_commit: str
    destination_branch: str
    url: str
    clone_url: str

    def dict(self) -> dict:
        return asdict(self)


class Provider:
    git_username = "x-token-auth"
    git_host = ""

    def __init__(self, token: str):
        self.token = token

    def open_pull_requests(self, repository: str) -> list[PullRequest]: ...
    def pull_request(self, repository: str, number: int) -> PullRequest: ...
    def diff(self, repository: str, number: int) -> str: ...
    def comment(self, repository: str, number: int, content: str, path: str | None, line: int | None) -> int: ...
    def create_pull_request(
        self, repository: str, source: str, destination: str, title: str, description: str, draft: bool = False
    ) -> PullRequest: ...


class Bitbucket(Provider):
    api = "https://api.bitbucket.org/2.0"
    git_username = "x-bitbucket-api-token-auth"
    git_host = "bitbucket.org"

    def _client(self) -> httpx.Client:
        return httpx.Client(base_url=self.api, headers={"Authorization": f"Bearer {self.token}"}, timeout=60, follow_redirects=True)

    @staticmethod
    def _parse(repository: str, pr: dict) -> PullRequest:
        return PullRequest(
            id=pr["id"],
            title=pr["title"],
            author=pr["author"]["display_name"],
            source_branch=pr["source"]["branch"]["name"],
            source_commit=pr["source"]["commit"]["hash"],
            destination_branch=pr["destination"]["branch"]["name"],
            url=pr["links"]["html"]["href"],
            clone_url=f"https://bitbucket.org/{repository}.git",
        )

    def open_pull_requests(self, repository: str) -> list[PullRequest]:
        results, url, params = [], f"/repositories/{repository}/pullrequests", {"state": "OPEN", "pagelen": 50}
        with self._client() as http:
            while url:
                response = http.get(url, params=params)
                response.raise_for_status()
                page = response.json()
                results += [self._parse(repository, pr) for pr in page["values"]]
                url, params = page.get("next"), None
        return results

    def pull_request(self, repository: str, number: int) -> PullRequest:
        with self._client() as http:
            response = http.get(f"/repositories/{repository}/pullrequests/{number}")
            response.raise_for_status()
            return self._parse(repository, response.json())

    def diff(self, repository: str, number: int) -> str:
        with self._client() as http:
            response = http.get(f"/repositories/{repository}/pullrequests/{number}/diff")
            response.raise_for_status()
            return response.text

    def comment(self, repository: str, number: int, content: str, path: str | None, line: int | None) -> int:
        body: dict = {"content": {"raw": content}}
        if path and line:
            body["inline"] = {"path": path, "to": line}
        with self._client() as http:
            response = http.post(f"/repositories/{repository}/pullrequests/{number}/comments", json=body)
            response.raise_for_status()
            return response.json()["id"]

    def create_pull_request(
        self, repository: str, source: str, destination: str, title: str, description: str, draft: bool = False
    ) -> PullRequest:
        body = {
            "draft": draft,
            "title": title,
            "description": description,
            "source": {"branch": {"name": source}},
            "destination": {"branch": {"name": destination}},
            "close_source_branch": True,
        }
        with self._client() as http:
            response = http.post(f"/repositories/{repository}/pullrequests", json=body)
            response.raise_for_status()
            return self._parse(repository, response.json())


class GitHub(Provider):
    api = "https://api.github.com"
    git_username = "x-access-token"
    git_host = "github.com"

    def _client(self, accept: str = "application/vnd.github+json") -> httpx.Client:
        headers = {"Authorization": f"Bearer {self.token}", "Accept": accept, "X-GitHub-Api-Version": "2022-11-28"}
        return httpx.Client(base_url=self.api, headers=headers, timeout=60, follow_redirects=True)

    @staticmethod
    def _parse(repository: str, pr: dict) -> PullRequest:
        return PullRequest(
            id=pr["number"],
            title=pr["title"],
            author=pr["user"]["login"],
            source_branch=pr["head"]["ref"],
            source_commit=pr["head"]["sha"],
            destination_branch=pr["base"]["ref"],
            url=pr["html_url"],
            clone_url=pr["head"]["repo"]["clone_url"] if pr["head"].get("repo") else f"https://github.com/{repository}.git",
        )

    def open_pull_requests(self, repository: str) -> list[PullRequest]:
        results, page = [], 1
        with self._client() as http:
            while True:
                response = http.get(f"/repos/{repository}/pulls", params={"state": "open", "per_page": 100, "page": page})
                response.raise_for_status()
                batch = response.json()
                results += [self._parse(repository, pr) for pr in batch]
                if len(batch) < 100:
                    return results
                page += 1

    def pull_request(self, repository: str, number: int) -> PullRequest:
        with self._client() as http:
            response = http.get(f"/repos/{repository}/pulls/{number}")
            response.raise_for_status()
            return self._parse(repository, response.json())

    def diff(self, repository: str, number: int) -> str:
        with self._client("application/vnd.github.diff") as http:
            response = http.get(f"/repos/{repository}/pulls/{number}")
            response.raise_for_status()
            return response.text

    def comment(self, repository: str, number: int, content: str, path: str | None, line: int | None) -> int:
        with self._client() as http:
            if path and line:
                head = self.pull_request(repository, number).source_commit
                response = http.post(
                    f"/repos/{repository}/pulls/{number}/comments",
                    json={"body": content, "commit_id": head, "path": path, "line": line, "side": "RIGHT"},
                )
            else:
                response = http.post(f"/repos/{repository}/issues/{number}/comments", json={"body": content})
            response.raise_for_status()
            return response.json()["id"]

    def create_pull_request(
        self, repository: str, source: str, destination: str, title: str, description: str, draft: bool = False
    ) -> PullRequest:
        payload = {"title": title, "body": description, "head": source, "base": destination, "draft": draft}
        with self._client() as http:
            response = http.post(f"/repos/{repository}/pulls", json=payload)
            response.raise_for_status()
            return self._parse(repository, response.json())


PROVIDERS: dict[str, type[Provider]] = {"bitbucket": Bitbucket, "github": GitHub}


def provider(name: str, token: str) -> Provider:
    return PROVIDERS[name](token)
