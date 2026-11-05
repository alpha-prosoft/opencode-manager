import json
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Body, Depends, HTTPException, Query, Response, status
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import PlainTextResponse, StreamingResponse

from ..auth import clear_auth_cookie, current_identity, login_identity, set_auth_cookie
from ..config import Settings, get_settings
from ..models import Identity, LoginRequest, Session, SessionRecord, SessionUpdate
from ..platform.catalog import CATALOG
from ..platform.context import Platform
from ..platform.runner import RunError
from ..platform.spec import json_schemas
from . import get_platform

router = APIRouter(prefix="/api")
metrics_router = APIRouter()

USAGE_WINDOW_DAYS = 7


def _since(days: int = USAGE_WINDOW_DAYS) -> str:
    return (datetime.now(UTC) - timedelta(days=days)).isoformat()


def visible_session(
    session_id: str, identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)
) -> SessionRecord:
    record = platform.sessions.get(session_id)
    if record is None or not platform.sessions.can_view(record, identity):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Session not found")
    return record


def managed_session(
    record: SessionRecord = Depends(visible_session),
    identity: Identity = Depends(current_identity),
    platform: Platform = Depends(get_platform),
) -> SessionRecord:
    if not platform.sessions.can_manage(record, identity):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the owner or a team admin can change this session")
    return record


def team_member(team_id: str, identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)) -> str:
    if platform.registry.team(team_id) is None or not platform.registry.role(identity.sub, team_id):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Team not found")
    return team_id


def platform_admin(identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)) -> Identity:
    if not platform.registry.is_platform_admin(identity.sub):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Platform administrators only")
    return identity


# ---- auth ----------------------------------------------------------------------------


@router.post("/auth/login")
def login(data: LoginRequest, response: Response, settings: Settings = Depends(get_settings)) -> Identity:
    if settings.auth_mode != "login":
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Login is handled by the identity provider")
    identity = login_identity(data.username, settings)
    set_auth_cookie(response, identity, settings)
    return identity


@router.post("/auth/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(response: Response) -> None:
    clear_auth_cookie(response)


@router.get("/me")
def me(identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)) -> dict:
    teams = sorted(platform.registry.teams_of(identity.sub))
    return {
        **identity.model_dump(),
        "platform_admin": platform.registry.is_platform_admin(identity.sub),
        "teams": [{"id": t, "role": platform.registry.role(identity.sub, t)} for t in teams],
    }


# ---- platform ------------------------------------------------------------------------


@router.get("/marketplace")
def marketplace(identity: Identity = Depends(current_identity)) -> list[dict]:
    return [activity.schema() for activity in CATALOG.values()]


@router.get("/schemas")
def schemas() -> dict:
    return json_schemas()


@router.get("/platform")
def platform_status(identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)) -> dict:
    spec = platform.registry.platform()
    return {
        **platform.registry.status(),
        "teams": len(spec.teams),
        "llm_connections": [{"id": c.id, "type": c.type, "models": c.models} for c in spec.llm_connections],
        "allowed_images": spec.allowed_images,
    }


@router.post("/platform/sync")
async def sync(identity: Identity = Depends(platform_admin), platform: Platform = Depends(get_platform)) -> dict:
    return await platform.engine.sync_now()


# ---- teams ---------------------------------------------------------------------------


def _team_summary(platform: Platform, team: dict, username: str) -> dict:
    definitions = platform.registry.definitions(team["id"])
    return {
        "id": team["id"],
        "name": team["spec"]["name"],
        "description": team["spec"]["description"],
        "repository": {k: team["spec"]["repository"][k] for k in ("url", "branch", "path")},
        "commit": team["commit_id"],
        "synced_at": team["synced_at"],
        "status": team["status"],
        "error": team["error"],
        "role": platform.registry.role(username, team["id"]),
        "counts": {kind: sum(1 for d in definitions if d.kind == kind) for kind in ("agents", "harnesses", "workflows")},
        "invalid": sum(1 for d in definitions if not d.valid),
    }


@router.get("/teams")
def teams(identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)) -> list[dict]:
    mine = platform.registry.teams_of(identity.sub)
    return [_team_summary(platform, t, identity.sub) for t in platform.registry.teams() if t["id"] in mine]


@router.get("/teams/{team_id}")
def team(
    team_id: str = Depends(team_member), identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)
) -> dict:
    record = next(t for t in platform.registry.teams() if t["id"] == team_id)
    spec = record["spec"]
    return {
        **_team_summary(platform, record, identity.sub),
        "members": spec["members"],
        "admins": spec["admins"],
        "secrets": spec["secrets"],
        "definitions": [
            {"kind": d.kind, "id": d.id, "source": d.source, "spec": d.spec, "errors": d.errors, "valid": d.valid}
            for d in platform.registry.definitions(team_id)
        ],
        "usage": platform.usage.for_team(team_id, _since()),
        "usage_by_model": platform.usage.by_model({team_id}, _since()),
    }


@router.post("/teams/{team_id}/workflows/{workflow_id}/runs", status_code=status.HTTP_201_CREATED)
async def start_run(
    workflow_id: str,
    team_id: str = Depends(team_member),
    inputs: dict = Body(default={}),
    identity: Identity = Depends(current_identity),
    platform: Platform = Depends(get_platform),
) -> dict:
    try:
        run = await run_in_threadpool(platform.runner.create_manual, identity, team_id, workflow_id, inputs)
    except RunError as error:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, str(error)) from error
    await platform.engine.start_run(run["id"])
    return run


# ---- runs ----------------------------------------------------------------------------


