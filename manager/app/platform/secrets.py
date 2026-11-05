import os
from pathlib import Path

from ..config import Settings


class SecretStore:
    def __init__(self, settings: Settings):
        self.directory = Path(settings.secrets_dir)

    def get(self, name: str) -> str | None:
        path = self.directory / name
        if path.is_file():
            return path.read_text().strip()
        return os.environ.get(f"PLATFORM_SECRET_{name}")

    def require(self, name: str) -> str:
        value = self.get(name)
        if not value:
            raise LookupError(f"Platform secret {name} is not configured")
        return value

    def available(self) -> set[str]:
        names = {p.name for p in self.directory.iterdir() if p.is_file()} if self.directory.is_dir() else set()
        names |= {key.removeprefix("PLATFORM_SECRET_") for key in os.environ if key.startswith("PLATFORM_SECRET_")}
        return names
