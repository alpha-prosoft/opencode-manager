import asyncio
import logging
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

from temporalio import activity
from temporalio.client import (
    Client,
    WorkflowExecutionStatus,
    Schedule,
    ScheduleActionStartWorkflow,
    ScheduleAlreadyRunningError,
    ScheduleIntervalSpec,
    ScheduleSpec,
    ScheduleUpdate,
)
from temporalio.common import WorkflowIDConflictPolicy
from temporalio.service import RPCError, RPCStatusCode
from temporalio.worker import Worker

from ..config import Settings
from ..platform.registry import PlatformRegistry
from ..platform.runner import RunService
from ..platform.triggers import TriggerService
from ..sessions import SessionService
from .activities import PlatformActivities, RunActivities, SessionActivities
from .workflows import (
    PlatformSyncWorkflow,
    PollPullRequestsWorkflow,
    ReapSessionsWorkflow,
    RunWorkflow,
    ScheduledRunWorkflow,
    StartSessionWorkflow,
    run_workflow_id,
)

log = logging.getLogger(__name__)

REAP_SCHEDULE_ID = "reap-sessions"
SYNC_SCHEDULE_ID = "platform-sync"
TRIGGER_PREFIX = "trigger-"
LEGACY_SCHEDULES = ("poll-bitbucket",)
RECONCILE_SECONDS = 120
CONNECT_ATTEMPTS = 30
TRIGGER_WORKFLOWS = {"pull_request": PollPullRequestsWorkflow, "schedule": ScheduledRunWorkflow}


async def connect(settings: Settings) -> Client:
    for attempt in range(1, CONNECT_ATTEMPTS + 1):
        try:
            return await Client.connect(settings.temporal_address, namespace=settings.temporal_namespace)
        except RuntimeError as error:
            if attempt == CONNECT_ATTEMPTS:
                raise
            log.info("Temporal not reachable yet (%s), retrying", error)
            await asyncio.sleep(2)
    raise AssertionError("unreachable")