def _visible_run(platform: Platform, identity: Identity, run_id: str) -> dict:
    run = platform.runs.get(run_id)
    if run is None or run["team_id"] not in platform.registry.teams_of(identity.sub):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Run not found")
    return run


@router.get("/runs")
def runs(
    team: str | None = None,
    limit: int = Query(default=100, le=500),
    identity: Identity = Depends(current_identity),
    platform: Platform = Depends(get_platform),
) -> list[dict]:
    items = platform.runs.list(platform.registry.teams_of(identity.sub), limit, team)
    return [{k: v for k, v in run.items() if k != "result"} | {"has_result": bool(run["result"])} for run in items]


@router.get("/runs/{run_id}")
def run(run_id: str, identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)) -> dict:
    item = _visible_run(platform, identity, run_id)
    record = platform.sessions.get(item["session_id"]) if item["session_id"] else None
    session = platform.sessions.describe(record, identity).model_dump(mode="json") if record and platform.sessions.can_view(record, identity) else None
    return {**item, "session": session, "usage": platform.usage.for_run(run_id)}


# ---- sessions ------------------------------------------------------------------------


@router.get("/sessions")
def list_sessions(identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)) -> list[Session]:
    return platform.sessions.list(identity)


@router.get("/sessions/{session_id}")
def get_session(
    record: SessionRecord = Depends(visible_session),
    identity: Identity = Depends(current_identity),
    platform: Platform = Depends(get_platform),
) -> dict:
    return {
        **platform.sessions.describe(record, identity).model_dump(mode="json"),
        "usage": platform.usage.for_session(record.id),
        "audit_events": platform.audit.count(record.id),
    }


@router.patch("/sessions/{session_id}")
def update_session(
    changes: SessionUpdate,
    record: SessionRecord = Depends(managed_session),
    identity: Identity = Depends(current_identity),
    platform: Platform = Depends(get_platform),
) -> Session:
    return platform.sessions.describe(platform.sessions.update(record, changes), identity)


@router.post("/sessions/{session_id}/start")
async def start_session(
    record: SessionRecord = Depends(visible_session),
    identity: Identity = Depends(current_identity),
    platform: Platform = Depends(get_platform),
) -> Session:
    await platform.engine.start_session(record.id)
    return await run_in_threadpool(platform.sessions.describe, record, identity)


@router.post("/sessions/{session_id}/stop")
def stop_session(
    record: SessionRecord = Depends(managed_session),
    identity: Identity = Depends(current_identity),
    platform: Platform = Depends(get_platform),
) -> Session:
    platform.sessions.stop(record.id)
    return platform.sessions.describe(record, identity)


@router.delete("/sessions/{session_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_session(record: SessionRecord = Depends(managed_session), platform: Platform = Depends(get_platform)) -> None:
    platform.sessions.delete(record.id)


@router.get("/sessions/{session_id}/audit")
def audit(
    after: int = 0,
    limit: int = Query(default=200, le=1000),
    record: SessionRecord = Depends(visible_session),
    platform: Platform = Depends(get_platform),
) -> list[dict]:
    return platform.audit.events(record.id, after, limit)


@router.get("/sessions/{session_id}/audit/verify")
def verify_audit(record: SessionRecord = Depends(visible_session), platform: Platform = Depends(get_platform)) -> dict:
    return platform.audit.verify(record.id)


@router.get("/sessions/{session_id}/audit/export")
def export_audit(record: SessionRecord = Depends(visible_session), platform: Platform = Depends(get_platform)):
    def lines():
        after = 0
        while batch := platform.audit.events(record.id, after, 1000):
            for event in batch:
                yield json.dumps(event) + "\n"
            after = batch[-1]["seq"]

    headers = {"Content-Disposition": f'attachment; filename="audit-{record.id}.jsonl"'}
    return StreamingResponse(lines(), media_type="application/x-ndjson", headers=headers)


# ---- metrics -------------------------------------------------------------------------


def _labels(**labels) -> str:
    return ",".join(f'{k}="{str(v).replace(chr(34), "")}"' for k, v in labels.items())


@metrics_router.get("/metrics", include_in_schema=False)
def metrics(identity: Identity = Depends(platform_admin), platform: Platform = Depends(get_platform)) -> PlainTextResponse:
    lines = [
        "# HELP opencode_llm_tokens_total Tokens processed through the LLM gateway.",
        "# TYPE opencode_llm_tokens_total counter",
    ]
    usage = platform.usage.totals_for_metrics()
    for row in usage:
        for kind in ("prompt", "completion"):
            lines.append(f"opencode_llm_tokens_total{{{_labels(team=row['team_id'], model=row['model'], type=kind)}}} {row[f'{kind}_tokens'] or 0}")
    lines += ["# HELP opencode_llm_requests_total LLM gateway requests.", "# TYPE opencode_llm_requests_total counter"]
    for row in usage:
        lines.append(f"opencode_llm_requests_total{{{_labels(team=row['team_id'], model=row['model'])}}} {row['calls']}")
        lines.append(f"opencode_llm_errors_total{{{_labels(team=row['team_id'], model=row['model'])}}} {row['errors'] or 0}")
    lines += ["# HELP opencode_runs_total Activity runs by status.", "# TYPE opencode_runs_total counter"]
    for row in platform.runs.counts():
        lines.append(f"opencode_runs_total{{{_labels(team=row['team_id'], activity=row['activity'], status=row['status'])}}} {row['runs']}")
    lines += ["# HELP opencode_sessions_running Running sessions.", "# TYPE opencode_sessions_running gauge"]
    lines.append(f"opencode_sessions_running {len(platform.sessions.running_session_ids())}")
    return PlainTextResponse("\n".join(lines) + "\n", media_type="text/plain; version=0.0.4")
