from temporalio import activity
from temporalio.exceptions import ApplicationError

from ..config import Settings
from ..pull_requests import PullRequestSessions
from ..sessions import SessionService


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
        return [
            self.start_session,
            self.check_ready,
            self.list_running_sessions,
            self.stop_session_if_done,
        ]


class PullRequestActivities:
    def __init__(self, settings: Settings, pull_requests: PullRequestSessions):
        self.settings = settings
        self.pull_requests = pull_requests

    @activity.defn(name="bitbucket_repositories")
    def bitbucket_repositories(self) -> list[str]:
        return self.settings.bitbucket_repos

    @activity.defn(name="list_new_pull_requests")
    def list_new_pull_requests(self, repo: str) -> list[dict]:
        return self.pull_requests.new_pull_requests(repo)

    @activity.defn(name="ensure_pull_request_session")
    def ensure_pull_request_session(self, pull_request: dict) -> str:
        return self.pull_requests.ensure_session(pull_request)

    def all(self) -> list:
        return [self.bitbucket_repositories, self.list_new_pull_requests, self.ensure_pull_request_session]
