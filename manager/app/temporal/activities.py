import time

from temporalio import activity
from temporalio.exceptions import ApplicationError

from ..platform.handlers import HandlerError
from ..platform.registry import PlatformRegistry
from ..platform.runner import ModelUnavailable, RunError, RunService
from ..platform.triggers import TriggerService
from ..sessions import SessionService
from .workflows import MODEL_UNAVAILABLE

POLL_SECONDS = 10


class SessionActivities:
    def __init__(self, service: SessionService):
        self.service = service

    def _record(self, session_id: str):
        record = self.service.get(session_id)
        if record is None:
            raise ApplicationError(f"Session {session_id} not found", non_retryable=True)
        return record

    @activity.defn(name="start_session")
    def start_session(self, session_id: str) -> None:
        self.service.start(self._record(session_id))

    @activity.defn(name="check_ready")
    def check_ready(self, session_id: str) -> None:
        if not self.service.is_ready(self._record(session_id)):
            raise ApplicationError(f"Session {session_id} is not ready yet")

    @activity.defn(name="stop_session")
    def stop_session(self, session_id: str) -> None:
        if self.service.get(session_id):
            self.service.stop(session_id)

    @activity.defn(name="list_running_sessions")
    def list_running_sessions(self) -> list[str]:
        return self.service.running_session_ids()

    @activity.defn(name="stop_session_if_done")
    def stop_session_if_done(self, session_id: str) -> bool:
        record = self.service.get(session_id)
        if record is None or not self.service.is_done(record):
            return False
        self.service.stop(session_id)
        return True

    def all(self) -> list:
        return [self.start_session, self.check_ready, self.stop_session, self.list_running_sessions, self.stop_session_if_done]


class RunActivities:
    def __init__(self, runner: RunService):
        self.runner = runner

    @activity.defn(name="run_prepare")
    def run_prepare(self, run_id: str) -> dict:
        try:
            return self.runner.prepare(run_id)
        except (RunError, HandlerError) as error:
            raise ApplicationError(str(error), non_retryable=True) from error

    @activity.defn(name="run_start_conversation")
    def run_start_conversation(self, run_id: str, model: str) -> str | None:
        return self.runner.start_conversation(run_id, model)

    @activity.defn(name="run_wait")
    def run_wait(self, run_id: str, conversation: str) -> None:
        while True:
            try:
                if self.runner.conversation_finished(run_id, conversation):
                    return
            except ModelUnavailable as error:
                raise ApplicationError(str(error), type=MODEL_UNAVAILABLE, non_retryable=True) from error
            activity.heartbeat()
            time.sleep(POLL_SECONDS)

    @activity.defn(name="run_nudge")
    def run_nudge(self, run_id: str, conversation: str, model: str) -> None:
        self.runner.nudge(run_id, conversation, model)

    @activity.defn(name="run_abort")
    def run_abort(self, run_id: str, conversation: str) -> None:
        self.runner.abort(run_id, conversation)

    @activity.defn(name="run_finalize")
    def run_finalize(self, run_id: str, submission: dict) -> dict:
        return self.runner.finalize(run_id, submission)

    @activity.defn(name="run_complete")
    def run_complete(self, run_id: str) -> None:
        self.runner.complete(run_id)

    @activity.defn(name="run_ci_start")
    def run_ci_start(self, run_id: str) -> int:
        return self.runner.ci_start(run_id)

    @activity.defn(name="run_ci_poll")
    def run_ci_poll(self, run_id: str) -> str:
        return self.runner.ci_poll(run_id)

    @activity.defn(name="run_ci_timeout")
    def run_ci_timeout(self, run_id: str) -> None:
        self.runner.ci_timeout(run_id)

    @activity.defn(name="run_ci_failure")
    def run_ci_failure(self, run_id: str) -> str | None:
        try:
            return self.runner.ci_failure(run_id)
        except RunError as error:
            raise ApplicationError(str(error), non_retryable=True) from error

    @activity.defn(name="run_fail")
    def run_fail(self, run_id: str, message: str) -> None:
        self.runner.fail(run_id, message)

    def all(self) -> list:
        return [
            self.run_ci_start,
            self.run_ci_poll,
            self.run_ci_timeout,
            self.run_ci_failure,
            self.run_prepare,
            self.run_start_conversation,
            self.run_wait,
            self.run_nudge,
            self.run_abort,
            self.run_finalize,
            self.run_complete,
            self.run_fail,
        ]


class PlatformActivities:
    def __init__(self, registry: PlatformRegistry, triggers: TriggerService):
        self.registry = registry
        self.triggers = triggers

    @activity.defn(name="platform_sync")
    def platform_sync(self) -> dict:
        return self.registry.sync()

    @activity.defn(name="trigger_new_pull_requests")
    def trigger_new_pull_requests(self, team_id: str, workflow_id: str) -> list[str]:
        return self.triggers.new_pull_requests(team_id, workflow_id)

    @activity.defn(name="trigger_scheduled_run")
    def trigger_scheduled_run(self, team_id: str, workflow_id: str) -> str:
        return self.triggers.scheduled_run(team_id, workflow_id)

    def all(self) -> list:
        return [self.platform_sync, self.trigger_new_pull_requests, self.trigger_scheduled_run]
