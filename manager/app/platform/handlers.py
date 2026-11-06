from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlsplit

from pydantic import BaseModel, Field

from ..review_post import PostPlan, ReviewSubmission, plan_post
from . import git, scm
from .catalog import Activity, Options
from .spec import GitSource, TeamEntry, WorkflowSpec

CONTEXT_DIR = ".platform"
REPO_DIR = "repo"
MAX_PATCH_BYTES = 1_000_000

GENERIC_REVIEW_GUIDELINES = """# Review guidelines

Review the change for correctness, security, and consistency with the conventions already used in the
repository. Report only issues you verified in the diff or the repository. Phrase anything you cannot verify
as a question.
"""


class HandlerError(Exception):
    """A problem with the run's inputs or options that retrying won't fix."""


class ImplementationSubmission(BaseModel):
    title: str = Field(default="", max_length=120, description="Short title for the commit and pull request")
    summary: str = Field(max_length=4000, description="What was changed and why")
    verified: bool = Field(default=False, description="True only if the project's tests or checks ran and passed")
    changes: list[str] = Field(default=[], max_length=100, description="One line per notable change")
    tests: list[str] = Field(default=[], max_length=50, description="Commands run to verify the change and their outcome")
    notes: str = Field(default="", max_length=4000, description="Follow-ups, risks or open questions")


class DocumentSubmission(BaseModel):
    title: str = Field(max_length=200)
    markdown: str = Field(max_length=200_000, description="The complete document in Markdown")


SUBMISSIONS: dict[str, type[BaseModel]] = {
    "submit_review": ReviewSubmission,
    "submit_implementation": ImplementationSubmission,
    "submit_document": DocumentSubmission,
}


@dataclass
class RunContext:
    run: dict
    team: TeamEntry
    workflow: WorkflowSpec
    activity: Activity
    options: Options
    inputs: Options
    workspace: Path
    secrets: object
    read_team_file: callable
    session_url: str
    secret_host_allowed: callable
    local_roots: list
    author: tuple[str, str] = ("Agent Platform", "agent-platform@noreply.invalid")

    @property
    def context_dir(self) -> Path:
        return self.workspace / CONTEXT_DIR

    def write_context(self, name: str, content: str) -> None:
        self.context_dir.mkdir(parents=True, exist_ok=True)
        (self.context_dir / name).write_text(content)

    def checkout(self, source: GitSource, branch: str | None = None) -> str:
        token = None
        if source.credentials:
            if not self.secret_host_allowed(source.credentials, source.url):
                raise ValueError(f"Secret {source.credentials} may not be sent to {urlsplit(source.url).hostname}")
            token = self.secrets.require(source.credentials)
        return git.checkout(
            source.url, branch or source.branch, self.workspace / REPO_DIR, source.username, token, 50, self.local_roots
        )

    def team_file(self, relative: str | None, default: str = "") -> str:
        return self.read_team_file(relative) if relative else default


class Handler:
    def prepare(self, ctx: RunContext) -> None: ...

    def prompt(self, ctx: RunContext) -> str | None:
        return None

    def finalize(self, ctx: RunContext, submission: dict, store) -> dict:
        return {}


class SessionHandler(Handler):
    def prepare(self, ctx: RunContext) -> None:
        if ctx.options.repository:
            ctx.checkout(ctx.options.repository, ctx.inputs.branch)

    def prompt(self, ctx: RunContext) -> str | None:
        return ctx.inputs.prompt


