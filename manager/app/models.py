from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, Field


class SessionStatus(StrEnum):
    RUNNING = "running"
    STARTING = "starting"
    STOPPED = "stopped"
    MISSING = "missing"
    ERROR = "error"


class SessionAttributes(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    description: str = ""
    labels: dict[str, str] = {}
    env: dict[str, str] = {}


class SessionCreate(SessionAttributes):
    pass


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


class Identity(BaseModel):
    sub: str
    email: str
    name: str | None = None
