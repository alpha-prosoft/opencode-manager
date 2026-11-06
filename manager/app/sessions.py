import logging
import secrets
import threading
import time
from collections import defaultdict
from collections.abc import Callable
from datetime import UTC, datetime
from urllib.parse import quote

from . import opencode
from .backends import ContainerBackend
from .backends.base import WORKSPACE_DIR
from .config import Settings
from .models import Identity, Session, SessionRecord, SessionStatus, SessionUpdate, Visibility
from .routing import RouteProvider
from .storage import SharedStorage
from .store import SessionStore

log = logging.getLogger(__name__)

ACTIVE = {SessionStatus.RUNNING, SessionStatus.STARTING}
CONVERSATION_LABEL = "conversation"
TOUCH_THROTTLE_SECONDS = 30


class SessionService:
    def __init__(
        self,
        settings: Settings,
        store: SessionStore,
        backend: ContainerBackend,
        routes: RouteProvider,
        storage: SharedStorage,
        team_role: Callable[[str, str], str | None] = lambda username, team: None,
        is_admin: Callable[[str], bool] = lambda username: False,
    ):
        self.settings = settings
        self.store = store
        self.backend = backend
        self.routes = routes
        self.storage = storage
        self.team_role = team_role
        self.is_admin = is_admin
        self.personal: Callable[[SessionRecord], bool] = lambda record: False
        self._locks: defaultdict[str, threading.Lock] = defaultdict(threading.Lock)
        self._ready: set[str] = set()
        self._last_touch: dict[str, float] = {}

    def session_url(self, record: SessionRecord) -> str:
        return self.settings.public_url(self.settings.session_host(record.id))

    def open_path(self, record: SessionRecord, conversation: str | None = None) -> str:
        return opencode.web_path(WORKSPACE_DIR, conversation or record.labels.get(CONVERSATION_LABEL))

    def entry_path(self, record: SessionRecord) -> str:
        return f"/sessions/{quote(record.sub, safe='')}/{record.id}"

    def manager_page(self, record: SessionRecord) -> str:
        return self.settings.public_url(self.settings.domain) + f"/sessions/{record.id}"

    def entry_url(self, record: SessionRecord) -> str:
        return self.settings.public_url(self.settings.domain) + self.entry_path(record)

    def _team_role(self, record: SessionRecord, identity: Identity) -> str | None:
        return self.team_role(identity.sub, record.team_id) if record.team_id else None

    def can_view(self, record: SessionRecord, identity: Identity) -> bool:
        """See the session, its activity and audit trail."""
        if record.sub == identity.sub or record.visibility == Visibility.PUBLIC or self.is_admin(identity.sub):
            return True
        role = self._team_role(record, identity)
        return role == "admin" or (role is not None and record.visibility == Visibility.TEAM)

    def can_manage(self, record: SessionRecord, identity: Identity) -> bool:
        """Stop, delete, change sharing."""
        return record.sub == identity.sub or self.is_admin(identity.sub) or self._team_role(record, identity) == "admin"

    def open_blocked(self, record: SessionRecord, identity: Identity) -> str | None:
        """Why this person may not work in the live session, or None when they may."""
        if record.locked_at:
            return "locked"
        if record.sub == identity.sub:
            return None
        if self.personal(record):
            return "personal"
        if record.visibility == Visibility.PRIVATE:
            return "private"
        return None if self.can_view(record, identity) else "hidden"

    def can_open(self, record: SessionRecord, identity: Identity) -> bool:
        return self.open_blocked(record, identity) is None

    def session_id_from_host(self, host: str) -> str | None:
        hostname = host.split(":", 1)[0].lower()
        suffix = f".{self.settings.domain}"
        if not hostname.endswith(suffix):
            return None
        session_id = hostname.removesuffix(suffix)
        return session_id if session_id.isalnum() else None

    def view(self, record: SessionRecord, identity: Identity, statuses: dict[str, SessionStatus]) -> Session:
        blocked = self.open_blocked(record, identity)
        return Session(
            can_open=blocked is None,
            open_blocked=blocked,
            personal=self.personal(record),
            **record.model_dump(),
            secret_key=record.secret_key,
            status=statuses.get(record.id, SessionStatus.STOPPED),
            url=self.session_url(record),
            open_url=self.entry_path(record),
            is_owner=record.sub == identity.sub,
            can_manage=self.can_manage(record, identity),
        )

    def list(self, identity: Identity) -> list[Session]:
        statuses = self.backend.statuses()
        return [self.view(r, identity, statuses) for r in self.store.list() if self.can_view(r, identity)]

    def get(self, session_id: str) -> SessionRecord | None:
        return self.store.get(session_id)

    def describe(self, record: SessionRecord, identity: Identity) -> Session:
        return self.view(record, identity, {record.id: self.backend.status(record.id)})

    def update(self, record: SessionRecord, changes: SessionUpdate) -> SessionRecord:
        fields = changes.model_dump(mode="json", exclude_none=True)
        if fields:
            self.store.update(record.id, fields)
        return self.store.get(record.id)

    def label(self, session_id: str, key: str, value: str) -> None:
        record = self.store.get(session_id)
        self.store.update(session_id, {"labels": {**record.labels, key: value}})

    def sync_routes(self) -> None:
        self.routes.sync(self.store.list())

    def create(
        self,
        owner: Identity,
        name: str,
        description: str,
        visibility: Visibility,
        team_id: str,
        run_id: str,
        activity: str,
        labels: dict[str, str],
    ) -> SessionRecord:
        stamp = datetime.now(UTC)
        record = SessionRecord(
            id=secrets.token_hex(8),
            sub=owner.sub,
            email=owner.email,
            name=name[:100] or activity,
            description=description,
            visibility=visibility,
            labels=labels,
            env={},
            image="",
            team_id=team_id,
            run_id=run_id,
            activity=activity,
            secret_key=secrets.token_urlsafe(32),
            created_at=stamp,
            last_activity_at=stamp,
        )
        self.storage.prepare(record.id)
        self.backend.provision(record)
        self.store.insert(record)
        self.sync_routes()
        return record

    def is_active(self, session_id: str) -> bool:
        return self.backend.status(session_id) in ACTIVE

    def lock(self, record: SessionRecord, identity: Identity) -> None:
        if self.is_active(record.id):
            self.stop(record.id)
        self.store.update(record.id, {"locked_by": identity.sub, "locked_at": datetime.now(UTC).isoformat()})

    def start(self, record: SessionRecord) -> None:
        if record.locked_at:
            raise RuntimeError(f"Session {record.id} is locked")
        sandbox = self.storage.load_sandbox(record.id)
        if sandbox is None:
            raise RuntimeError(f"Session {record.id} has no sandbox configuration")
        with self._locks[record.id]:
            if self.is_active(record.id):
                return
            log.info("Starting session %s", record.id)
            self._ready.discard(record.id)
            self.backend.start(record, sandbox)
            self.store.touch(record.id)

    def is_known_ready(self, session_id: str) -> bool:
        return session_id in self._ready

    def is_ready(self, record: SessionRecord) -> bool:
        if self.is_known_ready(record.id):
            return True
        if opencode.is_healthy(self.backend.service_url(record.id), record.secret_key):
            self._ready.add(record.id)
            return True
        return False

    def touch(self, session_id: str) -> None:
        moment = time.monotonic()
        if moment - self._last_touch.get(session_id, 0) >= TOUCH_THROTTLE_SECONDS:
            self._last_touch[session_id] = moment
            self.store.touch(session_id)

    def stop(self, session_id: str) -> None:
        with self._locks[session_id]:
            log.info("Stopping session %s", session_id)
            self._ready.discard(session_id)
            self.backend.stop(session_id)
            self.store.mark_stopped(session_id)

    def delete(self, session_id: str) -> None:
        with self._locks[session_id]:
            self._ready.discard(session_id)
            self.backend.delete(session_id)
            self.storage.remove(session_id)
            self.store.delete(session_id)
        self._locks.pop(session_id, None)
        self._last_touch.pop(session_id, None)
        self.sync_routes()

    def running_session_ids(self) -> list[str]:
        statuses = self.backend.statuses()
        return [r.id for r in self.store.list() if statuses.get(r.id) == SessionStatus.RUNNING]

    def is_done(self, record: SessionRecord) -> bool:
        sandbox = self.storage.load_sandbox(record.id)
        grace = (sandbox.idle_minutes * 60) if sandbox else self.settings.idle_grace_seconds
        idle = (datetime.now(UTC) - record.last_activity_at).total_seconds()
        if idle < grace:
            return False
        return opencode.count_busy_sessions(self.backend.service_url(record.id), record.secret_key) == 0
