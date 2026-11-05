from fastapi import Request

from ..platform.context import Platform
from ..sessions import SessionService
from ..temporal.engine import TemporalEngine


def get_platform(request: Request) -> Platform:
    return request.app.state.platform


def get_service(request: Request) -> SessionService:
    return request.app.state.sessions


def get_engine(request: Request) -> TemporalEngine:
    return request.app.state.engine
