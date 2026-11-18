# mcp-whoami

A mock MCP server that says back how it was called. The platform's MCP gateway adds a credential to every tool
call (a person's own value, their team's, the tenant's or the operator's); `whoami` shows which one arrived. The
example tenants use it to show which account a session's tools run as, and the end-to-end tests
(`service/test-e2e/manager_svc/e2e_credentials.clj`) use it to assert exactly which value the gateway chose, without
any outside service.

It holds no secrets and calls nothing. Streamable HTTP on port 8000, like the other example MCP servers.

## Accepted values

Only made-up values that start with `whoami-`, named after who they stand for: `whoami-tenant-acme`,
`whoami-team-demo`, `whoami-user-bob`. A basic credential needs both its username and its secret to start with
`whoami-`. Anything else is refused and never repeated in an answer, so a real secret typed by mistake stays private.

The credential is read from `Authorization` (`Bearer <token>`, `Basic <base64>`, or the bare value) or, without
it, from `X-Api-Key`, which are the header and schemes an MCP server's `auth` in tenant.json can configure.

## Tools

| Tool | Answer |
|---|---|
| `whoami` | `{"header", "scheme": "bearer" \| "basic" \| "raw", "token"}`, for basic `"username"` instead of the token and never the secret; a tool error without a credential or with one it does not accept |
| `echo` | `{"text"}`: the text it was given |
| `delete_account` | `{"deleted": false}`. No allow-list should include it: a call that arrives got past the gateway |

## HTTP endpoints

| Endpoint | Answer |
|---|---|
| `GET /check` | 200 with what `whoami` would say for an accepted credential, 401 otherwise: the target of a credential's `test.url` |
| `GET /calls` | `{"whoami": n, "echo": n, "delete_account": n}`: tool calls since the server started, so a test can prove a refused call never arrived |
