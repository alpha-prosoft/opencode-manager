import json
import shutil
from pathlib import Path

from . import bitbucket, opencode
from .config import Settings
from .models import SessionRecord
from .review_post import PostPlan, ReviewSubmission, plan_post
from .sessions import REVIEW_CONVERSATION_LABEL, SessionService
from .storage import session_subpath

REVIEW_DIR = ".review"
REVIEW_TOOL = "submit_review"
MCP_SERVER_NAME = "review"

DEFAULT_GUIDELINES = """# Review guidelines

Review the change for correctness, security, and consistency with the conventions already used in the
repository. Report only issues you verified in the diff or the repository.

Findings need a severity (blocker, major, minor, question, nit), the file path and line, the problem and the
concrete fix.
"""

PROMPT = """You are reviewing Bitbucket pull request #{id} "{title}" in {workspace}/{repo}.

- The PR source branch is checked out in `{repo}/`.
- The PR diff is in `{review_dir}/pr.diff` and its metadata in `{review_dir}/pull-request.md`.
- The review guidelines for this repository are in `{review_dir}/guidelines.md`.

Read the guidelines first, then the diff. Inspect the repository for the context the guidelines ask for
(for example existing bindings or linting data). Do not modify, create or delete any files.

When you are done, submit the review by calling the `{tool}` tool exactly once:
- `verdict`: APPROVE, APPROVE_WITH_NITS or REQUEST_CHANGES
- `summary`: what the PR changes, in one or two sentences
- `findings`: one entry per issue, with `path` and `line` pointing at the line in the NEW version of the file
  that the diff touches (leave both empty for PR-level remarks)
- `checked`: the checklist items you verified as OK

The tool validates the review; if it reports an error, fix the arguments and call it again. Do not post
anything to Bitbucket yourself: the workflow decides what gets posted. After the tool accepts the review,
reply with a short confirmation."""

NUDGE = """You have not submitted the review yet. Call the `{tool}` tool now with your verdict, summary, findings
and checked items."""


class ModelUnavailable(Exception):
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

    def _model_auth_path(self, session_id: str) -> Path:
        home = Path(self.settings.shared_mount_path) / session_subpath(session_id) / "home"
        return home / ".local" / "share" / "opencode" / "auth.json"

    def install_model_credentials(self, session_id: str) -> bool:
        source = Path(self.settings.review_model_auth_file)
        if not source.is_file():
            return False
        target = self._model_auth_path(session_id)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        target.chmod(0o600)
        return True

    def remove_model_credentials(self, session_id: str) -> None:
        self._model_auth_path(session_id).unlink(missing_ok=True)

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

    def start(self, session_id: str, pr: dict, model: str) -> str:
        record = self._record(session_id)
        base_url = self.service.backend.service_url(session_id)
        provider = model.split("/", 1)[0]
        if provider not in opencode.connected_providers(base_url, record.secret_key):
            raise ModelUnavailable(f"Provider {provider} is not connected in this session")
        conversation = opencode.create_session(base_url, record.secret_key, f"Code review PR #{pr['id']} ({model})")
        prompt = PROMPT.format(review_dir=REVIEW_DIR, tool=REVIEW_TOOL, **pr)
        opencode.prompt_async(base_url, record.secret_key, conversation, model, prompt)
        self.service.store.update(session_id, {"labels": {**record.labels, REVIEW_CONVERSATION_LABEL: conversation}})
        return conversation

    def result(self, session_id: str, conversation: str) -> str | None:
        record = self._record(session_id)
        base_url = self.service.backend.service_url(session_id)
        status = opencode.session_statuses(base_url, record.secret_key).get(conversation)
        if status and status.get("action", {}).get("reason") == "free_tier_limit":
            raise ModelUnavailable(status.get("message") or "Model quota exhausted")
        if status and status.get("type") != "idle":
            return None
        try:
            return opencode.last_reply(base_url, record.secret_key, conversation)
        except RuntimeError as error:
            raise ModelUnavailable(str(error)) from error

    def nudge(self, session_id: str, conversation: str, model: str) -> None:
        record = self._record(session_id)
        base_url = self.service.backend.service_url(session_id)
        opencode.prompt_async(base_url, record.secret_key, conversation, model, NUDGE.format(tool=REVIEW_TOOL))

    def abort(self, session_id: str, conversation: str) -> None:
        record = self._record(session_id)
        opencode.abort(self.service.backend.service_url(session_id), record.secret_key, conversation)

    def opencode_config(self, record: SessionRecord) -> dict:
        return {
            **self.settings.review_opencode_config,
            "mcp": {
                MCP_SERVER_NAME: {
                    "type": "remote",
                    "url": f"{self.settings.manager_service_url}/mcp/",
                    "headers": {"X-Review-Session": record.id, "X-Review-Key": record.secret_key},
                    "oauth": False,
                    "enabled": True,
                }
            },
        }

    def save(self, session_id: str, review: ReviewSubmission) -> None:
        review_dir = self._workspace(session_id) / REVIEW_DIR
        (review_dir / "review.json").write_text(review.model_dump_json(indent=2))
        record = self._record(session_id)
        self.service.store.update(session_id, {"labels": {**record.labels, "review": review.verdict.lower()}})

    def plan(self, session_id: str, review: ReviewSubmission) -> PostPlan:
        review_dir = self._workspace(session_id) / REVIEW_DIR
        diff = (review_dir / "pr.diff").read_text()
        record = self._record(session_id)
        plan = plan_post(
            review,
            diff,
            self.settings.review_post_mode,
            self.settings.review_max_comments,
            self.service.entry_url(record),
        )
        (review_dir / "post-plan.json").write_text(plan.model_dump_json(indent=2))
        return plan

    def post(self, session_id: str, pr: dict, plan: PostPlan) -> int:
        posted = self.service.store.posted_review_comments(session_id)
        count = 0
        for comment in plan.comments:
            if comment.key in posted:
                continue
            comment_id = bitbucket.post_comment(
                self.settings.bitbucket_token,
                pr["workspace"],
                pr["repo"],
                pr["id"],
                comment.content,
                comment.path,
                comment.line,
            )
            self.service.store.record_review_comment(session_id, comment.key, comment_id)
            count += 1
        return count
