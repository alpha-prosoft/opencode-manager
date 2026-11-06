import asyncio
import logging
from contextlib import asynccontextmanager, suppress
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse

from .auth import signing_key
from .backends import create_backend
from .config import get_settings
from .db import Database
from .platform import tools
from .platform.audit import AuditCollector, AuditStore
from .platform.context import Platform
from .platform.gateway import router as llm_router
from .platform.registry import PlatformRegistry
from .platform.runner import RunService
from .platform.runs import RunStore
from .platform.credentials import Credentials, CredentialVault
from .platform.secrets import SecretStore
from .platform.telemetry import TelemetryStore
from .platform.telemetry import router as telemetry_router
from .platform.triggers import TriggerService
from .platform.usage import UsageStore
from .platform import mcp_gateway
from .routes import admin, api, credentials, gateway
from .routing import FORWARD_AUTH_PATH, create_route_provider
from .sessions import SessionService
from .storage import SharedStorage
from .store import SessionStore
from .temporal.engine import TemporalEngine, connect

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
log = logging.getLogger(__name__)


def log_failure(task: asyncio.Task) -> None:
    if not task.cancelled() and task.exception():
        log.error("Background task %s failed", task.get_name(), exc_info=task.exception())


async def prepare_backend(sessions: SessionService) -> None:
    try:
        await run_in_threadpool(sessions.backend.prepare)
    except Exception:
        log.exception("Backend preparation failed")


async def initial_sync(platform: Platform) -> None:
    if platform.registry.configured:
        try:
            await platform.engine.sync_now()
        except Exception:
            log.exception("Initial platform sync failed")


def build_platform() -> Platform:
    settings = get_settings()
    db = Database(settings.database_path)
    secrets = SecretStore(settings)
    secrets.vault = CredentialVault(settings, db)
    registry = PlatformRegistry(settings, db, secrets)
    credentials = Credentials(secrets.vault, secrets, registry.platform)
    backend = create_backend(settings)
    sessions = SessionService(
        settings,
        SessionStore(db),
        backend,
        create_route_provider(settings, backend),
        SharedStorage(settings.shared_mount_path),
        team_role=registry.role,
        is_admin=registry.is_platform_admin,
    )
    sessions.personal = lambda record: credentials.uses_personal(record.sub, sessions.storage.mcp_servers(record.id))
    runs = RunStore(db)
    key = signing_key()
    runner = RunService(settings, registry, sessions, runs, secrets, key, credentials)
    audit = AuditStore(db)
    return Platform(
        settings=settings,
        signing_key=key,
        secrets=secrets,
        credentials=credentials,
        registry=registry,
        sessions=sessions,
        runs=runs,
        runner=runner,
        triggers=TriggerService(runner),
        usage=UsageStore(db),
        audit=audit,
        collector=AuditCollector(audit, sessions),
        telemetry=TelemetryStore(db),
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    platform = build_platform()
    await run_in_threadpool(platform.sessions.sync_routes)
    platform.engine = TemporalEngine(
        platform.settings, platform.sessions, platform.registry, platform.runner, platform.triggers, await connect(platform.settings)
    )
    app.state.platform = platform
    app.state.sessions = platform.sessions
    app.state.engine = platform.engine
    tools.state.platform = platform

    tasks = [
        asyncio.create_task(prepare_backend(platform.sessions), name="prepare-backend"),
        asyncio.create_task(platform.engine.run(), name="temporal-engine"),
        asyncio.create_task(platform.collector.run(), name="audit-collector"),
        asyncio.create_task(initial_sync(platform), name="initial-sync"),
    ]
    for task in tasks:
        task.add_done_callback(log_failure)
    async with tools.server.session_manager.run():
        yield
    await platform.engine.shutdown()
    for task in tasks:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task


app = FastAPI(title="OpenCode Agent Platform", lifespan=lifespan, docs_url="/api/docs", openapi_url="/api/openapi.json")


@app.middleware("http")
async def wake_session_hosts(request: Request, call_next):
    session_id = request.app.state.sessions.session_id_from_host(request.headers.get("host", ""))
    if session_id is None or request.url.path == FORWARD_AUTH_PATH:
        return await call_next(request)
    return await gateway.wake_session(request, session_id)


app.include_router(api.router)
app.include_router(api.metrics_router)
app.include_router(admin.router)
app.include_router(credentials.router)
app.include_router(mcp_gateway.router)
app.include_router(gateway.router)
app.include_router(llm_router)
app.include_router(telemetry_router)
app.mount("/mcp", tools.mcp_app())


@app.get("/healthz", include_in_schema=False)
def healthz() -> dict:
    return {"status": "ok"}


ui_dist = Path(get_settings().ui_dist_dir).resolve()
if ui_dist.is_dir():

    @app.get("/{path:path}", include_in_schema=False)
    def ui(path: str) -> FileResponse:
        candidate = (ui_dist / path).resolve()
        if path and candidate.is_file() and candidate.is_relative_to(ui_dist):
            return FileResponse(candidate)
        return FileResponse(ui_dist / "index.html")
