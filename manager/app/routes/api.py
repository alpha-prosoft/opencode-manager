from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.concurrency import run_in_threadpool

from ..auth import current_identity
from ..models import Identity, Session, SessionCreate, SessionRecord
from ..sessions import SessionService
from ..temporal.engine import TemporalEngine
from . import get_engine, get_service

router = APIRouter(prefix="/api")


def owned_session(
    session_id: str,
    identity: Identity = Depends(current_identity),
    service: SessionService = Depends(get_service),
) -> SessionRecord:
    record = service.get(session_id)
    if record is None or record.sub != identity.sub:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Session not found")
    return record


@router.get("/me")
def me(identity: Identity = Depends(current_identity)) -> Identity:
    return identity


@router.get("/sessions")
def list_sessions(
    identity: Identity = Depends(current_identity),
    service: SessionService = Depends(get_service),
) -> list[Session]:
    return service.list(identity.sub)


@router.post("/sessions", status_code=status.HTTP_201_CREATED)
async def create_session(
    data: SessionCreate,
    identity: Identity = Depends(current_identity),
    service: SessionService = Depends(get_service),
    engine: TemporalEngine = Depends(get_engine),
) -> Session:
    record = await run_in_threadpool(service.create, identity, data)
    await engine.start_session(record.id)
    return await run_in_threadpool(service.describe, record)


@router.get("/sessions/{session_id}")
def get_session(
    record: SessionRecord = Depends(owned_session),
    service: SessionService = Depends(get_service),
) -> Session:
    return service.describe(record)


@router.post("/sessions/{session_id}/start")
async def start_session(
    record: SessionRecord = Depends(owned_session),
    service: SessionService = Depends(get_service),
    engine: TemporalEngine = Depends(get_engine),
) -> Session:
    await engine.start_session(record.id)
    return await run_in_threadpool(service.describe, record)


@router.post("/sessions/{session_id}/stop")
def stop_session(
    record: SessionRecord = Depends(owned_session),
    service: SessionService = Depends(get_service),
) -> Session:
    service.stop(record.id)
    return service.describe(record)


@router.delete("/sessions/{session_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_session(
    record: SessionRecord = Depends(owned_session),
    service: SessionService = Depends(get_service),
) -> None:
    service.delete(record.id)