class ReviewHandler(Handler):
    def _scm(self, ctx: RunContext):
        if ctx.options.scm is None:
            raise ValueError("The review activity needs options.scm (or a pull_request trigger)")
        return ctx.options.scm, scm.provider(ctx.options.scm.provider, ctx.secrets.require(ctx.options.scm.credentials))

    def prepare(self, ctx: RunContext) -> None:
        source, provider = self._scm(ctx)
        pr = provider.pull_request(source.repository, ctx.inputs.pull_request)
        if urlsplit(pr.clone_url).hostname != provider.git_host:
            raise ValueError(f"Refusing to clone {pr.clone_url}: not hosted on {provider.git_host}")
        git.checkout(
            pr.clone_url, pr.source_branch, ctx.workspace / REPO_DIR, provider.git_username, provider.token, depth=50
        )
        ctx.write_context("pr.diff", provider.diff(source.repository, pr.id))
        ctx.write_context("guidelines.md", ctx.team_file(ctx.options.guidelines_file, GENERIC_REVIEW_GUIDELINES))
        ctx.write_context(
            "pull-request.md",
            f"# PR #{pr.id}: {pr.title}\n\n- Repository: {source.repository} ({source.provider})\n- Author: {pr.author}\n"
            f"- Branch: {pr.source_branch} → {pr.destination_branch}\n- Commit: {pr.source_commit}\n- URL: {pr.url}\n",
        )

    def prompt(self, ctx: RunContext) -> str:
        return (
            f"Review pull request #{ctx.inputs.pull_request} of {ctx.options.scm.repository}.\n\n"
            f"- The PR source branch is checked out in `{REPO_DIR}/`.\n"
            f"- The diff is in `{CONTEXT_DIR}/pr.diff`, metadata in `{CONTEXT_DIR}/pull-request.md`.\n"
            f"- Review guidelines: `{CONTEXT_DIR}/guidelines.md`. Read them first.\n\n"
            "Read every hunk of the diff, including removed lines. Inspect the repository for context. "
            "Do not modify any files.\n\n"
            "Submit the result by calling the `submit_review` tool exactly once (verdict, summary, findings with "
            "path and NEW-file line inside the diff, checked items). If it reports a validation error, fix the "
            "arguments and call it again. Do not post anything anywhere yourself."
        )

    def finalize(self, ctx: RunContext, submission: dict, store) -> dict:
        review = ReviewSubmission.model_validate(submission)
        diff = (ctx.context_dir / "pr.diff").read_text()
        plan: PostPlan = plan_post(review, diff, ctx.options.post_mode, ctx.options.max_comments, ctx.session_url)
        (ctx.context_dir / "review.json").write_text(review.model_dump_json(indent=2))
        (ctx.context_dir / "post-plan.json").write_text(plan.model_dump_json(indent=2))
        posted = 0
        if plan.mode == "auto":
            source, provider = self._scm(ctx)
            already = store.posted_review_comments(ctx.run["session_id"])
            for comment in plan.comments:
                if comment.key in already:
                    continue
                comment_id = provider.comment(source.repository, ctx.inputs.pull_request, comment.content, comment.path, comment.line)
                store.record_review_comment(ctx.run["session_id"], comment.key, comment_id)
                posted += 1
        return {
            "review": review.model_dump(),
            "plan": {"mode": plan.mode, "comments": len(plan.comments), "inline": plan.inline_count, "folded": plan.folded_count},
            "posted": posted,
        }


def short_title(text: str, limit: int = 100) -> str:
    line = text.strip().splitlines()[0] if text.strip() else ""
    if len(line) <= limit:
        return line
    cut = line[:limit].rsplit(" ", 1)[0]
    return cut.rstrip(" ,;:") + "…"


