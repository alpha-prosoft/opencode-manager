"""Read-only Bitbucket Cloud tools over MCP.

The server holds no credentials: it forwards the Authorization header it receives,
which the platform's MCP gateway sets from the caller's (or the platform's) credential.
"""

import os

import httpx
import uvicorn
from mcp.server import MCPServer
from mcp.server.mcpserver import Context
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.transport_security import TransportSecuritySettings

API = os.environ.get("BITBUCKET_API", "https://api.bitbucket.org/2.0")
MAX_DIFF = 200_000

server = MCPServer("bitbucket", instructions="Read Bitbucket Cloud repositories and pull requests. Read-only.")


async def _get(ctx: Context, path: str, params: dict | None = None, raw: bool = False):
    authorization = (ctx.headers or {}).get("authorization")
    if not authorization:
        raise ToolError("No Bitbucket credential was provided")
    async with httpx.AsyncClient(base_url=API, timeout=30, follow_redirects=True) as http:
        response = await http.get(path, params=params, headers={"Authorization": authorization})
    if response.status_code in (401, 403):
        raise ToolError(f"Bitbucket refused the credential (HTTP {response.status_code})")
    if response.status_code == 404:
        raise ToolError("Not found, or the credential can't see it")
    response.raise_for_status()
    return response.text if raw else response.json()


@server.tool(name="whoami", description="The Bitbucket account the current credential belongs to.")
async def whoami(ctx: Context) -> dict:
    user = await _get(ctx, "/user")
    return {"display_name": user.get("display_name"), "nickname": user.get("nickname"), "account_id": user.get("account_id")}


@server.tool(name="list_pull_requests", description="Pull requests of a repository, newest first.")
async def list_pull_requests(workspace: str, repository: str, ctx: Context, state: str = "OPEN", limit: int = 20) -> list[dict]:
    data = await _get(ctx, f"/repositories/{workspace}/{repository}/pullrequests", {"state": state, "pagelen": min(limit, 50)})
    return [
        {
            "id": pr["id"],
            "title": pr["title"],
            "author": (pr.get("author") or {}).get("display_name"),
            "source": pr["source"]["branch"]["name"],
            "destination": pr["destination"]["branch"]["name"],
            "state": pr["state"],
            "updated_on": pr["updated_on"],
        }
        for pr in data.get("values", [])
    ]


@server.tool(name="get_pull_request", description="Details and description of one pull request.")
async def get_pull_request(workspace: str, repository: str, pull_request: int, ctx: Context) -> dict:
    pr = await _get(ctx, f"/repositories/{workspace}/{repository}/pullrequests/{pull_request}")
    return {
        "id": pr["id"],
        "title": pr["title"],
        "description": pr.get("description") or "",
        "author": (pr.get("author") or {}).get("display_name"),
        "source": pr["source"]["branch"]["name"],
        "destination": pr["destination"]["branch"]["name"],
        "state": pr["state"],
        "reviewers": [r.get("display_name") for r in pr.get("reviewers", [])],
    }


@server.tool(name="get_pull_request_diff", description="The unified diff of a pull request (truncated when very large).")
async def get_pull_request_diff(workspace: str, repository: str, pull_request: int, ctx: Context) -> str:
    diff = await _get(ctx, f"/repositories/{workspace}/{repository}/pullrequests/{pull_request}/diff", raw=True)
    return diff if len(diff) <= MAX_DIFF else diff[:MAX_DIFF] + "\n… diff truncated"


app = server.streamable_http_app(
    streamable_http_path="/",
    stateless_http=True,
    json_response=True,
    transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
)

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "8000")))
