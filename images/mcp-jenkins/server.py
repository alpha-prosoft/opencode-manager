"""Read-only Jenkins tools over MCP.

The server holds no credentials: it forwards the Authorization header it receives,
which the platform's MCP gateway sets from the platform's (or the caller's) credential.
JENKINS_URL is the only configuration.
"""

import os
from urllib.parse import quote

import httpx
import uvicorn
from mcp.server import MCPServer
from mcp.server.mcpserver import Context
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.transport_security import TransportSecuritySettings

JENKINS = os.environ.get("JENKINS_URL", "").rstrip("/")
LOG_LIMIT = 20_000

server = MCPServer("jenkins", instructions="Read Jenkins jobs, builds, pipeline stages and logs. Read-only: nothing can be started or changed.")


def job_path(job: str) -> str:
    """'folder/job/branch' -> '/job/folder/job/job/job/branch'"""
    return "".join(f"/job/{quote(part, safe='')}" for part in job.strip("/").split("/") if part)


async def _get(ctx: Context, path: str, params: dict | None = None, text: bool = False):
    if not JENKINS:
        raise ToolError("JENKINS_URL is not configured on the Jenkins MCP server")
    authorization = (ctx.headers or {}).get("authorization")
    if not authorization:
        raise ToolError("No Jenkins credential was provided")
    async with httpx.AsyncClient(timeout=30) as http:
        response = await http.get(JENKINS + path, params=params, headers={"Authorization": authorization})
    if response.is_redirect and "login" in response.headers.get("location", "").lower():
        raise ToolError("Jenkins sent the request to its login page: the token was not accepted. Sign in to Jenkins once in a browser, then check the user name and token.")
    if response.status_code in (401, 403):
        raise ToolError(f"Jenkins refused the credential (HTTP {response.status_code})")
    if response.status_code == 404:
        raise ToolError("Not found: check the job path (folders separated by /) and build number")
    response.raise_for_status()
    return response.text if text else response.json()


def _build(build: dict) -> dict:
    return {
        "number": build.get("number"),
        "result": build.get("result") or ("RUNNING" if build.get("building") else None),
        "building": build.get("building"),
        "started": build.get("timestamp"),
        "duration_ms": build.get("duration"),
        "url": build.get("url"),
        "description": build.get("description"),
        "causes": [c.get("shortDescription") for a in build.get("actions", []) for c in (a or {}).get("causes", []) or []],
    }


@server.tool(name="whoami", description="The Jenkins user the current credential belongs to.")
async def whoami(ctx: Context) -> dict:
    me = await _get(ctx, "/me/api/json", {"tree": "id,fullName"})
    return {"id": me.get("id"), "name": me.get("fullName")}


@server.tool(name="list_jobs", description="Jobs (and branches of multibranch jobs) inside a folder; empty folder lists the top level.")
async def list_jobs(ctx: Context, folder: str = "") -> list[dict]:
    data = await _get(ctx, job_path(folder) + "/api/json", {"tree": "jobs[name,_class,color,url]"})
    return [{"name": j["name"], "type": j["_class"].rsplit(".", 1)[-1], "status": j.get("color")} for j in data.get("jobs", [])]


@server.tool(name="get_builds", description="Recent builds of a job, newest first. For a multibranch pipeline use 'job/branch'.")
async def get_builds(job: str, ctx: Context, limit: int = 10) -> list[dict]:
    tree = f"builds[number,result,building,timestamp,duration,url,description,actions[causes[shortDescription]]]{{0,{min(limit, 50)}}}"
    data = await _get(ctx, job_path(job) + "/api/json", {"tree": tree})
    return [_build(b) for b in data.get("builds", [])]


@server.tool(name="get_build", description="One build: result, duration and pipeline stages. number may be a build number or 'lastBuild'.")
async def get_build(job: str, ctx: Context, number: str = "lastBuild") -> dict:
    path = job_path(job) + f"/{quote(str(number), safe='')}"
    build = _build(await _get(ctx, path + "/api/json"))
    try:
        stages = (await _get(ctx, path + "/wfapi/describe")).get("stages", [])
        build["stages"] = [{"name": s["name"], "status": s["status"], "duration_ms": s.get("durationMillis")} for s in stages]
    except (ToolError, httpx.HTTPError):
        build["stages"] = None
    return build


@server.tool(name="get_build_log", description="The end of a build's console log.")
async def get_build_log(job: str, ctx: Context, number: str = "lastBuild", tail_lines: int = 200) -> str:
    text = await _get(ctx, job_path(job) + f"/{quote(str(number), safe='')}/consoleText", text=True)
    lines = text.splitlines()[-max(1, min(tail_lines, 2000)) :]
    return "\n".join(lines)[-LOG_LIMIT:]


app = server.streamable_http_app(
    streamable_http_path="/",
    stateless_http=True,
    json_response=True,
    transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
)

if __name__ == "__main__":
    uvicorn.run(app, host=os.environ.get("HOST", "0.0.0.0"), port=int(os.environ.get("PORT", "8000")))