class ImplementHandler(Handler):
    def _provider(self, ctx: RunContext):
        source = ctx.options.repository
        token = ctx.secrets.require(source.credentials) if source.credentials else None
        return scm.provider(ctx.options.pull_request.provider, token or ""), token

    def prepare(self, ctx: RunContext) -> None:
        branch = ctx.inputs.branch
        if ctx.inputs.pull_request:
            if not ctx.options.pull_request:
                raise HandlerError("This workflow does not deliver pull requests, so it can't continue on one")
            provider, _ = self._provider(ctx)
            pr = provider.pull_request(ctx.options.pull_request.repository, ctx.inputs.pull_request)
            branch = pr.source_branch
            ctx.write_context("pull_request.md", f"# Pull request #{pr.id}: {pr.title}\n\nBranch `{pr.source_branch}` into `{pr.destination_branch}`.\n")
        ctx.checkout(ctx.options.repository, branch)
        ctx.write_context("task.md", ctx.inputs.task)
        if ctx.options.guidelines_file:
            ctx.write_context("guidelines.md", ctx.team_file(ctx.options.guidelines_file))

    def prompt(self, ctx: RunContext) -> str:
        guidelines = f" Follow `{CONTEXT_DIR}/guidelines.md`." if ctx.options.guidelines_file else ""
        continuing = (
            f" You are continuing pull request #{ctx.inputs.pull_request} (see `{CONTEXT_DIR}/pull_request.md`); its branch is checked out."
            if ctx.inputs.pull_request
            else ""
        )
        return (
            f"Implement the task in `{CONTEXT_DIR}/task.md` in the repository checked out at `{REPO_DIR}/`.{continuing}{guidelines}\n\n"
            "Make focused changes, run the project's tests or linters if they exist, and do not commit or push. "
            "If you need a virtual environment, create it in your home directory. "
            "When you are done, call the `submit_implementation` tool exactly once with a short title, a summary, "
            "the list of changes, the commands you ran and their outcome, and any notes. Set `verified` to true only "
            "if the tests or checks actually ran and passed."
        )

    def finalize(self, ctx: RunContext, submission: dict, store) -> dict:
        result = ImplementationSubmission.model_validate(submission)
        patch = git.diff(ctx.workspace / REPO_DIR)
        truncated = len(patch.encode()) > MAX_PATCH_BYTES
        files = sorted({line[6:] for line in patch.splitlines() if line.startswith("+++ b/")})
        (ctx.context_dir / "change.patch").write_text(patch)
        output = {
            "implementation": result.model_dump(),
            "patch": patch[:MAX_PATCH_BYTES],
            "patch_truncated": truncated,
            "files": files,
        }
        if ctx.options.pull_request and patch:
            output["pull_request"] = self._deliver(ctx, result)
        return output

    def _description(self, ctx: RunContext, result: ImplementationSubmission) -> str:
        person = ctx.run["created_by"]
        lines = [result.summary, ""]
        lines += [f"- {change}" for change in result.changes]
        if result.tests:
            lines += ["", "**How the agent verified it:** " + "; ".join(result.tests)]
        if not result.verified:
            lines += ["", "**Not verified:** the agent could not run the checks successfully, so this is a draft."]
        if result.notes:
            lines += ["", f"**Notes:** {result.notes}"]
        lines += ["", f"_Opened by the agent platform for {person}._" + (f" [Session]({ctx.session_url})" if ctx.session_url else "")]
        return "\n".join(lines)

    def _deliver(self, ctx: RunContext, result: ImplementationSubmission) -> dict:
        delivery = ctx.options.pull_request
        source = ctx.options.repository
        provider, token = self._provider(ctx)
        title = short_title(result.title or result.summary) or ctx.workflow.name
        message = f"{title}\n\nMade by an agent for {ctx.run['created_by']} (run {ctx.run['id']})."
        if ctx.inputs.pull_request:
            pr = provider.pull_request(delivery.repository, ctx.inputs.pull_request)
            commit = git.commit_and_push(ctx.workspace / REPO_DIR, source.url, pr.source_branch, provider.git_username, token, message, ctx.author)
            pushed = {"pushed_at": datetime.now(UTC).isoformat()}
            return {"id": pr.id, "url": pr.url, "branch": pr.source_branch, "target": pr.destination_branch, "commit": commit, "updated": True, **pushed}
        branch = f"agent/{ctx.run['id']}"
        commit = git.commit_and_push(ctx.workspace / REPO_DIR, source.url, branch, provider.git_username, token, message, ctx.author)
        target = delivery.target_branch or ctx.inputs.branch or source.branch
        draft = not result.verified
        pr = provider.create_pull_request(delivery.repository, branch, target, title, self._description(ctx, result), draft)
        return {
            "id": pr.id, "url": pr.url, "branch": branch, "target": target, "commit": commit, "draft": draft,
            "pushed_at": datetime.now(UTC).isoformat(),
        }


class AnalysisHandler(Handler):
    def prepare(self, ctx: RunContext) -> None:
        if ctx.options.repository:
            ctx.checkout(ctx.options.repository)
        ctx.write_context("brief.md", ctx.inputs.brief)
        if ctx.options.template_file:
            ctx.write_context("template.md", ctx.team_file(ctx.options.template_file))

    def prompt(self, ctx: RunContext) -> str:
        template = f" Use the structure in `{CONTEXT_DIR}/template.md`." if ctx.options.template_file else ""
        repository = f" The repository at `{REPO_DIR}/` is available for context." if ctx.options.repository else ""
        return (
            f"Write a business analysis for the brief in `{CONTEXT_DIR}/brief.md`.{template}{repository}\n\n"
            "Cover goals, stakeholders, functional and non-functional requirements, assumptions, risks and open "
            "questions. Do not modify any repository files. When the document is complete, call the "
            "`submit_document` tool exactly once with a title and the full Markdown."
        )

    def finalize(self, ctx: RunContext, submission: dict, store) -> dict:
        document = DocumentSubmission.model_validate(submission)
        ctx.write_context("analysis.md", document.markdown)
        return {"document": document.model_dump()}


HANDLERS: dict[str, Handler] = {
    "session": SessionHandler(),
    "review": ReviewHandler(),
    "implement": ImplementHandler(),
    "analysis": AnalysisHandler(),
}

