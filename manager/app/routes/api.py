from fastapi import APIRouter, Depends, HTTPException, Response, status
from fastapi.concurrency import run_in_threadpool

from ..auth import clear_auth_cookie, current_identity, login_identity, set_auth_cookie
from ..config import Settings, get_settings
from ..models import Identity, LoginRequest, Session, SessionCreate, SessionRecord, SessionUpdate
from ..sessions import SessionService
from ..temporal.engine import TemporalEngine
from . import get_engine, get_service

router = APIRouter(prefix="/api")


def visible_session(
    session_id: str,
    identity: Identity = Depends(current_identity),
    service: SessionService = Depends(get_service),
) -> SessionRecord:
    record = service.get(session_id)
    if record is None or not service.can_view(record, identity):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Session not found")
    return record


def owned_session(
    record: SessionRecord = Depends(visible_session),
    identity: Identity = Depends(current_identity),
) -> SessionRecord:
    if record.sub != identity.sub:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the owner can change this session")
    return record


@router.post("/auth/login")
def login(data: LoginRequest, response: Response, settings: Settings = Depends(get_settings)) -> Identity:
    if settings.auth_mode != "login":
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Login is handled by the identity provider")
    identity = login_identity(data.username, settings)
    set_auth_cookie(response, identity, settings)
    return identity


@router.post("/auth/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(response: Response) -> None:
    clear_auth_cookie(response)


@router.get("/me")
def me(identity: Identity = Depends(current_identity)) -> Identity:
    return identity


@router.get("/sessions")
def list_sessions(
    identity: Identity = Depends(current_identity),
    service: SessionService = Depends(get_service),
) -> list[Session]:
    return service.list(identity)


@router.post("/sessions", status_code=status.HTTP_201_CREATED)
async def create_session(
    data: SessionCreate,
    identity: Identity = Depends(current_identity),
    service: SessionService = Depends(get_service),
    engine: TemporalEngine = Depends(get_engine),
) -> Session:
    record = await run_in_threadpool(service.create, identity, data)
    await engine.start_session(record.id)
    return await run_in_threadpool(service.describe, record, identity)


@router.get("/sessions/{session_id}")
def get_session(
    record: SessionRecord = Depends(visible_session),
    identity: Identity = Depends(current_identity),
    service: SessionService = Depends(get_service),
) -> Session:
    return service.describe(record, identity)


@router.patch("/sessions/{session_id}")
def update_session(
    changes: SessionUpdate,
    record: SessionRecord = Depends(owned_session),
    identity: Identity = Depends(current_identity),
    service: SessionService = Depends(get_service),
) -> Session:
    return service.describe(service.update(record, changes), identity)


@router.post("/sessions/{session_id}/start")
async def start_session(
    record: SessionRecord = Depends(visible_session),
    identity: Identity = Depends(current_identity),
    service: SessionService = Depends(get_service),
    engine: TemporalEngine = Depends(get_engine),
) -> Session:
    await engine.start_session(record.id)
    return await run_in_threadpool(service.describe, record, identity)


@router.post("/sessions/{session_id}/stop")
def stop_session(
    record: SessionRecord = Depends(owned_session),
    identity: Identity = Depends(current_identity),
    service: SessionService = Depends(get_service),
) -> Session:
    service.stop(record.id)
    return service.describe(record, identity)


@router.delete("/sessions/{session_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_session(
    record: SessionRecord = Depends(owned_session),
    service: SessionService = Depends(get_service),
) -> None:
    service.delete(record.id)
