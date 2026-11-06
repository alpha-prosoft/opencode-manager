import httpx
from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field

from ..auth import current_identity
from ..models import Identity
from ..platform.context import Platform
from ..platform.credentials import PLATFORM_OWNER, now
from ..platform.spec import CredentialSpec, PlatformSpec
from . import get_platform
from .api import platform_admin

router = APIRouter(prefix="/api")


class CredentialValue(BaseModel):
    secret: str = Field(min_length=1, max_length=16384)
    username: str | None = Field(default=None, max_length=200)


def _declared(platform: Platform, credential_id: str) -> CredentialSpec:
    spec = platform.registry.platform().credential(credential_id)
    if spec is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown credential")
    return spec


def _value(spec: CredentialSpec, body: CredentialValue) -> dict:
    if spec.kind == "basic" and not body.username:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, f"{spec.name} needs a username as well")
    value = {"secret": body.secret.strip() if spec.kind != "ssh_key" else body.secret}
    if body.username:
        value["username"] = body.username.strip()
    return value


def pending(platform: Platform, username: str) -> list[str]:
    teams = platform.registry.teams_of(username)
    items = platform.credentials.status(username, teams)
    with platform.registry.db.connect() as db:
        seen = {row["credential_id"] for row in db.execute("SELECT credential_id FROM credential_prompts WHERE username = ?", (username,))}
    return [item["id"] for item in items if item["id"] not in seen or (item["required"] and item["state"] == "missing")]


@router.get("/me/credentials")
def my_credentials(identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)) -> dict:
    teams = platform.registry.teams_of(identity.sub)
    return {"credentials": platform.credentials.status(identity.sub, teams), "pending": pending(platform, identity.sub)}


@router.put("/me/credentials/{credential_id}")
async def set_my_credential(
    credential_id: str, body: CredentialValue, identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)
) -> dict:
    spec = _declared(platform, credential_id)
    if not spec.personal:
        raise HTTPException(status.HTTP_403_FORBIDDEN, f"{spec.name} is managed by platform admins")
    if spec.teams != "*" and not set(spec.teams) & platform.registry.teams_of(identity.sub):
        raise HTTPException(status.HTTP_403_FORBIDDEN, f"{spec.name} is not used by your teams")
    value = _value(spec, body)
    ok, message = await run_in_threadpool(platform.credentials.test, spec, value)
    if not ok:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, f"Not saved. {message}")
    platform.credentials.vault.put(credential_id, identity.sub, value, identity.sub)
    if spec.test:
        platform.credentials.vault.mark_verified(credential_id, identity.sub)
    _mark_seen(platform, identity.sub, [credential_id])
    return {"message": message}


@router.delete("/me/credentials/{credential_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_my_credential(credential_id: str, identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)) -> None:
    _declared(platform, credential_id)
    platform.credentials.vault.delete(credential_id, identity.sub)


class Seen(BaseModel):
    credentials: list[str]


@router.post("/me/credentials/seen", status_code=status.HTTP_204_NO_CONTENT)
def mark_seen(body: Seen, identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)) -> None:
    _mark_seen(platform, identity.sub, body.credentials)


def _mark_seen(platform: Platform, username: str, credential_ids: list[str]) -> None:
    with platform.registry.db.connect() as db:
        for credential_id in credential_ids:
            db.execute(
                "INSERT INTO credential_prompts VALUES (?, ?, ?) ON CONFLICT (username, credential_id) DO NOTHING",
                (username, credential_id, now()),
            )


def _usage(spec: PlatformSpec, credential_id: str, onboarding_secret: str | None) -> list[str]:
    uses = [f"LLM connection {c.id}" for c in spec.llm_connections if c.secret == credential_id]
    uses += [f"MCP server {m.name}" for m in spec.mcp_servers if m.credential == credential_id]
    for team in spec.teams:
        if getattr(team.repository, "credentials", None) == credential_id:
            uses.append(f"repository of {team.id}")
        if team.notifications.webhook == credential_id:
            uses.append(f"webhook of {team.id}")
        if credential_id in team.secrets:
            uses.append(f"granted to {team.id}")
    if onboarding_secret == credential_id:
        uses.append("onboarding repository")
    return uses


@router.get("/admin/credentials", dependencies=[Depends(platform_admin)])
def admin_credentials(platform: Platform = Depends(get_platform)) -> list[dict]:
    spec = platform.registry.platform()
    rows = platform.credentials.vault.metadata()
    stored = {row["credential_id"]: row for row in rows if row["owner"] == PLATFORM_OWNER}
    personal: dict[str, int] = {}
    for row in rows:
        if row["owner"] != PLATFORM_OWNER:
            personal[row["credential_id"]] = personal.get(row["credential_id"], 0) + 1
    declared = {c.id: c for c in spec.credentials}
    names = set(declared) | {c.secret for c in spec.llm_connections} | {m.credential for m in spec.mcp_servers if m.credential}
    for team in spec.teams:
        names |= set(team.secrets)
        names |= {s for s in (getattr(team.repository, "credentials", None), team.notifications.webhook) if s}
    if platform.settings.onboarding_credentials:
        names.add(platform.settings.onboarding_credentials)
    items = []
    for name in sorted(names):
        credential = declared.get(name)
        source = "admin" if name in stored else "file" if platform.secrets.file_value(name) else None
        items.append(
            {
                "id": name,
                "name": credential.name if credential else name,
                "description": credential.description if credential else "",
                "kind": credential.kind if credential else "token",
                "provided_by": credential.provided_by if credential else "platform",
                "declared": credential is not None,
                "hosts": spec.credential_hosts(name),
                "platform_value": source,
                "hint": stored[name]["hint"] if name in stored else None,
                "updated_at": stored[name]["updated_at"] if name in stored else None,
                "updated_by": stored[name]["updated_by"] if name in stored else None,
                "personal_values": personal.get(name, 0),
                "used_by": _usage(spec, name, platform.settings.onboarding_credentials),
            }
        )
    return items


