import shutil
from pathlib import Path

SESSION_DIRS = ("home", "workspace")


def session_subpath(session_id: str) -> str:
    return f"sessions/{session_id}"


class SharedStorage:
    def __init__(self, mount_path: str):
        self.root = Path(mount_path)

    def prepare(self, session_id: str) -> None:
        for name in SESSION_DIRS:
            (self.root / session_subpath(session_id) / name).mkdir(parents=True, exist_ok=True)

    def remove(self, session_id: str) -> None:
        shutil.rmtree(self.root / session_subpath(session_id), ignore_errors=True)
