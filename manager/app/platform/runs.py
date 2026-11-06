import json
import secrets

from ..db import Database
from ..store import now

ACTIVE = ("queued", "preparing", "running", "awaiting_result", "finalizing", "following_ci")
FINISHED = ("completed", "failed", "cancelled")


class RunStore:
    def __init__(self, db: Database):
        self.db = db

    @staticmethod
    def _row(row) -> dict:
        data = dict(row)
        for column in ("trigger", "inputs", "result"):
            data[column] = json.loads(data[column]) if data[column] else None
        return data

    def create(self, team_id: str, workflow_id: str, activity: str, trigger: dict, inputs: dict, created_by: str) -> dict:
        run_id = secrets.token_hex(6)
        stamp = now()
        with self.db.connect() as db:
            db.execute(
                "INSERT INTO runs (id, team_id, workflow_id, activity, status, trigger, inputs, created_by, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, 'queued', ?, ?, ?, ?, ?)",
                (run_id, team_id, workflow_id, activity, json.dumps(trigger), json.dumps(inputs), created_by, stamp, stamp),
            )
        return self.get(run_id)

    def get(self, run_id: str) -> dict | None:
        with self.db.connect() as db:
            row = db.execute("SELECT * FROM runs WHERE id = ?", (run_id,)).fetchone()
        return self._row(row) if row else None

    def list(self, team_ids: set[str], limit: int = 100, team_id: str | None = None) -> list[dict]:
        teams = {team_id} & team_ids if team_id else team_ids
        if not teams:
            return []
        marks = ",".join("?" * len(teams))
        with self.db.connect() as db:
            rows = db.execute(
                f"SELECT * FROM runs WHERE team_id IN ({marks}) ORDER BY created_at DESC LIMIT ?", (*teams, limit)
            ).fetchall()
        return [self._row(r) for r in rows]

    def update(self, run_id: str, **changes) -> None:
        for column in ("result",):
            if column in changes and changes[column] is not None:
                changes[column] = json.dumps(changes[column])
        changes["updated_at"] = now()
        assignments = ", ".join(
            f"{column} = CASE WHEN status = 'cancelled' THEN status ELSE :{column} END" if column == "status" else f"{column} = :{column}"
            for column in changes
        )
        with self.db.connect() as db:
            db.execute(f"UPDATE runs SET {assignments} WHERE id = :id", {**changes, "id": run_id})

    def active(self) -> list[dict]:
        marks = ",".join("?" * len(ACTIVE))
        with self.db.connect() as db:
            rows = db.execute(f"SELECT * FROM runs WHERE status IN ({marks})", ACTIVE).fetchall()
        return [self._row(r) for r in rows]

    def counts(self) -> list[dict]:
        with self.db.connect() as db:
            rows = db.execute(
                "SELECT team_id, activity, status, COUNT(*) AS runs FROM runs GROUP BY team_id, activity, status"
            ).fetchall()
        return [dict(r) for r in rows]

    def seen(self, trigger_key: str) -> set[str]:
        with self.db.connect() as db:
            rows = db.execute("SELECT item FROM trigger_items WHERE trigger_key = ?", (trigger_key,)).fetchall()
        return {r["item"] for r in rows}

    def mark_seen(self, trigger_key: str, item: str, run_id: str | None) -> None:
        with self.db.connect() as db:
            db.execute(
                "INSERT INTO trigger_items (trigger_key, item, run_id, seen_at) VALUES (?, ?, ?, ?) "
                "ON CONFLICT (trigger_key, item) DO UPDATE SET run_id = COALESCE(excluded.run_id, trigger_items.run_id)",
                (trigger_key, item, run_id, now()),
            )