@router.put("/admin/credentials/{credential_id}")
async def set_platform_credential(
    credential_id: str, body: CredentialValue, identity: Identity = Depends(platform_admin), platform: Platform = Depends(get_platform)
) -> dict:
    spec = platform.registry.platform().credential(credential_id) or CredentialSpec(id=credential_id, name=credential_id)
    if spec.provided_by == "user":
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, f"{spec.name} is personal only: every user sets their own value")
    value = _value(spec, body)
    ok, message = await run_in_threadpool(platform.credentials.test, spec, value)
    if not ok:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, f"Not saved. {message}")
    platform.credentials.vault.put(credential_id, PLATFORM_OWNER, value, identity.sub)
    return {"message": message}


@router.delete("/admin/credentials/{credential_id}", status_code=status.HTTP_204_NO_CONTENT, dependencies=[Depends(platform_admin)])
def delete_platform_credential(credential_id: str, platform: Platform = Depends(get_platform)) -> None:
    platform.credentials.vault.delete(credential_id, PLATFORM_OWNER)


def _readiness(platform: Platform, spec: PlatformSpec, credential_id: str, username: str) -> dict:
    declared = spec.credential(credential_id)
    resolved = platform.credentials.resolve(credential_id, username)
    return {
        "id": credential_id,
        "name": declared.name if declared else credential_id,
        "provided_by": declared.provided_by if declared else "platform",
        "source": resolved.source if resolved else None,
    }


@router.get("/harnesses")
def harnesses(identity: Identity = Depends(current_identity), platform: Platform = Depends(get_platform)) -> list[dict]:
    spec = platform.registry.platform()
    items = []
    for team_id in sorted(platform.registry.teams_of(identity.sub)):
        team = platform.registry.team(team_id)
        workflows = [w for w in platform.registry.definitions(team_id, "workflows") if w.valid]
        for definition in platform.registry.definitions(team_id, "harnesses"):
            harness = definition.spec or {}
            servers = [spec.mcp_server(server_id) for server_id in harness.get("mcp", [])]
            credentials = sorted({s.credential for s in servers if s and s.credential})
            readiness = [_readiness(platform, spec, c, identity.sub) for c in credentials]
            items.append(
                {
                    "team_id": team_id,
                    "team_name": team.name if team else team_id,
                    "id": definition.id,
                    "valid": definition.valid,
                    "errors": definition.errors,
                    "description": harness.get("description", ""),
                    "network": harness.get("network", "none"),
                    "models": (harness.get("llm") or {}).get("models", []),
                    "skills": [path.rstrip("/").split("/")[-1] for path in harness.get("skills", [])],
                    "mcp_servers": [
                        {"id": s.id, "name": s.name, "description": s.description, "tools": s.tools} for s in servers if s
                    ],
                    "credentials": readiness,
                    "ready": definition.valid and all(r["source"] for r in readiness),
                    "workflows": [{"id": w.id, "name": w.spec["name"]} for w in workflows if w.spec["harness"] == definition.id],
                }
            )
    return items


def _platform_ready(platform: Platform, spec: PlatformSpec, credential_id: str | None) -> bool:
    if credential_id is None:
        return True
    declared = spec.credential(credential_id)
    return (declared is not None and declared.provided_by == "user") or platform.credentials.platform_value(credential_id) is not None


@router.get("/admin/mcp-servers", dependencies=[Depends(platform_admin)])
async def admin_mcp_servers(platform: Platform = Depends(get_platform)) -> list[dict]:
    spec = platform.registry.platform()
    used: dict[str, list[str]] = {}
    for team in spec.teams:
        for definition in platform.registry.definitions(team.id, "harnesses"):
            for server_id in (definition.spec or {}).get("mcp", []):
                used.setdefault(server_id, []).append(f"{team.id}/{definition.id}")
    items = []
    async with httpx.AsyncClient(timeout=5) as http:
        for server in spec.mcp_servers:
            try:
                response = await http.post(
                    server.url,
                    json={"jsonrpc": "2.0", "id": 1, "method": "ping"},
                    headers={"Accept": "application/json, text/event-stream"},
                )
                reachable, detail = True, f"HTTP {response.status_code}"
            except httpx.HTTPError as error:
                reachable, detail = False, type(error).__name__
            items.append(
                {
                    **server.model_dump(),
                    "reachable": reachable,
                    "detail": detail,
                    "harnesses": used.get(server.id, []),
                    "credential_ready": _platform_ready(platform, spec, server.credential),
                }
            )
    return items
