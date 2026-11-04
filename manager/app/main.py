import asyncio
import logging
from contextlib import asynccontextmanager, suppress
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse

from .backends import create_backend
from .config import get_settings
from . import review_tool
from .routes import api, gateway
from .routing import FORWARD_AUTH_PATH, create_route_provider
from .sessions import SessionService
from .storage import SharedStorage
from .store import SessionStore
from .temporal.engine import TemporalEngine, connect

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger(__name__)


def log_failure(task: asyncio.Task) -> None:
    if not task.cancelled() and task.exception():
        log.error("Background task %s failed", task.get_name(), exc_info=task.exception())


async def prepare_backend(service: SessionService) -> None:
    try:
        await run_in_threadpool(service.backend.prepare)
    except Exception:
        log.exception("Backend preparation failed")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    backend = create_backend(settings)
    service = SessionService(
        settings,
        SessionStore(settings.database_path),
        backend,
        create_route_provider(settings, backend),
        SharedStorage(settings.shared_mount_path),
    )
    await run_in_threadpool(service.sync_routes)
    engine = TemporalEngine(settings, service, await connect(settings))
    app.state.sessions = service
    app.state.engine = engine
    review_tool.state.service = service
    review_tool.state.engine = engine

    tasks = [
        asyncio.create_task(prepare_backend(service), name="prepare-backend"),
        asyncio.create_task(engine.run(), name="temporal-engine"),
    ]
    for task in tasks:
        task.add_done_callback(log_failure)
    async with review_tool.server.session_manager.run():
        yield
    await engine.shutdown()
    for task in tasks:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task


app = FastAPI(
    title="OpenCode Session Manager",
    lifespan=lifespan,
    docs_url="/api/docs",
    openapi_url="/api/openapi.json",
)


@app.middleware("http")
async def wake_session_hosts(request: Request, call_next):
    session_id = request.app.state.sessions.session_id_from_host(request.headers.get("host", ""))
    if session_id is None or request.url.path == FORWARD_AUTH_PATH:
        return await call_next(request)
    return await gateway.wake_session(request, session_id)


app.include_router(api.router)
app.include_router(gateway.router)
app.mount("/mcp", review_tool.mcp_app())


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
