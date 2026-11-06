from types import SimpleNamespace

from mcp.server import MCPServer
from mcp.server.mcpserver import Context
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import Field
from temporalio.service import RPCError

from ..review_post import ReviewFinding, Verdict
from ..temporal.workflows import run_workflow_id
from .harness import verify_key
from .runner import RunError

state = SimpleNamespace(platform=None)

server = MCPServer("opencode-platform", instructions="Submit the result of the current platform activity.")


async def _submit(ctx: Context, tool: str, payload: dict) -> str:
    platform = state.platform
    headers = ctx.headers or {}
    session_id = verify_key(platform.signing_key, "mcp", headers.get("x-platform-key", ""))
    if session_id is None or session_id != headers.get("x-platform-session"):
        raise ToolError("This session is not allowed to submit results")
    record = platform.sessions.get(session_id)
    if record is None or not record.run_id:
        raise ToolError("This session does not belong to a platform run")
    try:
        submission = platform.runner.validate_submission(record.run_id, tool, payload)
    except RunError as error:
        raise ToolError(str(error)) from error
    try:
        handle = platform.engine.client.get_workflow_handle(run_workflow_id(record.run_id))
        await handle.signal("submit", {"tool": tool, "payload": submission})
    except RPCError as error:
        raise ToolError("No run is waiting for a result from this session; nothing was submitted") from error
    return "Result accepted. The platform takes it from here; you are done."


@server.tool(
    name="submit_review",
    description="Submit the finished code review. Call exactly once. The platform decides what is posted.",
)
async def submit_review(
    verdict: Verdict,
    summary: str,
    ctx: Context,
    findings: list[ReviewFinding] = Field(default=[]),
    checked: list[str] = Field(default=[]),
) -> str:
    payload = {"verdict": verdict, "summary": summary, "findings": [f.model_dump() for f in findings], "checked": checked}
    return await _submit(ctx, "submit_review", payload)


@server.tool(
    name="submit_implementation",
    description="Submit the finished implementation. Call exactly once after the changes are in the working tree.",
)
async def submit_implementation(
    title: str,
    summary: str,
    verified: bool,
    ctx: Context,
    changes: list[str] = Field(default=[]),
    tests: list[str] = Field(default=[]),
    notes: str = "",
) -> str:
    """title: short imperative title (max 120 characters). verified: true only if the tests or checks ran and passed."""
    payload = {"title": title, "summary": summary, "verified": verified, "changes": changes, "tests": tests, "notes": notes}
    return await _submit(ctx, "submit_implementation", payload)


@server.tool(name="submit_document", description="Submit the finished document as Markdown. Call exactly once.")
async def submit_document(title: str, markdown: str, ctx: Context) -> str:
    return await _submit(ctx, "submit_document", {"title": title, "markdown": markdown})


def mcp_app():
    return server.streamable_http_app(
        streamable_http_path="/",
        stateless_http=True,
        json_response=True,
        transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
    )
