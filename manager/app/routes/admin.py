import time
from datetime import UTC, datetime, timedelta

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.concurrency import run_in_threadpool

from ..models import Identity, SessionStatus
from ..platform.context import Platform
from ..platform.spec import ArchiveSource, GitSource, PlatformSpec
from . import get_platform
from .api import platform_admin

router = APIRouter(prefix="/api/admin", dependencies=[Depends(platform_admin)])


def _since(days: int) -> str:
    return (datetime.now(UTC) - timedelta(days=days)).isoformat()


def _price(spec: PlatformSpec, row: dict) -> float | None:
    connection = next((c for c in spec.llm_connections if c.id == row["connection"]), None)
    price = connection.pricing.get(row["model"]) if connection else None
    if price is None:
        return None
    cached = row.get("cached_tokens") or 0
    cached_rate = price.cached_input if price.cached_input is not None else price.input
    prompt = (row["prompt_tokens"] or 0) - cached
    return round((prompt * price.input + cached * cached_rate + (row["completion_tokens"] or 0) * price.output) / 1_000_000, 4)


@router.get("/overview")
def overview(platform: Platform = Depends(get_platform)) -> dict:
    spec = platform.registry.platform()
    statuses = platform.sessions.backend.statuses()
    today = datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0).isoformat()
    usage = platform.usage.by_team_model(today)
    runs = platform.runs.list({t.id for t in spec.teams}, 1000)
    runs_today = [r for r in runs if r["created_at"] >= today]
    costs = [c for c in (_price(spec, row) for row in usage) if c is not None]
    return {
        "status": platform.registry.status(),
        "teams": len(spec.teams),
        "users": len({u for t in spec.teams for u in t.members + t.admins} | set(spec.admins)),
        "sessions": {
            "total": len(platform.sessions.store.list()),
            "running": sum(1 for s in statuses.values() if s == SessionStatus.RUNNING),
        },
        "runs_today": {state: sum(1 for r in runs_today if r["status"] == state) for state in {r["status"] for r in runs_today}},
        "tokens_today": sum((row["prompt_tokens"] or 0) + (row["completion_tokens"] or 0) for row in usage),
        "cost_today": round(sum(costs), 4) if costs else None,
    }


@router.get("/users")
def users(platform: Platform = Depends(get_platform)) -> list[dict]:
    spec = platform.registry.platform()
    activity = {row["username"]: row for row in platform.usage.by_user(_since(30))}
    people: dict[str, dict] = {}
    for user in spec.admins:
        people.setdefault(user, {"username": user, "platform_admin": True, "teams": {}})
    for team in spec.teams:
        for user in team.members + team.admins:
            entry = people.setdefault(user, {"username": user, "platform_admin": user in spec.admins, "teams": {}})
            entry["teams"][team.id] = "admin" if user in team.admins else "member"
    return [
        {**entry, "runs_30d": activity.get(user, {}).get("runs", 0), "tokens_30d": activity.get(user, {}).get("tokens", 0)}
        for user, entry in sorted(people.items())
    ]


@router.get("/usage")
def usage(days: int = Query(default=7, ge=1, le=90), platform: Platform = Depends(get_platform)) -> list[dict]:
    spec = platform.registry.platform()
    return [{**row, "cost": _price(spec, row)} for row in platform.usage.by_team_model(_since(days))]


@router.get("/secrets")
def secrets(platform: Platform = Depends(get_platform)) -> list[dict]:
    spec = platform.registry.platform()
    used: dict[str, set[str]] = {}
    for connection in spec.llm_connections:
        used.setdefault(connection.secret, set()).add(f"llm connection {connection.id}")
    for team in spec.teams:
        for secret in team.secrets:
            used.setdefault(secret, set()).add(f"granted to {team.id}")
        if isinstance(team.repository, (GitSource, ArchiveSource)) and team.repository.credentials:
            used.setdefault(team.repository.credentials, set()).add(f"repository of {team.id}")
        if team.notifications.webhook:
            used.setdefault(team.notifications.webhook, set()).add(f"webhook of {team.id}")
    if platform.settings.onboarding_credentials:
        used.setdefault(platform.settings.onboarding_credentials, set()).add("onboarding repository")
    available = platform.secrets.available()
    return [
        {"name": name, "configured": name in available, "used_by": sorted(uses), "hosts": spec.secret_hosts.get(name, [])}
        for name, uses in sorted(used.items())
    ]


@router.post("/llm/{connection_id}/test")
async def test_connection(connection_id: str, platform: Platform = Depends(get_platform)) -> dict:
    connection = next((c for c in platform.registry.platform().llm_connections if c.id == connection_id), None)
    if connection is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown LLM connection")
    secret = platform.secrets.get(connection.secret)
    if not secret:
        return {"ok": False, "error": f"secret {connection.secret} is not configured"}
    started = time.monotonic()
    body = {"model": connection.models[0], "messages": [{"role": "user", "content": "Reply with OK"}], "max_tokens": 5}
    try:
        async with httpx.AsyncClient(timeout=30) as http:
            response = await http.post(
                f"{connection.upstream()}/chat/completions", json=body, headers={"Authorization": f"Bearer {secret}"}
            )
    except httpx.HTTPError as error:
        return {"ok": False, "error": str(error)}
    latency = int((time.monotonic() - started) * 1000)
    if response.status_code >= 400:
        return {"ok": False, "status": response.status_code, "error": response.text[:300], "latency_ms": latency}
    return {"ok": True, "model": connection.models[0], "latency_ms": latency}


@router.get("/schedules")
async def schedules(platform: Platform = Depends(get_platform)) -> list[dict]:
    items = []
    async for entry in await platform.engine.client.list_schedules():
        info = entry.info
        items.append(
            {
                "id": entry.id,
                "next_run": info.next_action_times[0].isoformat() if info and info.next_action_times else None,
                "last_run": info.recent_actions[-1].started_at.isoformat() if info and info.recent_actions else None,
                "paused": bool(entry.schedule and entry.schedule.state.paused),
            }
        )
    return sorted(items, key=lambda s: s["id"])


@router.post("/sessions/stop-idle")
async def stop_idle(identity: Identity = Depends(platform_admin), platform: Platform = Depends(get_platform)) -> dict:
    stopped = []
    for session_id in await run_in_threadpool(platform.sessions.running_session_ids):
        record = platform.sessions.get(session_id)
        if record and await run_in_threadpool(platform.sessions.is_done, record):
            await run_in_threadpool(platform.sessions.stop, session_id)
            stopped.append(session_id)
    return {"stopped": stopped}
