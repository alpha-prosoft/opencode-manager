from fastapi import APIRouter, Depends, Request, Response, status
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import HTMLResponse, RedirectResponse

from ..auth import resolve_identity
from ..config import Settings, get_settings
from ..models import SessionRecord
from ..routing import FORWARD_AUTH_PATH
from ..sessions import SessionService
from ..temporal.engine import TemporalEngine
from . import get_engine, get_service

router = APIRouter()

STARTING_PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta http-equiv="refresh" content="3">
<title>Starting session</title>
<style>body{font-family:system-ui,sans-serif;display:grid;place-items:center;height:100vh;margin:0}</style>
</head><body><p>Starting your session&hellip; this page refreshes automatically.</p></body></html>"""


def starting_page() -> HTMLResponse:
    return HTMLResponse(STARTING_PAGE, status_code=status.HTTP_503_SERVICE_UNAVAILABLE)


async def ensure_ready(record: SessionRecord, service: SessionService, engine: TemporalEngine) -> bool:
    if service.is_known_ready(record.id):
        return True
    if not await run_in_threadpool(service.is_active, record.id):
        await engine.start_session(record.id)
        return False
    return await run_in_threadpool(service.is_ready, record)


def owned_record(request: Request, session_id: str | None, settings: Settings, service: SessionService):
    identity = resolve_identity(request, settings)
    if identity is None:
        return None, Response(status_code=status.HTTP_401_UNAUTHORIZED)
    record = service.get(session_id) if session_id else None
    if record is None or record.sub != identity.sub:
        return None, Response(status_code=status.HTTP_404_NOT_FOUND)
    return (record, identity), None


@router.get("/sessions/{sub}/{session_id}")
def enter_session(
    sub: str,
    session_id: str,
    request: Request,
    settings: Settings = Depends(get_settings),
    service: SessionService = Depends(get_service),
) -> Response:
    owned, error = owned_record(request, session_id, settings, service)
    if error:
        return error
    record, _ = owned
    if record.sub != sub:
        return Response(status_code=status.HTTP_404_NOT_FOUND)
    return RedirectResponse(service.session_url(record), status_code=status.HTTP_303_SEE_OTHER)


@router.get(FORWARD_AUTH_PATH)
async def forward_auth(
    request: Request,
    settings: Settings = Depends(get_settings),
    service: SessionService = Depends(get_service),
    engine: TemporalEngine = Depends(get_engine),
) -> Response:
    host = request.headers.get("x-forwarded-host") or request.headers.get("host", "")
    owned, error = owned_record(request, service.session_id_from_host(host), settings, service)
    if error:
        return error
    record, identity = owned
    if not await ensure_ready(record, service, engine):
        return starting_page()

    service.touch(record.id)
    return Response(
        status_code=status.HTTP_200_OK,
        headers={"X-Forwarded-Email": record.email, "X-Forwarded-Name": identity.name or record.sub},
    )


async def wake_session(request: Request, session_id: str) -> Response:
    settings = get_settings()
    service: SessionService = request.app.state.sessions
    owned, error = owned_record(request, session_id, settings, service)
    if error:
        return error
    record, _ = owned
    await ensure_ready(record, service, request.app.state.engine)
    return starting_page()
