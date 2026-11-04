import json
import sqlite3
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

from .models import SessionRecord

SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    sub TEXT NOT NULL,
    email TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    labels TEXT NOT NULL,
    env TEXT NOT NULL,
    image TEXT NOT NULL,
    secret_key TEXT NOT NULL,
    created_at TEXT NOT NULL,
    last_activity_at TEXT NOT NULL,
    stopped_at TEXT
);
CREATE INDEX IF NOT EXISTS sessions_sub ON sessions (sub);
"""

JSON_COLUMNS = ("labels", "env")


class SessionStore:
    def __init__(self, path: str):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._path = path
        with self._connect() as db:
            db.executescript(SCHEMA)

    @contextmanager
    def _connect(self):
        db = sqlite3.connect(self._path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        finally:
            db.close()

    @staticmethod
    def _to_record(row: sqlite3.Row) -> SessionRecord:
        data = dict(row)
        for column in JSON_COLUMNS:
            data[column] = json.loads(data[column])
        return SessionRecord.model_validate(data)

    def insert(self, record: SessionRecord) -> None:
        data = record.model_dump(mode="json")
        data["secret_key"] = record.secret_key
        for column in JSON_COLUMNS:
            data[column] = json.dumps(data[column])
        columns = ", ".join(data)
        placeholders = ", ".join(f":{key}" for key in data)
        with self._connect() as db:
            db.execute(f"INSERT INTO sessions ({columns}) VALUES ({placeholders})", data)

    def get(self, session_id: str) -> SessionRecord | None:
        with self._connect() as db:
            row = db.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
        return self._to_record(row) if row else None

    def list(self, sub: str | None = None) -> list[SessionRecord]:
        query, params = "SELECT * FROM sessions", ()
        if sub is not None:
            query, params = query + " WHERE sub = ?", (sub,)
        with self._connect() as db:
            rows = db.execute(query + " ORDER BY created_at DESC", params).fetchall()
        return [self._to_record(row) for row in rows]

    def touch(self, session_id: str) -> None:
        with self._connect() as db:
            db.execute(
                "UPDATE sessions SET last_activity_at = ?, stopped_at = NULL WHERE id = ?",
                (datetime.now(UTC).isoformat(), session_id),
            )

    def mark_stopped(self, session_id: str) -> None:
        with self._connect() as db:
            db.execute(
                "UPDATE sessions SET stopped_at = ? WHERE id = ?",
                (datetime.now(UTC).isoformat(), session_id),
            )

    def delete(self, session_id: str) -> None:
        with self._connect() as db:
            db.execute("DELETE FROM sessions WHERE id = ?", (session_id,))
