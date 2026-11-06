"""Calls tools on MCP servers from the platform itself (credential checks, following builds)."""

import json

import httpx

from .credentials import authorization
from .spec import McpServerSpec


class ToolFailed(Exception):
    pass


def call_tool(server: McpServerSpec, credential: dict | None, tool: str, arguments: dict, timeout: float = 30):
    """Call `tool` and return its result: parsed JSON when the tool returned JSON, otherwise text."""
    headers = {"Accept": "application/json, text/event-stream"}
    if credential is not None:
        headers[server.auth.header] = authorization(server.auth.scheme, credential)
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": tool, "arguments": arguments}}
    try:
        response = httpx.post(server.url, json=body, headers=headers, timeout=timeout)
        response.raise_for_status()
        payload = response.json()
    except (httpx.HTTPError, ValueError) as error:
        raise ToolFailed(f"could not reach the {server.name} MCP server ({type(error).__name__})") from error
    if "error" in payload:
        raise ToolFailed(payload["error"].get("message", "the MCP server returned an error"))
    result = payload.get("result") or {}
    text = "".join(part.get("text", "") for part in result.get("content", []) if part.get("type") == "text")
    if result.get("isError"):
        raise ToolFailed(text or f"{tool} failed on {server.name}")
    if "structuredContent" in result:
        structured = result["structuredContent"]
        return structured.get("result", structured) if isinstance(structured, dict) and set(structured) == {"result"} else structured
    try:
        return json.loads(text)
    except ValueError:
        return text
