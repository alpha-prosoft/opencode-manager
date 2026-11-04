import logging
import secrets
import threading
import time
from collections import defaultdict
from datetime import UTC, datetime
from urllib.parse import quote

from . import opencode
from .backends import ContainerBackend
from .config import Settings
from .models import Identity, Session, SessionCreate, SessionRecord, SessionStatus, SessionUpdate, Visibility
from .routing import RouteProvider
from .storage import SharedStorage
from .store import SessionStore

log = logging.getLogger(__name__)

ACTIVE = {SessionStatus.RUNNING, SessionStatus.STARTING}
TOUCH_THROTTLE_SECONDS = 30


class SessionService:
    def __init__(
        self,
        settings: Settings,
        store: SessionStore,
        backend: ContainerBackend,
        routes: RouteProvider,
        storage: SharedStorage,
    ):
        self.settings = settings
        self.store = store
        self.backend = backend
        self.routes = routes
        self.storage = storage
        self._locks: defaultdict[str, threading.Lock] = defaultdict(threading.Lock)
        self._ready: set[str] = set()
        self._last_touch: dict[str, float] = {}

    def session_url(self, record: SessionRecord) -> str:
        return self.settings.public_url(self.settings.session_host(record.id))

    def entry_path(self, record: SessionRecord) -> str:
        return f"/sessions/{quote(record.sub, safe='')}/{record.id}"

    def entry_url(self, record: SessionRecord) -> str:
        return self.settings.public_url(self.settings.domain) + self.entry_path(record)

    @staticmethod
    def can_view(record: SessionRecord, identity: Identity) -> bool:
        return record.sub == identity.sub or record.visibility == Visibility.PUBLIC

    def session_id_from_host(self, host: str) -> str | None:
        hostname = host.split(":", 1)[0].lower()
        suffix = f".{self.settings.domain}"
        if not hostname.endswith(suffix):
            return None
        session_id = hostname.removesuffix(suffix)
        return session_id if session_id.isalnum() else None

    def view(self, record: SessionRecord, identity: Identity, statuses: dict[str, SessionStatus]) -> Session:
        is_owner = record.sub == identity.sub
        data = record.model_dump() | ({} if is_owner else {"env": {}})
        return Session(
            **data,
            secret_key=record.secret_key,
            status=statuses.get(record.id, SessionStatus.MISSING),
            url=self.session_url(record),
            open_url=self.entry_path(record),
            is_owner=is_owner,
        )

    def list(self, identity: Identity) -> list[Session]:
        statuses = self.backend.statuses()
        return [self.view(record, identity, statuses) for record in self.store.list(visible_to=identity.sub)]

    def get(self, session_id: str) -> SessionRecord | None:
        return self.store.get(session_id)

    def describe(self, record: SessionRecord, identity: Identity) -> Session:
        return self.view(record, identity, {record.id: self.backend.status(record.id)})

    def update(self, record: SessionRecord, changes: SessionUpdate) -> SessionRecord:
        fields = changes.model_dump(mode="json", exclude_none=True)
        if fields:
            self.store.update(record.id, fields)
        return self.store.get(record.id)

    def sync_routes(self) -> None:
        self.routes.sync(self.store.list())

    def create(self, identity: Identity, data: SessionCreate) -> SessionRecord:
        now = datetime.now(UTC)
        record = SessionRecord(
            **data.model_dump(),
            id=secrets.token_hex(8),
            sub=identity.sub,
            email=identity.email,
            image=self.settings.session_image,
            secret_key=secrets.token_urlsafe(32),
            created_at=now,
            last_activity_at=now,
        )
        self.storage.prepare(record.id)
        self.backend.provision(record)
        self.store.insert(record)
        self.sync_routes()
        return record

    def is_active(self, session_id: str) -> bool:
        return self.backend.status(session_id) in ACTIVE

    def start(self, record: SessionRecord) -> None:
        with self._locks[record.id]:
            if self.is_active(record.id):
                return
            log.info("Starting session %s", record.id)
            self._ready.discard(record.id)
            self.storage.prepare(record.id)
            self.backend.start(record)
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
        now = time.monotonic()
        if now - self._last_touch.get(session_id, 0) >= TOUCH_THROTTLE_SECONDS:
            self._last_touch[session_id] = now
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
        idle = (datetime.now(UTC) - record.last_activity_at).total_seconds()
        if idle < self.settings.idle_grace_seconds:
            return False
        busy = opencode.count_busy_sessions(self.backend.service_url(record.id), record.secret_key)
        return busy == 0