class TemporalEngine:
    def __init__(
        self,
        settings: Settings,
        sessions: SessionService,
        registry: PlatformRegistry,
        runner: RunService,
        triggers: TriggerService,
        client: Client,
    ):
        self.settings = settings
        self.sessions = sessions
        self.runner = runner
        self.triggers = triggers
        self.client = client
        self._executor = ThreadPoolExecutor(max_workers=32)
        self.worker = Worker(
            client,
            task_queue=settings.temporal_task_queue,
            workflows=[
                StartSessionWorkflow,
                ReapSessionsWorkflow,
                RunWorkflow,
                PollPullRequestsWorkflow,
                ScheduledRunWorkflow,
                PlatformSyncWorkflow,
            ],
            activities=[
                *SessionActivities(sessions).all(),
                *RunActivities(runner).all(),
                *PlatformActivities(registry, triggers).all(),
                self.reconcile_schedules,
            ],
            activity_executor=self._executor,
        )

    async def start_session(self, session_id: str) -> None:
        await self.client.start_workflow(
            StartSessionWorkflow.run,
            session_id,
            id=f"start-session-{session_id}",
            task_queue=self.settings.temporal_task_queue,
            id_conflict_policy=WorkflowIDConflictPolicy.USE_EXISTING,
        )

    async def start_run(self, run_id: str) -> None:
        await self.client.start_workflow(
            RunWorkflow.run, run_id, id=run_workflow_id(run_id), task_queue=self.settings.temporal_task_queue
        )

    async def cancel_run(self, run_id: str) -> None:
        try:
            await self.client.get_workflow_handle(run_workflow_id(run_id)).cancel()
        except RPCError as error:
            if error.status != RPCStatusCode.NOT_FOUND:
                raise

    async def reconcile_runs(self) -> list[str]:
        """Fail runs whose workflow ended without reporting (terminated, timed out, lost)."""
        repaired = []
        for run in await asyncio.to_thread(self.runner.runs.active):
            try:
                description = await self.client.get_workflow_handle(run_workflow_id(run["id"])).describe()
            except RPCError as error:
                if error.status != RPCStatusCode.NOT_FOUND:
                    continue
                state = "not found"
            else:
                if description.status == WorkflowExecutionStatus.RUNNING:
                    continue
                state = description.status.name.lower()
            await asyncio.to_thread(self.runner.fail, run["id"], f"Run workflow ended unexpectedly ({state})")
            repaired.append(run["id"])
        return repaired

    async def _reconcile_forever(self) -> None:
        while True:
            try:
                repaired = await self.reconcile_runs()
                if repaired:
                    log.warning("Marked %d orphaned run(s) as failed: %s", len(repaired), repaired)
            except Exception:
                log.exception("Run reconciliation failed")
            await asyncio.sleep(RECONCILE_SECONDS)

    async def sync_now(self) -> dict:
        return await self.client.execute_workflow(
            PlatformSyncWorkflow.run,
            id="platform-sync-manual",
            task_queue=self.settings.temporal_task_queue,
            id_conflict_policy=WorkflowIDConflictPolicy.USE_EXISTING,
        )

    async def _upsert_schedule(self, schedule_id: str, workflow_run, every: timedelta, args: list | None = None) -> None:
        schedule = Schedule(
            action=ScheduleActionStartWorkflow(
                workflow_run, args=args or [], id=schedule_id, task_queue=self.settings.temporal_task_queue
            ),
            spec=ScheduleSpec(intervals=[ScheduleIntervalSpec(every=every)]),
        )
        try:
            await self.client.create_schedule(schedule_id, schedule)
        except ScheduleAlreadyRunningError:
            await self.client.get_schedule_handle(schedule_id).update(lambda _: ScheduleUpdate(schedule=schedule))

    async def _delete_schedule(self, schedule_id: str) -> None:
        try:
            await self.client.get_schedule_handle(schedule_id).delete()
        except RPCError as error:
            if error.status != RPCStatusCode.NOT_FOUND:
                raise

    @activity.defn(name="reconcile_schedules")
    async def reconcile_schedules(self) -> dict:
        desired = await asyncio.to_thread(self.triggers.desired_schedules)
        existing = set()
        async for entry in await self.client.list_schedules():
            if entry.id.startswith(TRIGGER_PREFIX):
                existing.add(entry.id)
        for schedule_id in existing - desired.keys():
            await self._delete_schedule(schedule_id)
        for schedule_id, item in desired.items():
            await self._upsert_schedule(
                schedule_id, TRIGGER_WORKFLOWS[item["kind"]].run, timedelta(minutes=item["minutes"]), [item["team"], item["workflow"]]
            )
        return {"schedules": sorted(desired)}

    async def ensure_schedules(self) -> None:
        for legacy in LEGACY_SCHEDULES:
            await self._delete_schedule(legacy)
        await self._upsert_schedule(
            REAP_SCHEDULE_ID, ReapSessionsWorkflow.run, timedelta(seconds=self.settings.reap_interval_seconds)
        )
        if self.settings.onboarding_url:
            await self._upsert_schedule(
                SYNC_SCHEDULE_ID, PlatformSyncWorkflow.run, timedelta(minutes=self.settings.sync_interval_minutes)
            )
        else:
            await self._delete_schedule(SYNC_SCHEDULE_ID)

    async def run(self) -> None:
        while True:
            try:
                await self.ensure_schedules()
                break
            except RPCError as error:
                log.warning("Schedules not created yet (%s), retrying", error.message)
                await asyncio.sleep(5)
        reconciler = asyncio.create_task(self._reconcile_forever(), name="run-reconciler")
        try:
            await self.worker.run()
        finally:
            reconciler.cancel()

    async def shutdown(self) -> None:
        await self.worker.shutdown()
        self._executor.shutdown(wait=False)
