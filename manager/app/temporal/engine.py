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
from temporalio.service import RPCStatusCode
from temporalio.common import WorkflowIDConflictPolicy
from temporalio.service import RPCError
from temporalio.worker import Worker

from ..config import Settings
from ..pull_requests import PullRequestSessions
from ..sessions import SessionService
from ..review import PullRequestReviews
from .activities import PullRequestActivities, ReviewActivities, SessionActivities
from .workflows import (
    PollPullRequestsWorkflow,
    PullRequestSessionWorkflow,
    ReapSessionsWorkflow,
    StartSessionWorkflow,
)

log = logging.getLogger(__name__)

REAP_SCHEDULE_ID = "reap-sessions"
BITBUCKET_SCHEDULE_ID = "poll-bitbucket"
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
            workflows=[
                StartSessionWorkflow,
                ReapSessionsWorkflow,
                PollPullRequestsWorkflow,
                PullRequestSessionWorkflow,
            ],
            activities=[
                *SessionActivities(service).all(),
                *PullRequestActivities(settings, PullRequestSessions(settings, service)).all(),
                *ReviewActivities(settings, PullRequestReviews(settings, service)).all(),
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

    async def _upsert_schedule(self, schedule_id: str, workflow_run, every: timedelta) -> None:
        schedule = Schedule(
            action=ScheduleActionStartWorkflow(
                workflow_run, id=schedule_id, task_queue=self.settings.temporal_task_queue
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

    async def ensure_schedules(self) -> None:
        await self._upsert_schedule(
            REAP_SCHEDULE_ID, ReapSessionsWorkflow.run, timedelta(seconds=self.settings.reap_interval_seconds)
        )
        if self.settings.bitbucket_enabled:
            await self._upsert_schedule(
                BITBUCKET_SCHEDULE_ID,
                PollPullRequestsWorkflow.run,
                timedelta(minutes=self.settings.bitbucket_poll_minutes),
            )
        else:
            await self._delete_schedule(BITBUCKET_SCHEDULE_ID)

    async def run(self) -> None:
        while True:
            try:
                await self.ensure_schedules()
                break
            except RPCError as error:
                log.warning("Schedules not created yet (%s), retrying", error.message)
                await asyncio.sleep(5)
        await self.worker.run()

    async def shutdown(self) -> None:
        await self.worker.shutdown()
        self._executor.shutdown(wait=False)
