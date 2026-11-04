import asyncio
from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy

QUICK = {"start_to_close_timeout": timedelta(minutes=1)}
START = {
    "start_to_close_timeout": timedelta(minutes=30),
    "retry_policy": RetryPolicy(initial_interval=timedelta(seconds=5), maximum_attempts=5),
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
