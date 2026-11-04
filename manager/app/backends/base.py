from abc import ABC, abstractmethod

from ..config import Settings
from ..models import SessionRecord, SessionStatus
from ..storage import session_subpath

SESSION_DIR = "/session"
HOME_DIR = f"{SESSION_DIR}/home"
WORKSPACE_DIR = f"{SESSION_DIR}/workspace"
LABEL_SESSION = "opencode-manager/session"
LABEL_SUB = "opencode-manager/sub"


def resource_name(session_id: str) -> str:
    return f"opencode-{session_id}"


class ContainerBackend(ABC):
    def __init__(self, settings: Settings):
        self.settings = settings

    def instance_env(self, record: SessionRecord) -> dict[str, str]:
        return {
            **self.settings.session_env,
            **record.env,
            "HOME": HOME_DIR,
            "OPENCODE_SERVER_PASSWORD": record.secret_key,
        }

    def command(self) -> list[str]:
        return ["web", "--hostname", "0.0.0.0", "--port", str(self.settings.session_port)]

    def mount(self, session_id: str) -> tuple[str, str]:
        return SESSION_DIR, session_subpath(session_id)

    def prepare(self) -> None:
        pass

    def provision(self, record: SessionRecord) -> None:
        pass

    @abstractmethod
    def start(self, record: SessionRecord) -> None: ...

    @abstractmethod
    def stop(self, session_id: str) -> None: ...

    @abstractmethod
    def delete(self, session_id: str) -> None: ...

    @abstractmethod
    def statuses(self) -> dict[str, SessionStatus]: ...

    @abstractmethod
    def service_url(self, session_id: str) -> str: ...

    def status(self, session_id: str) -> SessionStatus:
        return self.statuses().get(session_id, SessionStatus.STOPPED)
