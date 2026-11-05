import json
from datetime import UTC, datetime

from .db import Database
from .models import SessionRecord

JSON_COLUMNS = ("labels", "env")


def now() -> str:
    return datetime.now(UTC).isoformat()


class SessionStore:
    def __init__(self, db: Database):
        self.db = db

    @staticmethod
    def _to_record(row) -> SessionRecord:
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
        with self.db.connect() as db:
            db.execute(f"INSERT INTO sessions ({columns}) VALUES ({placeholders})", data)

    def get(self, session_id: str) -> SessionRecord | None:
        with self.db.connect() as db:
            row = db.execute("SELECT * FROM sessions WHERE id = ?", (session_id,)).fetchone()
        return self._to_record(row) if row else None

    def list(self) -> list[SessionRecord]:
        with self.db.connect() as db:
            rows = db.execute("SELECT * FROM sessions ORDER BY created_at DESC").fetchall()
        return [self._to_record(row) for row in rows]

    def update(self, session_id: str, changes: dict) -> None:
        for column in JSON_COLUMNS:
            if column in changes:
                changes[column] = json.dumps(changes[column])
        assignments = ", ".join(f"{column} = :{column}" for column in changes)
        with self.db.connect() as db:
            db.execute(f"UPDATE sessions SET {assignments} WHERE id = :id", {**changes, "id": session_id})

    def touch(self, session_id: str) -> None:
        with self.db.connect() as db:
            db.execute(
                "UPDATE sessions SET last_activity_at = ?, stopped_at = NULL WHERE id = ?", (now(), session_id)
            )

    def mark_stopped(self, session_id: str) -> None:
        with self.db.connect() as db:
            db.execute("UPDATE sessions SET stopped_at = ? WHERE id = ?", (now(), session_id))

    def delete(self, session_id: str) -> None:
        with self.db.connect() as db:
            db.execute("DELETE FROM sessions WHERE id = ?", (session_id,))

    def posted_review_comments(self, session_id: str) -> set[str]:
        with self.db.connect() as db:
            rows = db.execute("SELECT key FROM review_comments WHERE session_id = ?", (session_id,)).fetchall()
        return {row["key"] for row in rows}

    def record_review_comment(self, session_id: str, key: str, comment_id: int) -> None:
        with self.db.connect() as db:
            db.execute(
                "INSERT INTO review_comments (session_id, key, comment_id, posted_at) VALUES (?, ?, ?, ?)",
                (session_id, key, comment_id, now()),
            )
