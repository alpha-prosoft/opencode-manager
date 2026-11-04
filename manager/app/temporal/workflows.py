import asyncio
from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.workflow import ParentClosePolicy
from temporalio.exceptions import ActivityError, WorkflowAlreadyStartedError

QUICK = {"start_to_close_timeout": timedelta(minutes=1)}
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


@workflow.defn
class PullRequestSessionWorkflow:
    @workflow.run
    async def run(self, pull_request: dict) -> str:
        session_id: str = await workflow.execute_activity("ensure_pull_request_session", pull_request, **QUICK)
        if not await workflow.execute_activity("review_enabled", **QUICK):
            return session_id
        await workflow.execute_activity("prepare_review_workspace", args=[session_id, pull_request], **BITBUCKET)
        await workflow.execute_activity("start_session", session_id, **START)
        await workflow.execute_activity("check_ready", session_id, **WAIT_READY)
        conversation: str = await workflow.execute_activity("start_review", args=[session_id, pull_request], **QUICK)
        review: str = await workflow.execute_activity("wait_for_review", args=[session_id, conversation], **REVIEW_WAIT)
        await workflow.execute_activity("save_review", args=[session_id, review], **QUICK)
        return session_id


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
                workflow_id = f"pr-session-{repo}-{pull_request['id']}"
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
