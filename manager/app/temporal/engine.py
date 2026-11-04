import asyncio
import logging
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

from temporalio.client import (
    Client,
    Schedule,
    ScheduleActionStartWorkflow,
    ScheduleAlreadyRunningError,
    ScheduleIntervalSpec,
    ScheduleSpec,
    ScheduleUpdate,
)
from temporalio.common import WorkflowIDConflictPolicy
from temporalio.service import RPCError
from temporalio.worker import Worker

from ..config import Settings
from ..sessions import SessionService
from .activities import SessionActivities
from .workflows import ReapSessionsWorkflow, StartSessionWorkflow

log = logging.getLogger(__name__)

REAP_SCHEDULE_ID = "reap-sessions"
CONNECT_ATTEMPTS = 30


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
    def __init__(self, settings: Settings, service: SessionService, client: Client):
        self.settings = settings
        self.service = service
        self.client = client
        self._executor = ThreadPoolExecutor(max_workers=16)
        self.worker = Worker(
            client,
            task_queue=settings.temporal_task_queue,
            workflows=[StartSessionWorkflow, ReapSessionsWorkflow],
            activities=SessionActivities(service).all(),
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

    async def ensure_reap_schedule(self) -> None:
        schedule = Schedule(
            action=ScheduleActionStartWorkflow(
                ReapSessionsWorkflow.run,
                id="reap-sessions",
                task_queue=self.settings.temporal_task_queue,
            ),
            spec=ScheduleSpec(
                intervals=[ScheduleIntervalSpec(every=timedelta(seconds=self.settings.reap_interval_seconds))]
            ),
        )
        try:
            await self.client.create_schedule(REAP_SCHEDULE_ID, schedule)
        except ScheduleAlreadyRunningError:
            await self.client.get_schedule_handle(REAP_SCHEDULE_ID).update(
                lambda _: ScheduleUpdate(schedule=schedule)
            )

    async def run(self) -> None:
        while True:
            try:
                await self.ensure_reap_schedule()
                break
            except RPCError as error:
                log.warning("Reap schedule not created yet (%s), retrying", error.message)
                await asyncio.sleep(5)
        await self.worker.run()

    async def shutdown(self) -> None:
        await self.worker.shutdown()
        self._executor.shutdown(wait=False)
