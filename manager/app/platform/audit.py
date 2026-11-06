import asyncio
import hashlib
import json
import logging
import sqlite3

import httpx

from ..db import Database
from ..opencode import basic_auth
from ..store import now

log = logging.getLogger(__name__)

GENESIS = "0" * 64
IGNORED = {"message.part.delta", "server.heartbeat", "server.connected", "plugin.added", "catalog.updated", "reference.updated", "integration.updated"}
RECONCILE_SECONDS = 5


def _canonical(payload: dict) -> str:
    return json.dumps(payload, sort_keys=True, separators=(",", ":"))


class AuditStore:
    def __init__(self, db: Database):
        self.db = db

    def append(self, session_id: str, event_type: str, key: str, payload: dict) -> bool:
        body = _canonical(payload)
        with self.db.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT hash FROM audit_events WHERE session_id = ? ORDER BY seq DESC LIMIT 1", (session_id,)
            ).fetchone()
            previous = row["hash"] if row else GENESIS
            digest = hashlib.sha256(f"{previous}|{event_type}|{key}|{body}".encode()).hexdigest()
            try:
                db.execute(
                    "INSERT INTO audit_events (session_id, ts, type, key, payload, prev_hash, hash) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (session_id, now(), event_type, key, body, previous, digest),
                )
            except sqlite3.IntegrityError:
                return False
        return True

    def events(self, session_id: str, after: int = 0, limit: int = 500) -> list[dict]:
        with self.db.connect() as db:
            rows = db.execute(
                "SELECT seq, ts, type, key, payload, hash FROM audit_events WHERE session_id = ? AND seq > ? ORDER BY seq LIMIT ?",
                (session_id, after, limit),
            ).fetchall()
        return [{**dict(r), "payload": json.loads(r["payload"])} for r in rows]

    def conversations(self, session_id: str) -> list[dict]:
        with self.db.connect() as db:
            titles = {
                row["id"]: row["title"]
                for row in db.execute(
                    "SELECT json_extract(payload, '$.id') AS id, json_extract(payload, '$.title') AS title "
                    "FROM audit_events WHERE session_id = ? AND type = 'conversation'",
                    (session_id,),
                )
            }
            rows = db.execute(
                "SELECT json_extract(payload, '$.sessionID') AS id, COUNT(*) AS messages, MIN(ts) AS started, MAX(ts) AS last "
                "FROM audit_events WHERE session_id = ? AND type = 'message' GROUP BY 1 ORDER BY last DESC",
                (session_id,),
            ).fetchall()
        seen = {row["id"] for row in rows}
        items = [{**dict(row), "title": titles.get(row["id"]) or "Untitled conversation"} for row in rows if row["id"]]
        items += [{"id": cid, "title": title, "messages": 0, "started": None, "last": None} for cid, title in titles.items() if cid not in seen]
        return items

    def count(self, session_id: str) -> int:
        with self.db.connect() as db:
            return db.execute("SELECT COUNT(*) FROM audit_events WHERE session_id = ?", (session_id,)).fetchone()[0]

    def verify(self, session_id: str) -> dict:
        previous, checked = GENESIS, 0
        with self.db.connect() as db:
            rows = db.execute(
                "SELECT seq, type, key, payload, prev_hash, hash FROM audit_events WHERE session_id = ? ORDER BY seq",
                (session_id,),
            ).fetchall()
        for row in rows:
            expected = hashlib.sha256(f"{previous}|{row['type']}|{row['key']}|{row['payload']}".encode()).hexdigest()
            if row["prev_hash"] != previous or row["hash"] != expected:
                return {"valid": False, "checked": checked, "broken_at": row["seq"]}
            previous, checked = row["hash"], checked + 1
        return {"valid": True, "checked": checked, "head": previous}


def classify(event: dict) -> tuple[str, str, dict] | None:
    """Map an OpenCode event to (type, dedupe key, payload) or None when it is not worth recording."""
    kind, props = event.get("type"), event.get("properties") or {}
    if kind in IGNORED or not kind:
        return None
    if kind == "message.updated":
        info = props.get("info") or {}
        if info.get("role") == "assistant" and not (info.get("time") or {}).get("completed"):
            return None
        return "message", f"message:{info.get('id')}", info
    if kind == "message.part.updated":
        part = props.get("part") or {}
        if part.get("type") == "tool":
            state = part.get("state") or {}
            if state.get("status") not in ("completed", "error"):
                return None
            return "tool", f"part:{part.get('id')}", part
        if part.get("type") in ("text", "reasoning"):
            timing = part.get("time")
            if timing and not timing.get("end"):
                return None
            return part["type"], f"part:{part.get('id')}", part
        if part.get("type") in ("step-start", "step-finish", "snapshot", "patch"):
            return None
        return part.get("type", "part"), f"part:{part.get('id')}", part
    return kind, f"{kind}:{hashlib.sha1(_canonical(props).encode()).hexdigest()}", props


class AuditCollector:
    def __init__(self, store: AuditStore, sessions):
        self.store = store
        self.sessions = sessions
        self.tasks: dict[str, asyncio.Task] = {}

    def record(self, session_id: str, event: dict) -> None:
        classified = classify(event)
        if classified:
            self.store.append(session_id, *classified)

    async def _snapshot(self, http: httpx.AsyncClient, session_id: str) -> None:
        for conversation in (await http.get("/session")).json():
            self.store.append(session_id, "conversation", f"conversation:{conversation['id']}", conversation)
            for message in (await http.get(f"/session/{conversation['id']}/message")).json():
                self.record(session_id, {"type": "message.updated", "properties": {"info": message["info"]}})
                for part in message["parts"]:
                    self.record(session_id, {"type": "message.part.updated", "properties": {"part": part}})

    async def _follow(self, session_id: str) -> None:
        while True:
            record = self.sessions.get(session_id)
            if record is None:
                return
            base_url = self.sessions.backend.service_url(session_id)
            headers = {"Authorization": basic_auth(record.secret_key)}
            try:
                async with httpx.AsyncClient(base_url=base_url, headers=headers, timeout=httpx.Timeout(10, read=None)) as http:
                    async with http.stream("GET", "/event") as response:
                        response.raise_for_status()
                        await self._snapshot(http, session_id)
                        async for line in response.aiter_lines():
                            if line.startswith("data: "):
                                try:
                                    event = json.loads(line[6:])
                                except ValueError:
                                    continue
                                await asyncio.to_thread(self.record, session_id, event)
            except (httpx.HTTPError, OSError) as error:
                log.debug("Audit stream for %s interrupted: %s", session_id, error)
            await asyncio.sleep(3)

    async def run(self) -> None:
        while True:
            try:
                running = set(await asyncio.to_thread(self.sessions.running_session_ids))
                for session_id in running - self.tasks.keys():
                    self.tasks[session_id] = asyncio.create_task(self._follow(session_id), name=f"audit-{session_id}")
                for session_id in set(self.tasks) - running:
                    self.tasks.pop(session_id).cancel()
            except Exception:
                log.exception("Audit reconcile failed")
            await asyncio.sleep(RECONCILE_SECONDS)
