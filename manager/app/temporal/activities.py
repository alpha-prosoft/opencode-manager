import time

from temporalio import activity
from temporalio.exceptions import ApplicationError

from ..config import Settings
from ..pull_requests import PullRequestSessions
from ..review import PullRequestReviews, ReviewQuotaExceeded
from ..review_post import PostPlan, ReviewSubmission

REVIEW_POLL_SECONDS = 10
QUOTA_EXCEEDED = "ReviewQuotaExceeded"
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


class ReviewActivities:
    def __init__(self, settings: Settings, reviews: PullRequestReviews):
        self.settings = settings
        self.reviews = reviews

    @activity.defn(name="review_enabled")
    def review_enabled(self) -> bool:
        return self.settings.review_enabled

    @activity.defn(name="review_models")
    def review_models(self) -> list[str]:
        return self.settings.review_models

    @activity.defn(name="prepare_review_workspace")
    def prepare_review_workspace(self, session_id: str, pull_request: dict) -> None:
        self.reviews.prepare_workspace(session_id, pull_request)

    @activity.defn(name="start_review")
    def start_review(self, session_id: str, pull_request: dict, model: str) -> str:
        return self.reviews.start(session_id, pull_request, model)

    @activity.defn(name="wait_for_review")
    def wait_for_review(self, session_id: str, conversation: str) -> str:
        while True:
            try:
                review = self.reviews.result(session_id, conversation)
            except ReviewQuotaExceeded as error:
                raise ApplicationError(str(error), type=QUOTA_EXCEEDED, non_retryable=True) from error
            if review is not None:
                return review
            activity.heartbeat()
            time.sleep(REVIEW_POLL_SECONDS)

    @activity.defn(name="nudge_review")
    def nudge_review(self, session_id: str, conversation: str, model: str) -> None:
        self.reviews.nudge(session_id, conversation, model)

    @activity.defn(name="abort_review")
    def abort_review(self, session_id: str, conversation: str) -> None:
        self.reviews.abort(session_id, conversation)

    @activity.defn(name="save_review")
    def save_review(self, session_id: str, review: dict) -> None:
        self.reviews.save(session_id, ReviewSubmission.model_validate(review))

    @activity.defn(name="plan_review_post")
    def plan_review_post(self, session_id: str, review: dict) -> dict:
        return self.reviews.plan(session_id, ReviewSubmission.model_validate(review)).model_dump()

    @activity.defn(name="post_review")
    def post_review(self, session_id: str, pull_request: dict, plan: dict) -> int:
        return self.reviews.post(session_id, pull_request, PostPlan.model_validate(plan))

    def all(self) -> list:
        return [
            self.review_enabled,
            self.review_models,
            self.abort_review,
            self.prepare_review_workspace,
            self.start_review,
            self.wait_for_review,
            self.nudge_review,
            self.save_review,
            self.plan_review_post,
            self.post_review,
        ]
