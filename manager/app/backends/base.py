from abc import ABC, abstractmethod

from ..config import Settings
from ..models import SessionRecord, SessionStatus
from ..storage import MODEL_CACHE_DIR, session_subpath

DATA_DIR = "/app/backend/data"
LABEL_SESSION = "owui-manager/session"
LABEL_SUB = "owui-manager/sub"


def resource_name(session_id: str) -> str:
    return f"owui-{session_id}"


def shared_mounts(session_id: str) -> dict[str, str]:
    return {
        DATA_DIR: session_subpath(session_id),
        f"{DATA_DIR}/{MODEL_CACHE_DIR}": MODEL_CACHE_DIR,
    }


class ContainerBackend(ABC):
    def __init__(self, settings: Settings):
        self.settings = settings

    def instance_env(self, record: SessionRecord) -> dict[str, str]:
        return {
            **self.settings.openwebui_env,
            **record.env,
            "PORT": str(self.settings.openwebui_port),
            "WEBUI_URL": self.settings.public_url(self.settings.session_host(record.id)),
            "WEBUI_SECRET_KEY": record.secret_key,
            "WEBUI_AUTH_TRUSTED_EMAIL_HEADER": "X-Forwarded-Email",
            "WEBUI_AUTH_TRUSTED_NAME_HEADER": "X-Forwarded-Name",
            "DATA_DIR": DATA_DIR,
        }

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
        return self.statuses().get(session_id, SessionStatus.MISSING)
