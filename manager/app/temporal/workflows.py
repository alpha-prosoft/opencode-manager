import asyncio
from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ActivityError, ApplicationError, WorkflowAlreadyStartedError
from temporalio.workflow import ParentClosePolicy

MODEL_UNAVAILABLE = "ModelUnavailable"
NUDGES = 2

QUICK = {"start_to_close_timeout": timedelta(minutes=1)}
SLOW = {
    "start_to_close_timeout": timedelta(minutes=10),
    "retry_policy": RetryPolicy(initial_interval=timedelta(seconds=10), maximum_attempts=3),
}
START = {
    "start_to_close_timeout": timedelta(minutes=30),
    "retry_policy": RetryPolicy(initial_interval=timedelta(seconds=5), maximum_attempts=5),
}
WAIT_READY = {
    "start_to_close_timeout": timedelta(seconds=10),
    "schedule_to_close_timeout": timedelta(minutes=30),
    "retry_policy": RetryPolicy(initial_interval=timedelta(seconds=2), backoff_coefficient=1.0),
}
WAIT_RESULT = {
    "start_to_close_timeout": timedelta(minutes=60),
    "heartbeat_timeout": timedelta(minutes=1),
    "retry_policy": RetryPolicy(maximum_attempts=3),
}


def run_workflow_id(run_id: str) -> str:
    return f"run-{run_id}"


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
class RunWorkflow:
    def __init__(self) -> None:
        self.submission: dict | None = None

    @workflow.signal(name="submit")
    def submit(self, submission: dict) -> None:
        self.submission = submission

    @workflow.query(name="submitted")
    def submitted(self) -> bool:
        return self.submission is not None

    async def _await_submission(self, run_id: str, conversation: str, model: str) -> None:
        for attempt in range(NUDGES + 1):
            await workflow.execute_activity("run_wait", args=[run_id, conversation], **WAIT_RESULT)
            if self.submission is not None or attempt == NUDGES:
                return
            await workflow.execute_activity("run_nudge", args=[run_id, conversation, model], **QUICK)

    async def _converse(self, run_id: str, models: list[str]) -> str | None:
        for model in models:
            if self.submission is not None:
                break
            conversation = None
            try:
                conversation = await workflow.execute_activity("run_start_conversation", args=[run_id, model], **QUICK)
                await self._await_submission(run_id, conversation, model)
            except ActivityError as error:
                if not isinstance(error.cause, ApplicationError) or error.cause.type != MODEL_UNAVAILABLE:
                    raise
                workflow.logger.warning("Model %s unavailable: %s", model, error.cause.message)
                if conversation:
                    await workflow.execute_activity("run_abort", args=[run_id, conversation], **QUICK)
                continue
            return model
        return None

    @workflow.run
    async def run(self, run_id: str) -> dict:
        session_id = None
        interactive = False
        try:
            info: dict = await workflow.execute_activity("run_prepare", run_id, **SLOW)
            session_id, interactive = info["session_id"], info["interactive"]
            await workflow.execute_activity("start_session", session_id, **START)
            await workflow.execute_activity("check_ready", session_id, **WAIT_READY)
            if interactive:
                await workflow.execute_activity("run_start_conversation", args=[run_id, info["models"][0]], **QUICK)
                model = info["models"][0]
            else:
                model = await self._converse(run_id, info["models"])
                if self.submission is None:
                    raise ApplicationError("The agent finished without submitting a result", non_retryable=True)
                await workflow.execute_activity("run_finalize", args=[run_id, self.submission["payload"]], **SLOW)
        except (ActivityError, ApplicationError) as error:
            cause = error.cause if isinstance(error, ActivityError) and error.cause else error
            await workflow.execute_activity("run_fail", args=[run_id, str(getattr(cause, "message", cause))], **QUICK)
            raise
        finally:
            if session_id and not interactive:
                await workflow.execute_activity("stop_session", session_id, **QUICK)
        await workflow.execute_activity("run_complete", run_id, **QUICK)
        return {"session_id": session_id, "model": model}


async def _start_runs(run_ids: list[str]) -> list[str]:
    started = []
    for run_id in run_ids:
        try:
            await workflow.start_child_workflow(
                RunWorkflow.run, run_id, id=run_workflow_id(run_id), parent_close_policy=ParentClosePolicy.ABANDON
            )
            started.append(run_id)
        except WorkflowAlreadyStartedError:
            continue
    return started


@workflow.defn
class PollPullRequestsWorkflow:
    @workflow.run
    async def run(self, team_id: str, workflow_id: str) -> list[str]:
        run_ids: list[str] = await workflow.execute_activity("trigger_new_pull_requests", args=[team_id, workflow_id], **SLOW)
        return await _start_runs(run_ids)


@workflow.defn
class ScheduledRunWorkflow:
    @workflow.run
    async def run(self, team_id: str, workflow_id: str) -> list[str]:
        run_id: str = await workflow.execute_activity("trigger_scheduled_run", args=[team_id, workflow_id], **QUICK)
        return await _start_runs([run_id])


@workflow.defn
class PlatformSyncWorkflow:
    @workflow.run
    async def run(self) -> dict:
        report: dict = await workflow.execute_activity("platform_sync", **SLOW)
        await workflow.execute_activity("reconcile_schedules", **QUICK)
        return report
