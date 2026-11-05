import json
import os
import shutil
from dataclasses import asdict
from pathlib import Path

from .backends.base import SESSION_UID, config_subpath, session_subpath
from .platform.harness import Sandbox

SESSION_DIRS = ("home", "workspace")


class SharedStorage:
    def __init__(self, mount_path: str):
        self.root = Path(mount_path)

    def session_dir(self, session_id: str) -> Path:
        return self.root / session_subpath(session_id)

    def workspace(self, session_id: str) -> Path:
        return self.session_dir(session_id) / "workspace"

    def _config_dir(self, session_id: str) -> Path:
        return self.root / "configs" / session_id

    def prepare(self, session_id: str) -> None:
        for name in SESSION_DIRS:
            (self.session_dir(session_id) / name).mkdir(parents=True, exist_ok=True)
        self.hand_over(session_id)

    def hand_over(self, session_id: str) -> None:
        for path in [self.session_dir(session_id), *self.session_dir(session_id).rglob("*")]:
            if not path.is_symlink():
                os.chown(path, SESSION_UID, SESSION_UID)

    def write_sandbox(self, session_id: str, sandbox: Sandbox) -> None:
        mount = self.root / config_subpath(session_id)
        shutil.rmtree(mount, ignore_errors=True)
        for relative, content in sandbox.files.items():
            path = mount / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            path.chmod(0o444)
        for directory in [mount, *(p for p in mount.rglob("*") if p.is_dir())]:
            directory.chmod(0o555)
        host_only = self._config_dir(session_id) / "sandbox.json"
        host_only.write_text(json.dumps({k: v for k, v in asdict(sandbox).items() if k != "files"}))
        host_only.chmod(0o600)

    def load_sandbox(self, session_id: str) -> Sandbox | None:
        path = self._config_dir(session_id) / "sandbox.json"
        if not path.is_file():
            return None
        return Sandbox(**json.loads(path.read_text()))

    def remove(self, session_id: str) -> None:
        shutil.rmtree(self.session_dir(session_id), ignore_errors=True)
        mount = self.root / config_subpath(session_id)
        if mount.exists():
            for directory in [mount, *(p for p in mount.rglob("*") if p.is_dir())]:
                directory.chmod(0o755)
        shutil.rmtree(self._config_dir(session_id), ignore_errors=True)
