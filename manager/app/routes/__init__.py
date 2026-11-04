from fastapi import Request

from ..sessions import SessionService
from ..temporal.engine import TemporalEngine


def get_service(request: Request) -> SessionService:
    return request.app.state.sessions


def get_engine(request: Request) -> TemporalEngine:
    return request.app.state.engine
