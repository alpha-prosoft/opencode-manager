"""A mock MCP server that says back how it was called.

The platform's MCP gateway adds a credential to every call: a person's own value, their team's or the tenant's.
This server shows which one arrived, so a test (or a curious person) sees whose account a tool call runs as.

It accepts only made-up values that start with "whoami-" (for a basic credential: username and secret both),
such as whoami-tenant-acme, whoami-team-demo or whoami-user-bob, and never repeats a value it did not accept.
It holds no secrets and calls nothing; the only thing it keeps is how often each tool was called (GET /calls).
"""

import base64
import binascii
import os
from collections import Counter

import uvicorn
from mcp.server import MCPServer
from mcp.server.mcpserver import Context
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.transport_security import TransportSecuritySettings
from starlette.requests import Request
from starlette.responses import JSONResponse

PREFIX = "whoami-"
CREDENTIAL_HEADERS = ("authorization", "x-api-key")

calls = Counter()

server = MCPServer("whoami", instructions="Shows which account the platform calls tools as. A mock: nothing real is behind it.")


def read_credential(headers) -> dict | None:
    """{header, scheme, token} or {header, scheme, username, secret} of the first credential header, None without one."""
    lowered = {name.lower(): value for name, value in (headers or {}).items()}
    header = next((name for name in CREDENTIAL_HEADERS if lowered.get(name)), None)
    if header is None:
        return None
    scheme, _, rest = lowered[header].partition(" ")
    if scheme.lower() == "bearer" and rest:
        return {"header": header, "scheme": "bearer", "token": rest.strip()}
    if scheme.lower() == "basic" and rest:
        try:
            username, _, secret = base64.b64decode(rest.strip(), validate=True).decode("utf-8").partition(":")
        except (binascii.Error, UnicodeDecodeError):
            username, secret = "", ""
        return {"header": header, "scheme": "basic", "username": username, "secret": secret}
    return {"header": header, "scheme": "raw", "token": lowered[header]}


def accepted(credential: dict | None) -> bool:
    if credential is None:
        return False
    if credential["scheme"] == "basic":
        return credential["username"].startswith(PREFIX) and credential["secret"].startswith(PREFIX)
    return credential["token"].startswith(PREFIX)


def identity(credential: dict) -> dict:
    """What whoami tells of an accepted credential: never a basic credential's secret."""
    return {key: value for key, value in credential.items() if key != "secret"}


@server.tool(name="whoami", description="How this call was authenticated: the header, the scheme and the token or username that arrived.")
async def whoami(ctx: Context) -> dict:
    calls["whoami"] += 1
    credential = read_credential(ctx.headers)
    if credential is None:
        raise ToolError("No credential was provided")
    if not accepted(credential):
        raise ToolError(f"whoami refused the credential: it accepts only values starting with {PREFIX}")
    return identity(credential)


@server.tool(name="echo", description="Returns the text it was given.")
async def echo(text: str) -> dict:
    calls["echo"] += 1
    return {"text": text}


@server.tool(name="delete_account", description="Deletes nothing. No allow-list should include this tool, so a call that arrives here got past the gateway.")
async def delete_account() -> dict:
    calls["delete_account"] += 1
    return {"deleted": False}


@server.custom_route("/check", methods=["GET"])
async def check(request: Request) -> JSONResponse:
    """The target of a credential's test.url: 200 for a value whoami accepts, 401 for anything else."""
    credential = read_credential(request.headers)
    if accepted(credential):
        return JSONResponse(identity(credential))
    return JSONResponse({"detail": f"Only values starting with {PREFIX} are accepted"}, status_code=401)


@server.custom_route("/calls", methods=["GET"])
async def call_counts(_request: Request) -> JSONResponse:
    """How often each tool was called since the server started."""
    return JSONResponse({"whoami": 0, "echo": 0, "delete_account": 0} | dict(calls))


app = server.streamable_http_app(
    streamable_http_path="/",
    stateless_http=True,
    json_response=True,
    transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
)

if __name__ == "__main__":
    uvicorn.run(app, host=os.environ.get("HOST", "0.0.0.0"), port=int(os.environ.get("PORT", "8000")))
