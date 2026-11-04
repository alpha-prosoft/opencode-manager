import json
from pathlib import Path

from . import bitbucket, opencode
from .config import Settings
from .models import SessionRecord
from .sessions import REVIEW_CONVERSATION_LABEL, SessionService
from .storage import session_subpath

REVIEW_DIR = ".review"

DEFAULT_GUIDELINES = """# Review guidelines

Review the change for correctness, security, and consistency with the conventions already used in the
repository. Report only issues you verified in the diff or the repository.

Output: a short summary, a verdict (APPROVE, APPROVE WITH NITS, REQUEST CHANGES), and findings with
severity (blocker, major, minor, nit), file path, problem and concrete fix.
"""

PROMPT = """You are reviewing Bitbucket pull request #{id} "{title}" in {workspace}/{repo}.

- The PR source branch is checked out in `{repo}/`.
- The PR diff is in `{review_dir}/pr.diff` and its metadata in `{review_dir}/pull-request.md`.
- The review guidelines for this repository are in `{review_dir}/guidelines.md`.

Read the guidelines first, then the diff. Inspect the repository for the context the guidelines ask for
(for example existing bindings or linting data). Do not modify, create or delete any files.

Reply with the complete review in the output format the guidelines require."""


class ReviewQuotaExceeded(Exception):
    pass


class PullRequestReviews:
    def __init__(self, settings: Settings, service: SessionService):
        self.settings = settings
        self.service = service

    def _record(self, session_id: str) -> SessionRecord:
        record = self.service.get(session_id)
        if record is None:
            raise LookupError(f"Session {session_id} not found")
        return record

    def _workspace(self, session_id: str) -> Path:
        return Path(self.settings.shared_mount_path) / session_subpath(session_id) / "workspace"

    def _guidelines(self, repo: str) -> str:
        path = Path(self.settings.review_guidelines_dir) / f"{repo}.md"
        return path.read_text() if path.is_file() else DEFAULT_GUIDELINES

    def prepare_workspace(self, session_id: str, pr: dict) -> None:
        self.service.storage.prepare(session_id)
        workspace = self._workspace(session_id)
        checkout = workspace / pr["repo"]
        if not checkout.exists():
            bitbucket.clone_branch(
                self.settings.bitbucket_token, pr["workspace"], pr["repo"], pr["source_branch"], checkout
            )
        review_dir = workspace / REVIEW_DIR
        review_dir.mkdir(exist_ok=True)
        diff = bitbucket.pull_request_diff(self.settings.bitbucket_token, pr["workspace"], pr["repo"], pr["id"])
        (review_dir / "pr.diff").write_text(diff)
        (review_dir / "guidelines.md").write_text(self._guidelines(pr["repo"]))
        (review_dir / "pull-request.md").write_text(
            f"# PR #{pr['id']}: {pr['title']}\n\n"
            f"- Repository: {pr['workspace']}/{pr['repo']}\n"
            f"- Author: {pr['author']}\n"
            f"- Branch: {pr['source_branch']} → {pr['destination_branch']}\n"
            f"- Commit: {pr['source_commit']}\n"
            f"- URL: {pr['url']}\n\n"
            f"```json\n{json.dumps(pr, indent=2)}\n```\n"
        )

    def start(self, session_id: str, pr: dict) -> str:
        record = self._record(session_id)
        base_url = self.service.backend.service_url(session_id)
        conversation = opencode.create_session(base_url, record.secret_key, f"Code review PR #{pr['id']}")
        prompt = PROMPT.format(review_dir=REVIEW_DIR, **pr)
        opencode.prompt_async(base_url, record.secret_key, conversation, self.settings.review_model, prompt)
        self.service.store.update(session_id, {"labels": {**record.labels, REVIEW_CONVERSATION_LABEL: conversation}})
        return conversation

    def result(self, session_id: str, conversation: str) -> str | None:
        record = self._record(session_id)
        base_url = self.service.backend.service_url(session_id)
        status = opencode.session_statuses(base_url, record.secret_key).get(conversation)
        if status and status.get("action", {}).get("reason") == "free_tier_limit":
            raise ReviewQuotaExceeded(f"{self.settings.review_model}: {status.get('message')}")
        if status and status.get("type") != "idle":
            return None
        return opencode.last_reply(base_url, record.secret_key, conversation)

    def save(self, session_id: str, review: str) -> None:
        (self._workspace(session_id) / REVIEW_DIR / "review.md").write_text(review + "\n")
        record = self._record(session_id)
        self.service.store.update(session_id, {"labels": {**record.labels, "review": "done"}})
