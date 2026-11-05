from abc import ABC, abstractmethod

from ..config import Settings
from ..models import SessionRecord, SessionStatus
from ..platform.harness import CONFIG_MOUNT, Sandbox

SESSION_DIR = "/session"
HOME_DIR = f"{SESSION_DIR}/home"
WORKSPACE_DIR = f"{SESSION_DIR}/workspace"
SESSION_UID = 1000
LABEL_SESSION = "opencode-manager/session"
LABEL_SUB = "opencode-manager/sub"
LABEL_TEAM = "opencode-manager/team"
LABEL_NETWORK = "opencode-manager/network"


def resource_name(session_id: str) -> str:
    return f"opencode-{session_id}"


def session_subpath(session_id: str) -> str:
    return f"sessions/{session_id}"


def config_subpath(session_id: str) -> str:
    return f"configs/{session_id}/mount"


class ContainerBackend(ABC):
    def __init__(self, settings: Settings):
        self.settings = settings

    def command(self) -> list[str]:
        return ["web", "--hostname", "0.0.0.0", "--port", str(self.settings.session_port)]

    def environment(self, sandbox: Sandbox) -> dict[str, str]:
        return {**sandbox.env, "HOME": HOME_DIR}

    def mounts(self, session_id: str) -> list[tuple[str, str, bool]]:
        return [(SESSION_DIR, session_subpath(session_id), False), (CONFIG_MOUNT, config_subpath(session_id), True)]

    def prepare(self) -> None:
        pass

    def provision(self, record: SessionRecord) -> None:
        pass

    @abstractmethod
    def start(self, record: SessionRecord, sandbox: Sandbox) -> None: ...

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
