import hmac
from types import SimpleNamespace

from mcp.server import MCPServer
from mcp.server.mcpserver import Context
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import Field
from temporalio.service import RPCError

from .review import REVIEW_TOOL
from .review_post import ReviewFinding, ReviewSubmission, Verdict
from .temporal.workflows import PullRequestSessionWorkflow, pull_request_workflow_id

state = SimpleNamespace(service=None, engine=None)

server = MCPServer(
    "opencode-manager-review",
    instructions="Submit the result of a pull request review to the review workflow.",
)


@server.tool(
    name=REVIEW_TOOL,
    description=(
        "Submit the finished code review. Call exactly once when the review is complete. "
        "Findings with a path and a line inside the diff may become inline comments; "
        "the workflow decides what is posted to the pull request."
    ),
)
async def submit_review(
    verdict: Verdict,
    summary: str,
    ctx: Context,
    findings: list[ReviewFinding] = Field(default=[]),
    checked: list[str] = Field(default=[]),
) -> str:
    headers = ctx.headers or {}
    record = state.service.get(headers.get("x-review-session", ""))
    if record is None or not hmac.compare_digest(record.secret_key, headers.get("x-review-key", "")):
        raise ToolError("This session is not allowed to submit reviews")
    if "pull-request" not in record.labels:
        raise ToolError("This session does not belong to a pull request review")

    review = ReviewSubmission(verdict=verdict, summary=summary, findings=findings, checked=checked)
    workflow_id = pull_request_workflow_id(record.labels["repository"].split("/")[-1], record.labels["pull-request"])
    handle = state.engine.client.get_workflow_handle(workflow_id)
    try:
        await handle.signal(PullRequestSessionWorkflow.submit_review, review.model_dump())
    except RPCError as error:
        raise ToolError("No review workflow is waiting for this session; nothing was submitted") from error
    return (
        f"Review accepted: {verdict} with {len(findings)} finding(s). "
        "The workflow decides what gets posted to the pull request; you are done."
    )


def mcp_app():
    return server.streamable_http_app(
        streamable_http_path="/",
        stateless_http=True,
        json_response=True,
        transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
    )
