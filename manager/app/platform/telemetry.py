import json

from fastapi import APIRouter, HTTPException, Request, status
from pydantic import BaseModel, Field

from ..db import Database
from ..store import now
from .harness import verify_key

KINDS = {"tool.start", "tool.end", "tool.blocked", "event"}
MAX_DETAIL = 4000

router = APIRouter(prefix="/api/telemetry", include_in_schema=False)


class TelemetryEvent(BaseModel):
    kind: str
    at: int | None = None
    tool: str | None = Field(default=None, max_length=100)
    call_id: str | None = Field(default=None, max_length=200)
    conversation: str | None = Field(default=None, max_length=200)
    duration_ms: int | None = Field(default=None, ge=0)
    args: str | None = None
    title: str | None = None
    output_bytes: int | None = None
    reason: str | None = None
    event: str | None = Field(default=None, max_length=100)
    details: str | None = None


class TelemetryStore:
    def __init__(self, db: Database):
        self.db = db

    def record(self, session_id: str, team_id: str | None, event: TelemetryEvent) -> None:
        detail = {k: v for k, v in event.model_dump(exclude={"kind", "tool", "call_id", "conversation", "duration_ms", "at"}).items() if v is not None}
        with self.db.connect() as db:
            db.execute(
                "INSERT INTO telemetry (session_id, team_id, ts, kind, tool, call_id, conversation, duration_ms, detail) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    session_id,
                    team_id,
                    now(),
                    event.kind,
                    event.tool,
                    event.call_id,
                    event.conversation,
                    event.duration_ms,
                    json.dumps(detail)[:MAX_DETAIL],
                ),
            )

    def activity(self, session_id: str, limit: int = 100) -> dict:
        with self.db.connect() as db:
            rows = [
                dict(r)
                for r in db.execute(
                    "SELECT * FROM telemetry WHERE session_id = ? ORDER BY id DESC LIMIT ?", (session_id, limit)
                ).fetchall()
            ]
            stats = db.execute(
                "SELECT tool, COUNT(*) AS calls, AVG(duration_ms) AS avg_ms, MAX(duration_ms) AS max_ms "
                "FROM telemetry WHERE session_id = ? AND kind = 'tool.end' GROUP BY tool ORDER BY calls DESC",
                (session_id,),
            ).fetchall()
            blocked = db.execute(
                "SELECT COUNT(*) FROM telemetry WHERE session_id = ? AND kind = 'tool.blocked'", (session_id,)
            ).fetchone()[0]
        for row in rows:
            row["detail"] = json.loads(row["detail"])
        finished = {r["call_id"] for r in rows if r["kind"] == "tool.end"}
        running = [r for r in rows if r["kind"] == "tool.start" and r["call_id"] not in finished]
        status_event = next((r for r in rows if r["kind"] == "event" and r["detail"].get("event") in ("session.status", "session.idle")), None)
        files = sorted({json.loads(r["detail"].get("details") or "{}").get("file") for r in rows if r["detail"].get("event") == "file.edited"} - {None})
        return {
            "current": running[0] if running else None,
            "state": "busy" if running else ("idle" if status_event and status_event["detail"].get("event") == "session.idle" else "unknown"),
            "events": rows,
            "tools": [dict(s) for s in stats],
            "blocked": blocked,
            "files_edited": files,
            "last_seen": rows[0]["ts"] if rows else None,
        }

    def metrics(self) -> list[dict]:
        with self.db.connect() as db:
            rows = db.execute(
                "SELECT COALESCE(team_id, '') AS team_id, COALESCE(tool, '') AS tool, kind, COUNT(*) AS n, "
                "COALESCE(SUM(duration_ms), 0) AS total_ms FROM telemetry WHERE kind IN ('tool.end', 'tool.blocked') "
                "GROUP BY team_id, tool, kind"
            ).fetchall()
        return [dict(r) for r in rows]


@router.post("", status_code=status.HTTP_204_NO_CONTENT)
async def ingest(event: TelemetryEvent, request: Request) -> None:
    platform = request.app.state.platform
    session_id = verify_key(platform.signing_key, "telemetry", request.headers.get("x-platform-key", ""))
    if session_id is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid telemetry key")
    if event.kind not in KINDS:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "Unknown telemetry kind")
    record = platform.sessions.get(session_id)
    if record is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown session")
    platform.telemetry.record(session_id, record.team_id, event)
    if event.kind.startswith("tool."):
        platform.sessions.touch(session_id)

