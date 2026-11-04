from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, Field


class SessionStatus(StrEnum):
    RUNNING = "running"
    STARTING = "starting"
    STOPPED = "stopped"
    MISSING = "missing"
    ERROR = "error"


class Visibility(StrEnum):
    PRIVATE = "private"
    PUBLIC = "public"


class SessionAttributes(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    description: str = ""
    visibility: Visibility = Visibility.PRIVATE
    labels: dict[str, str] = {}
    env: dict[str, str] = {}


class SessionCreate(SessionAttributes):
    pass


class SessionUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=100)
    description: str | None = None
    visibility: Visibility | None = None
    labels: dict[str, str] | None = None


class SessionRecord(SessionAttributes):
    id: str
    sub: str
    email: str
    image: str
    secret_key: str = Field(exclude=True)
    created_at: datetime
    last_activity_at: datetime
    stopped_at: datetime | None = None


class Session(SessionRecord):
    status: SessionStatus
    url: str
    open_url: str
    is_owner: bool


class Identity(BaseModel):
    sub: str
    email: str
    name: str | None = None


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=64)
