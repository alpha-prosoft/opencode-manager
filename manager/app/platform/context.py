from dataclasses import dataclass

from ..config import Settings
from ..sessions import SessionService
from .audit import AuditCollector, AuditStore
from .registry import PlatformRegistry
from .runner import RunService
from .runs import RunStore
from .secrets import SecretStore
from .triggers import TriggerService
from .usage import UsageStore


@dataclass
class Platform:
    settings: Settings
    signing_key: str
    secrets: SecretStore
    registry: PlatformRegistry
    sessions: SessionService
    runs: RunStore
    runner: RunService
    triggers: TriggerService
    usage: UsageStore
    audit: AuditStore
    collector: AuditCollector
    engine: object = None

    @property
    def storage(self):
        return self.sessions.storage
