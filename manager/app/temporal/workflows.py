import asyncio
from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.workflow import ParentClosePolicy
from temporalio.exceptions import ActivityError, ApplicationError, WorkflowAlreadyStartedError

QUICK = {"start_to_close_timeout": timedelta(minutes=1)}
POST = {
    "start_to_close_timeout": timedelta(minutes=5),
    "retry_policy": RetryPolicy(initial_interval=timedelta(seconds=30), maximum_attempts=3),
}
REVIEW_NUDGES = 2
START = {
    "start_to_close_timeout": timedelta(minutes=30),
    "retry_policy": RetryPolicy(initial_interval=timedelta(seconds=5), maximum_attempts=5),
}
BITBUCKET = {
    "start_to_close_timeout": timedelta(minutes=2),
    "retry_policy": RetryPolicy(initial_interval=timedelta(seconds=10), maximum_attempts=3),
}
REVIEW_WAIT = {
    "start_to_close_timeout": timedelta(minutes=45),
    "heartbeat_timeout": timedelta(minutes=1),
    "retry_policy": RetryPolicy(maximum_attempts=3),
}
WAIT_READY = {
    "start_to_close_timeout": timedelta(seconds=10),
    "schedule_to_close_timeout": timedelta(minutes=30),
    "retry_policy": RetryPolicy(initial_interval=timedelta(seconds=2), backoff_coefficient=1.0),
}


@workflow.defn
class StartSessionWorkflow:
    @workflow.run
    async def run(self, session_id: str) -> None:
        await workflow.execute_activity("start_session", session_id, **START)
        await workflow.execute_activity("check_ready", session_id, **WAIT_READY)


@workflow.defn
class ReapSessionsWorkflow:
    @workflow.run
    async def run(self) -> list[str]:
        running: list[str] = await workflow.execute_activity("list_running_sessions", **QUICK)
        stopped = await asyncio.gather(
            *(workflow.execute_activity("stop_session_if_done", sid, **QUICK) for sid in running)
        )
        return [sid for sid, was_stopped in zip(running, stopped) if was_stopped]


def pull_request_workflow_id(repo: str, pr_id: int | str) -> str:
    return f"pr-session-{repo}-{pr_id}"


@workflow.defn
class PullRequestSessionWorkflow:
    def __init__(self) -> None:
        self.review: dict | None = None

    @workflow.signal(name="submit_review")
    def submit_review(self, review: dict) -> None:
        self.review = review

    @workflow.query(name="review")
    def get_review(self) -> dict | None:
        return self.review

    async def _review(self, session_id: str, pull_request: dict) -> str | None:
        models: list[str] = await workflow.execute_activity("review_models", **QUICK)
        for model in models:
            if self.review is not None:
                break
            conversation = None
            try:
                conversation = await workflow.execute_activity(
                    "start_review", args=[session_id, pull_request, model], **QUICK
                )
                await self._await_submission(session_id, conversation, model)
            except ActivityError as error:
                if not isinstance(error.cause, ApplicationError) or error.cause.type != "ModelUnavailable":
                    raise
                workflow.logger.warning("Model %s is unavailable (%s), trying the next one", model, error.cause.message)
                if conversation:
                    await workflow.execute_activity("abort_review", args=[session_id, conversation], **QUICK)
                continue
            return model
        return None

    async def _await_submission(self, session_id: str, conversation: str, model: str) -> None:
        for attempt in range(REVIEW_NUDGES + 1):
            await workflow.execute_activity("wait_for_review", args=[session_id, conversation], **REVIEW_WAIT)
            if self.review is not None or attempt == REVIEW_NUDGES:
                return
            await workflow.execute_activity("nudge_review", args=[session_id, conversation, model], **QUICK)

    @workflow.run
    async def run(self, pull_request: dict) -> dict:
        session_id: str = await workflow.execute_activity("ensure_pull_request_session", pull_request, **QUICK)
        result = {"session_id": session_id}
        if not await workflow.execute_activity("review_enabled", **QUICK):
            return result
        await workflow.execute_activity("prepare_review_workspace", args=[session_id, pull_request], **BITBUCKET)
        await workflow.execute_activity("install_model_credentials", session_id, **QUICK)
        try:
            await workflow.execute_activity("start_session", session_id, **START)
            await workflow.execute_activity("check_ready", session_id, **WAIT_READY)
            result["model"] = await self._review(session_id, pull_request)
        finally:
            await workflow.execute_activity("remove_model_credentials", session_id, **QUICK)
        if self.review is None:
            raise ApplicationError("No model produced a submitted review", non_retryable=True)

        await workflow.execute_activity("save_review", args=[session_id, self.review], **QUICK)
        plan: dict = await workflow.execute_activity("plan_review_post", args=[session_id, self.review], **QUICK)
        result |= {"verdict": self.review["verdict"], "mode": plan["mode"], "comments": len(plan["comments"])}
        if plan["mode"] == "auto":
            result["posted"] = await workflow.execute_activity(
                "post_review", args=[session_id, pull_request, plan], **POST
            )
        return result


@workflow.defn
class PollPullRequestsWorkflow:
    @workflow.run
    async def run(self) -> list[str]:
        repos: list[str] = await workflow.execute_activity("bitbucket_repositories", **QUICK)
        started = []
        for repo in repos:
            try:
                pull_requests: list[dict] = await workflow.execute_activity("list_new_pull_requests", repo, **BITBUCKET)
            except ActivityError as error:
                workflow.logger.warning("Listing pull requests in %s failed: %s", repo, error)
                continue
            for pull_request in pull_requests:
                workflow_id = pull_request_workflow_id(repo, pull_request["id"])
                try:
                    await workflow.start_child_workflow(
                        PullRequestSessionWorkflow.run,
                        pull_request,
                        id=workflow_id,
                        parent_close_policy=ParentClosePolicy.ABANDON,
                    )
                except WorkflowAlreadyStartedError:
                    continue
                started.append(workflow_id)
        return started
