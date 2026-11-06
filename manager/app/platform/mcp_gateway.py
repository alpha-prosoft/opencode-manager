"""MCP gateway: sandboxes reach MCP servers only through here; credentials are added on the way out."""

import json
import logging
import uuid

import httpx
from fastapi import APIRouter, HTTPException, Request, status
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse, Response, StreamingResponse
from starlette.background import BackgroundTask

from .credentials import authorization
from .harness import verify_key
from .runner import PLATFORM_ACTOR

log = logging.getLogger(__name__)

router = APIRouter(prefix="/mcp-gw", include_in_schema=False)
KEY_PURPOSE = "tools"
REQUEST_HEADERS = ("content-type", "accept", "mcp-session-id", "mcp-protocol-version", "last-event-id")
RESPONSE_HEADERS = ("content-type", "mcp-session-id")
TIMEOUT = httpx.Timeout(connect=10, read=300, write=60, pool=10)


def _messages(body: bytes) -> list[dict]:
    if not body:
        return []
    try:
        parsed = json.loads(body)
    except ValueError:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "MCP requests must be JSON-RPC") from None
    return [m for m in (parsed if isinstance(parsed, list) else [parsed]) if isinstance(m, dict)]


def _error(messages: list[dict], message: str) -> JSONResponse:
    errors = [{"jsonrpc": "2.0", "id": m.get("id"), "error": {"code": -32001, "message": message}} for m in messages if "id" in m]
    return JSONResponse(errors[0] if len(errors) == 1 else errors, status_code=200 if errors else 403)


def _filter_tools(payload: dict, allowed: set[str]) -> dict:
    tools = (payload.get("result") or {}).get("tools")
    if isinstance(tools, list):
        payload["result"]["tools"] = [tool for tool in tools if tool.get("name") in allowed]
    return payload


def _filter_body(body: bytes, content_type: str, allowed: set[str]) -> bytes:
    if "application/json" in content_type:
        parsed = json.loads(body)
        parsed = [_filter_tools(p, allowed) for p in parsed] if isinstance(parsed, list) else _filter_tools(parsed, allowed)
        return json.dumps(parsed).encode()
    lines = []
    for line in body.decode().split("\n"):
        if line.startswith("data:"):
            try:
                line = "data: " + json.dumps(_filter_tools(json.loads(line[5:]), allowed))
            except ValueError:
                pass
        lines.append(line)
    return "\n".join(lines).encode()


@router.api_route("/{server_id}", methods=["GET", "POST", "DELETE"])
@router.api_route("/{server_id}/", methods=["GET", "POST", "DELETE"])
async def proxy(server_id: str, request: Request):
    platform = request.app.state.platform
    key = request.headers.get("x-platform-key", "")
    session_id = verify_key(platform.signing_key, KEY_PURPOSE, key) if key else None
    sandbox = platform.storage.load_sandbox(session_id) if session_id else None
    record = platform.sessions.get(session_id) if session_id else None
    if sandbox is None or record is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid gateway key")
    if server_id not in sandbox.mcp_servers:
        raise HTTPException(status.HTTP_403_FORBIDDEN, f"This harness may not use MCP server {server_id}")
    if record.locked_at:
        raise HTTPException(status.HTTP_423_LOCKED, "This session is locked")
    server = platform.registry.platform().mcp_server(server_id)
    if server is None or (server.teams != "*" and sandbox.team_id not in server.teams):
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"MCP server {server_id} is not available")

    body = await request.body()
    messages = _messages(body) if request.method == "POST" else []
    calls = [m for m in messages if m.get("method") == "tools/call"]
    allowed = set(server.tools) if server.tools is not None else None
    if allowed is not None:
        blocked = [c for c in calls if (c.get("params") or {}).get("name") not in allowed]
        if blocked:
            return _error(messages, f"Tool {(blocked[0].get('params') or {}).get('name')} is not allowed on {server.name}")

    headers = {name: request.headers[name] for name in REQUEST_HEADERS if name in request.headers}
    source = None
    if server.credential:
        user = None if record.sub.startswith(PLATFORM_ACTOR) else record.sub
        resolved = await run_in_threadpool(platform.credentials.resolve, server.credential, user)
        if resolved is None:
            message = f"{server.name} needs your {server.credential} credential. Add it under Accounts in the platform."
            if messages:
                return _error(messages, message)
            raise HTTPException(status.HTTP_403_FORBIDDEN, message)
        headers[server.auth.header] = authorization(server.auth.scheme, resolved.value)
        source = resolved.source

    for call in calls:
        params = call.get("params") or {}
        payload = {"server": server.id, "tool": params.get("name"), "arguments": params.get("arguments"), "credential": server.credential, "source": source}
        await run_in_threadpool(platform.audit.append, record.id, "mcp", f"mcp:{uuid.uuid4().hex}", payload)

    client = httpx.AsyncClient(timeout=TIMEOUT)
    try:
        upstream = await client.send(client.build_request(request.method, server.url, content=body, headers=headers), stream=True)
    except httpx.HTTPError as error:
        await client.aclose()
        log.warning("MCP server %s unreachable: %s", server.id, error)
        if messages:
            return _error(messages, f"{server.name} is unreachable right now")
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"{server.name} is unreachable") from error

    out_headers = {name: upstream.headers[name] for name in RESPONSE_HEADERS if name in upstream.headers}
    if allowed is not None and any(m.get("method") == "tools/list" for m in messages):
        content = await upstream.aread()
        await upstream.aclose()
        await client.aclose()
        return Response(_filter_body(content, upstream.headers.get("content-type", ""), allowed), upstream.status_code, out_headers)

    async def close():
        await upstream.aclose()
        await client.aclose()

    return StreamingResponse(upstream.aiter_raw(), upstream.status_code, out_headers, background=BackgroundTask(close))
