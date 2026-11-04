from urllib.parse import parse_qsl, quote, urlencode, urlsplit, urlunsplit

from fastapi import APIRouter, Depends, Request, Response, status
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import HTMLResponse, RedirectResponse

from ..auth import HANDOFF_PARAM, handoff_token, read_handoff, resolve_identity, set_auth_cookie
from ..config import Settings, get_settings
from ..models import SessionRecord
from ..opencode import basic_auth
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


class GatewayResponse(Exception):
    def __init__(self, response: Response):
        self.response = response


def starting_page() -> HTMLResponse:
    return HTMLResponse(STARTING_PAGE, status_code=status.HTTP_503_SERVICE_UNAVAILABLE)


def without_handoff(uri: str) -> str:
    parts = urlsplit(uri)
    query = [(key, value) for key, value in parse_qsl(parts.query, keep_blank_values=True) if key != HANDOFF_PARAM]
    return urlunsplit(("", "", parts.path or "/", urlencode(query), parts.fragment))


def authorize_session_request(
    request: Request, session_id: str | None, uri: str, service: SessionService, settings: Settings
) -> SessionRecord:
    record = service.get(session_id) if session_id else None
    if record is None:
        raise GatewayResponse(Response(status_code=status.HTTP_404_NOT_FOUND))

    handoff = dict(parse_qsl(urlsplit(uri).query)).get(HANDOFF_PARAM)
    if handoff:
        identity = read_handoff(handoff, record.id, settings)
        if identity is None or not service.can_view(record, identity):
            raise GatewayResponse(Response(status_code=status.HTTP_401_UNAUTHORIZED))
        target = service.session_url(record) + without_handoff(uri)
        redirect = RedirectResponse(target, status_code=status.HTTP_302_FOUND)
        set_auth_cookie(redirect, identity, settings)
        raise GatewayResponse(redirect)

    identity = resolve_identity(request, settings)
    if identity is None:
        raise GatewayResponse(RedirectResponse(service.entry_url(record), status_code=status.HTTP_302_FOUND))
    if not service.can_view(record, identity):
        raise GatewayResponse(Response(status_code=status.HTTP_404_NOT_FOUND))
    return record


async def ensure_ready(record: SessionRecord, service: SessionService, engine: TemporalEngine) -> bool:
    if service.is_known_ready(record.id):
        return True
    if not await run_in_threadpool(service.is_active, record.id):
        await engine.start_session(record.id)
        return False
    return await run_in_threadpool(service.is_ready, record)


@router.get("/sessions/{sub}/{session_id}")
def enter_session(
    sub: str,
    session_id: str,
    request: Request,
    settings: Settings = Depends(get_settings),
    service: SessionService = Depends(get_service),
) -> Response:
    identity = resolve_identity(request, settings)
    if identity is None:
        login = f"/login?next={quote(request.url.path, safe='')}"
        return RedirectResponse(login, status_code=status.HTTP_303_SEE_OTHER)
    record = service.get(session_id)
    if record is None or record.sub != sub or not service.can_view(record, identity):
        return Response(status_code=status.HTTP_404_NOT_FOUND)
    target = f"{service.session_url(record)}{service.open_path(record)}?{HANDOFF_PARAM}={handoff_token(identity, record.id)}"
    return RedirectResponse(target, status_code=status.HTTP_303_SEE_OTHER)


@router.get(FORWARD_AUTH_PATH)
async def forward_auth(
    request: Request,
    settings: Settings = Depends(get_settings),
    service: SessionService = Depends(get_service),
    engine: TemporalEngine = Depends(get_engine),
) -> Response:
    host = request.headers.get("x-forwarded-host") or request.headers.get("host", "")
    uri = request.headers.get("x-forwarded-uri", "/")
    try:
        record = authorize_session_request(request, service.session_id_from_host(host), uri, service, settings)
    except GatewayResponse as result:
        return result.response
    if not await ensure_ready(record, service, engine):
        return starting_page()

    service.touch(record.id)
    return Response(status_code=status.HTTP_200_OK, headers={"Authorization": basic_auth(record.secret_key)})


async def wake_session(request: Request, session_id: str) -> Response:
    service: SessionService = request.app.state.sessions
    uri = request.url.path + (f"?{request.url.query}" if request.url.query else "")
    try:
        record = authorize_session_request(request, session_id, uri, service, get_settings())
    except GatewayResponse as result:
        return result.response
    await ensure_ready(record, service, request.app.state.engine)
    return starting_page()
