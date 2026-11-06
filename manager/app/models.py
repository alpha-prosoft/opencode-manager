from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, Field


class SessionStatus(StrEnum):
    RUNNING = "running"
    STARTING = "starting"
    STOPPED = "stopped"
    ERROR = "error"


class Visibility(StrEnum):
    PRIVATE = "private"
    TEAM = "team"
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
    team_id: str | None = None
    run_id: str | None = None
    activity: str | None = None
    secret_key: str = Field(exclude=True)
    created_at: datetime
    last_activity_at: datetime
    stopped_at: datetime | None = None
    locked_by: str | None = None
    locked_at: datetime | None = None
    forked_from: str | None = None


class Session(SessionRecord):
    status: SessionStatus
    url: str
    open_url: str
    is_owner: bool
    can_manage: bool
    can_open: bool
    open_blocked: str | None = None
    personal: bool = False


class Identity(BaseModel):
    sub: str
    email: str
    name: str | None = None


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=64)
