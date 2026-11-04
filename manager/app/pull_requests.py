import json
import logging

from . import bitbucket
from .config import Settings
from .models import Identity, SessionCreate, Visibility
from .review import PullRequestReviews
from .sessions import SessionService

log = logging.getLogger(__name__)

BASELINE_MARKER = 0


class PullRequestSessions:
    def __init__(self, settings: Settings, service: SessionService):
        self.settings = settings
        self.service = service
        self.store = service.store
        self.reviews = PullRequestReviews(settings, service)

    def owner(self) -> Identity:
        sub = self.settings.bitbucket_session_owner
        return Identity(sub=sub, email=f"{sub}@{self.settings.email_domain}", name="Bitbucket")

    def new_pull_requests(self, repo: str) -> list[dict]:
        open_prs = bitbucket.list_open_pull_requests(
            self.settings.bitbucket_token, self.settings.bitbucket_workspace, repo
        )
        seen = self.store.seen_pull_requests(repo)
        if BASELINE_MARKER not in seen:
            self.store.record_pull_request(repo, BASELINE_MARKER, None)
            if not self.settings.bitbucket_backfill:
                for pr in open_prs:
                    self.store.record_pull_request(repo, pr["id"], None)
                log.info("Recorded %d open pull requests in %s as baseline", len(open_prs), repo)
                return []
        return [pr for pr in open_prs if pr["id"] not in seen]

    def ensure_session(self, pr: dict) -> str:
        existing = self.store.pull_request_session(pr["repo"], pr["id"])
        if existing:
            return existing
        full_name = f"{pr['workspace']}/{pr['repo']}"
        data = SessionCreate(
            name=f"{pr['repo']} #{pr['id']}: {pr['title']}"[:100],
            description=f"{pr['author']}: {pr['source_branch']} → {pr['destination_branch']}\n{pr['url']}",
            visibility=Visibility.PUBLIC,
            labels={
                "source": "bitbucket",
                "repository": full_name,
                "pull-request": str(pr["id"]),
                "branch": pr["source_branch"],
                "commit": pr["source_commit"][:12],
            },
        )
        record = self.service.create(self.owner(), data)
        config = self.reviews.opencode_config(record) if self.settings.review_enabled else self.settings.review_opencode_config
        self.store.update(record.id, {"env": {"OPENCODE_CONFIG_CONTENT": json.dumps(config)}})
        self.store.record_pull_request(pr["repo"], pr["id"], record.id)
        log.info("Created session %s for %s#%s", record.id, full_name, pr["id"])
        return record.id
