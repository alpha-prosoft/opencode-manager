from dataclasses import dataclass

from ..config import Settings
from ..sessions import SessionService
from .audit import AuditCollector, AuditStore
from .credentials import Credentials
from .registry import PlatformRegistry
from .runner import RunService
from .runs import RunStore
from .secrets import SecretStore
from .telemetry import TelemetryStore
from .triggers import TriggerService
from .usage import UsageStore


@dataclass
class Platform:
    settings: Settings
    signing_key: str
    secrets: SecretStore
    credentials: Credentials
    registry: PlatformRegistry
    sessions: SessionService
    runs: RunStore
    runner: RunService
    triggers: TriggerService
    usage: UsageStore
    audit: AuditStore
    collector: AuditCollector
    telemetry: TelemetryStore
    engine: object = None

    @property
    def storage(self):
        return self.sessions.storage
