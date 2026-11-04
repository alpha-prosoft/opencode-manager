import shutil
from pathlib import Path

SESSIONS_DIR = "sessions"
MODEL_CACHE_DIR = "cache/embedding/models"


def session_subpath(session_id: str) -> str:
    return f"{SESSIONS_DIR}/{session_id}"


class SharedStorage:
    def __init__(self, mount_path: str):
        self.root = Path(mount_path)

    def prepare(self, session_id: str) -> None:
        (self.root / session_subpath(session_id)).mkdir(parents=True, exist_ok=True)
        (self.root / MODEL_CACHE_DIR).mkdir(parents=True, exist_ok=True)

    def remove(self, session_id: str) -> None:
        shutil.rmtree(self.root / session_subpath(session_id), ignore_errors=True)
