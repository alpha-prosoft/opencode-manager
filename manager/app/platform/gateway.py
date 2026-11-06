import json
import logging
import time
from datetime import UTC, datetime

import httpx
from fastapi import APIRouter, HTTPException, Request, status
from fastapi.responses import JSONResponse, StreamingResponse

from .harness import verify_key

log = logging.getLogger(__name__)

router = APIRouter(prefix="/llm/v1", include_in_schema=False)
UPSTREAM_TIMEOUT = httpx.Timeout(connect=10, read=600, write=60, pool=10)


def _context(request: Request):
    platform = request.app.state.platform
    header = request.headers.get("authorization", "")
    key = header[7:] if header.lower().startswith("bearer ") else ""
    session_id = verify_key(platform.signing_key, "llm", key) if key else None
    sandbox = platform.storage.load_sandbox(session_id) if session_id else None
    if sandbox is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid gateway key")
    connection = next((c for c in platform.registry.platform().llm_connections if c.id == sandbox.llm_connection), None)
    if connection is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, f"LLM connection {sandbox.llm_connection} is not configured")
    return platform, session_id, sandbox, connection


@router.get("/models")
def models(request: Request) -> dict:
    _, _, sandbox, _ = _context(request)
    return {"object": "list", "data": [{"id": m, "object": "model", "owned_by": "platform"} for m in sandbox.llm_models]}


def _usage(payload: dict) -> tuple[int, int, int]:
    usage = payload.get("usage") or {}
    cached = (usage.get("prompt_tokens_details") or {}).get("cached_tokens") or 0
    return usage.get("prompt_tokens") or 0, usage.get("completion_tokens") or 0, cached


@router.post("/chat/completions")
async def chat_completions(request: Request):
    platform, session_id, sandbox, connection = _context(request)
    body = await request.json()
    model = body.get("model")
    if model not in sandbox.llm_models:
        raise HTTPException(status.HTTP_403_FORBIDDEN, f"Model {model} is not allowed for this session")
    team = platform.registry.team(sandbox.team_id)
    if team and team.budget.daily_tokens:
        midnight = datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0).isoformat()
        if platform.usage.tokens_since(sandbox.team_id, midnight) >= team.budget.daily_tokens:
            raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, f"Team {sandbox.team_id} has used its daily token budget")
    streaming = bool(body.get("stream"))
    if streaming:
        body["stream_options"] = {**(body.get("stream_options") or {}), "include_usage": True}

    secret = platform.secrets.get(connection.secret)
    if not secret:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, f"Secret {connection.secret} is not configured")
    headers = {"Authorization": f"Bearer {secret}", "Content-Type": "application/json", "Accept": request.headers.get("accept", "*/*")}
    started = time.monotonic()
    client = httpx.AsyncClient(timeout=UPSTREAM_TIMEOUT)
    try:
        upstream = await client.send(
            client.build_request("POST", f"{connection.upstream()}/chat/completions", json=body, headers=headers), stream=True
        )
    except httpx.HTTPError as error:
        await client.aclose()
        raise HTTPException(
            status.HTTP_502_BAD_GATEWAY, f"Could not reach LLM connection {connection.id}: {type(error).__name__}: {error}"
        ) from error

    def record(prompt: int, completion: int, cached: int) -> None:
        platform.usage.record(
            session_id=session_id,
            run_id=sandbox.run_id,
            team_id=sandbox.team_id,
            connection=connection.id,
            model=model,
            status=upstream.status_code,
            prompt=prompt,
            completion=completion,
            cached=cached,
            latency_ms=int((time.monotonic() - started) * 1000),
        )

    if not streaming or upstream.status_code >= 400:
        content = await upstream.aread()
        await upstream.aclose()
        await client.aclose()
        try:
            payload = json.loads(content)
        except ValueError:
            payload = {"error": {"message": content.decode(errors="replace")[:2000]}}
        record(*_usage(payload) if upstream.status_code < 400 else (0, 0, 0))
        return JSONResponse(payload, status_code=upstream.status_code)

    async def relay():
        totals = (0, 0, 0)
        buffer = b""
        try:
            async for chunk in upstream.aiter_raw():
                yield chunk
                buffer += chunk
                *lines, buffer = buffer.split(b"\n")
                for line in lines:
                    if line.startswith(b"data: {") and b'"usage"' in line:
                        try:
                            usage = _usage(json.loads(line[6:]))
                        except ValueError:
                            continue
                        if any(usage):
                            totals = usage
        finally:
            await upstream.aclose()
            await client.aclose()
            record(*totals)

    return StreamingResponse(relay(), status_code=upstream.status_code, media_type=upstream.headers.get("content-type"))
