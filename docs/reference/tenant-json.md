# tenant.json

This page is for tenant admins. It lists every field of `tenant.json`, the file at the root of a tenant's [onboarding repository](../index.md#terms), with its type, default and an example. For a guided build-up of the file, read [Onboard a tenant](../story/tenant.md).

- JSON Schema: [schemas/tenant.schema.json](https://github.com/alpha-prosoft/opencode-manager/blob/main/schemas/tenant.schema.json). Add `"$schema": "<url>"` to the file for editor validation; the platform ignores that key.
- Complete example: [examples/tenants/acme/onboarding/tenant.json](https://github.com/alpha-prosoft/opencode-manager/blob/main/examples/tenants/acme/onboarding/tenant.json).
- Unknown fields are refused at every level.
- The file is read at each sync. An invalid file keeps the tenant's last good configuration, and the sync status names the problem.

## Top level

| Field | Type | Default | Meaning |
|---|---|---|---|
| `version` | integer | `1` | Must be `1`. |
| `allowed_images` | list of strings | `["ghcr.io/anomalyco/opencode:1.19.5"]` | The only container images harnesses may use. |
| `llm_connections` | list of [LLM connections](#llm_connections) | `[]` | Upstream LLM endpoints. |
| `credentials` | list of [credentials](#credentials) | `[]` | Credentials people and admins fill in. |
| `mcp_servers` | list of [MCP servers](#mcp_servers) | `[]` | Tool servers run behind the platform's gateway. |
| `secret_hosts` | object: secret name → list of hosts | `{}` | For each secret, the hosts it may be sent to in Git checkouts. |
| `teams` | list of [teams](#teams) | `[]` | The tenant's teams and their repositories. |

A minimal valid file:

```json
{
  "version": 1,
  "llm_connections": [
    { "id": "copilot", "type": "github-copilot", "secret": "COPILOT_TOKEN", "models": ["claude-sonnet-5.5"] }
  ],
  "teams": [
    { "id": "demo", "name": "Demo team", "repository": { "url": "https://git.example.com/acme/demo-agents.git" } }
  ]
}
```

### Identifiers

| Kind | Pattern | Examples |
|---|---|---|
| Ids of teams, LLM connections and MCP servers | `^[a-z0-9][a-z0-9-]{0,62}$`: lowercase letters, digits and dashes | `demo`, `copilot`, `bitbucket` |
| Names of credentials and secrets | `^[A-Z][A-Z0-9_]{0,63}$`: uppercase letters, digits and underscores | `BITBUCKET_TOKEN` |

Ids must be unique within their list. A team id can't be a page name of the UI: `accounts`, `activities`, `admin`, `api`, `harnesses`, `login`, `platform`, `projects`, `runs`, `sessions`, `setup`, `teams`, `webhooks`.

## llm_connections

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | id | required | How harnesses and `team.json` refer to the connection. |
| `type` | `openai-compatible` or `github-copilot` | `openai-compatible` | The kind of upstream. |
| `base_url` | string | per type | The upstream base URL. An `openai-compatible` connection needs one, but the sync doesn't check that: without it the file is accepted and every call through the connection fails with 503 *LLM connection … needs base_url*. |
| `secret` | secret name | required | The secret holding the API key or OAuth token. |
| `models` | list of strings, at least one | required | The models teams may use through this connection. |
| `teams` | `"*"` or list of team ids | `"*"` | Which teams may use the connection. |
| `pricing` | object: model → [price](#pricing) | `{}` | Prices per model, for cost estimates. |

| `type` | Upstream | Secret |
|---|---|---|
| `openai-compatible` | `base_url`, for example `https://my-azure.openai.azure.com/openai/v1`, vLLM or LiteLLM | An API key, sent as `Authorization: Bearer` |
| `github-copilot` | `https://api.githubcopilot.com` | A GitHub OAuth token (`gho_…`) from `opencode auth login` |

The secret of an LLM connection is a tenant value only. If you also declare it under `credentials`, its `provided_by` must be `["tenant"]`.

### pricing

| Field | Type | Default | Meaning |
|---|---|---|---|
| `input` | number ≥ 0 | required | Price per million prompt tokens. |
| `output` | number ≥ 0 | required | Price per million completion tokens. |
| `cached_input` | number | none | Price per million cached prompt tokens. |

```json
"pricing": {
  "claude-sonnet-5.5": { "input": 3.0, "output": 15.0, "cached_input": 0.3 },
  "gemini-3.5-flash": { "input": 0.3, "output": 2.5 }
}
```

## credentials

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | secret name | required | Referred to by MCP servers, repositories, triggers and projects. |
| `name` | string, 1 to 80 characters | required | Shown to people. |
| `description` | string | `""` | Shown to people. |
| `kind` | `token`, `basic` or `ssh_key` | `token` | `token`: one secret. `basic`: a username and a password. `ssh_key`: a private key. |
| `hosts` | list of strings | `[]` | The only hosts the value may be sent to. |
| `provided_by` | list of `tenant`, `team`, `user`; at least one, none repeated | `["tenant"]` | Who may provide the value. See [Credentials](credentials.md). |
| `teams` | `"*"` or list of team ids | `"*"` | Which teams the credential applies to. |
| `help` | string | `""` | Shown when someone enters the value, for example where to create a token. |
| `required` | boolean | `false` | Every person in the teams the credential applies to must add their own value. Needs `user` in `provided_by`. |
| `test` | [test](#test) | none | How a value is checked when it is saved. |

```json
{
  "id": "PAGERDUTY_TOKEN",
  "name": "PagerDuty",
  "description": "Your PagerDuty API key, so on-call tools act as you.",
  "kind": "token",
  "hosts": ["api.pagerduty.com"],
  "provided_by": ["user"],
  "required": true,
  "teams": ["ops"],
  "help": "Create a user API key in PagerDuty → My Profile → User Settings → API Access."
}
```

### test

Set exactly one of the two fields.

| Field | Type | Meaning |
|---|---|---|
| `url` | string | An endpoint called with the credential. A 2xx answer means the value works; any other answer, or a redirect to a login page, rejects it. |
| `mcp_tool` | string | A tool, called without arguments, of the MCP server that uses this credential. The call goes through that server, so it also works for services only the server can reach. |

```json
"test": { "url": "https://api.bitbucket.org/2.0/user" }
```

```json
"test": { "mcp_tool": "whoami" }
```

## mcp_servers

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | id | required | How harnesses and `team.json` refer to the server. |
| `name` | string, 1 to 80 characters | required | Shown to people. |
| `description` | string | `""` | Shown to people. |
| `url` | `http://` or `https://` URL | required | The server's Streamable HTTP endpoint. Only the manager needs to reach it. |
| `credential` | secret name | none | The credential the gateway adds to every request. It must be declared under `credentials`, and its `hosts` must include the host of `url`. |
| `auth.header` | string | `Authorization` | The header the credential is sent in. |
| `auth.scheme` | `bearer`, `basic` or `raw` | `bearer` | How the credential is written into the header. |
| `tools` | list of strings | all tools | Allow-list of tool names. Other tools are hidden from the agent and refused when called. |
| `teams` | `"*"` or list of team ids | `"*"` | Which teams may use the server. |

```json
{
  "id": "jenkins",
  "name": "Jenkins (read-only)",
  "description": "Jobs, builds, pipeline stages and logs from Jenkins.",
  "url": "http://mcp-jenkins:8000/",
  "credential": "JENKINS_TOKEN",
  "auth": { "scheme": "basic" },
  "tools": ["whoami", "list_jobs", "get_builds", "get_build", "get_build_log"]
}
```

MCP servers that only speak stdio can be wrapped as an HTTP service.

## secret_hosts

```json
"secret_hosts": {
  "GITHUB_TOKEN": ["github.com"]
}
```

A secret used for a Git checkout is sent only to the hosts listed for it here and, if it is also declared under `credentials`, in that credential's `hosts`, whatever a team repository asks for. Use `secret_hosts` for secrets that are not declared as credentials.

## teams

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | id | required | The team's id, as used in identity provider groups and URLs. |
| `name` | string | required | Shown to people. |
| `description` | string | `""` | Shown to people. |
| `repository` | a [source](#sources) | required | Where the team's definitions live. |
| `secrets` | list of secret names | `[]` | Grants the team secrets that are **not** declared under `credentials` (operator secrets). A declared credential is granted through its own `teams` instead. |
| `notifications.webhook` | secret name | none | A secret holding a webhook URL (for example a Slack incoming webhook). The platform sends `{"text": …, "run": …}` to it when a run ends. |
| `notifications.on` | list of `completed`, `failed` | `["completed", "failed"]` | Which outcomes are notified. |

```json
{
  "id": "payments",
  "name": "Payments",
  "description": "Payment services.",
  "repository": {
    "type": "git",
    "url": "https://bitbucket.org/acme/payments-agents.git",
    "branch": "main",
    "credentials": "BITBUCKET_TOKEN",
    "username": "x-bitbucket-api-token-auth"
  },
  "secrets": ["BITBUCKET_TOKEN"],
  "notifications": { "webhook": "PAYMENTS_SLACK_WEBHOOK", "on": ["failed"] }
}
```

A team removed from the list disappears from the platform; its history stays.

## Sources

A team's `repository` is one of three source types. A tenant's onboarding source in the server configuration can be any of them too.

### git (default)

| Field | Type | Default | Meaning |
|---|---|---|---|
| `type` | `git` | `git` | May be left out. |
| `url` | string | required | An `https://` URL, an ssh URL (`git@host:org/repo.git`) or a `file://` URL under an allowed local root. |
| `branch` | string | `main` | The branch read. |
| `credentials` | secret name | none | A token for https URLs (sent as Basic auth), a private key for ssh URLs. |
| `username` | string | `x-token-auth` | The username sent with the token. |
| `path` | string | `.platform` | The directory inside the repository with the definitions. |

### url

| Field | Type | Default | Meaning |
|---|---|---|---|
| `type` | `url` | required | |
| `url` | `https://` URL | required | A `.tar.gz` or `.zip` archive, at most 50 MB. A single top-level folder is stripped; entries that would escape the directory are rejected. |
| `credentials` | secret name | none | Sent as a Bearer token. |
| `path` | string | `.platform` | The directory inside the archive with the definitions. |

```json
{ "type": "url", "url": "https://artifacts.acme.com/agents.tar.gz", "credentials": "ARTIFACT_TOKEN" }
```

### path

| Field | Type | Default | Meaning |
|---|---|---|---|
| `type` | `path` | required | |
| `location` | string | required | An absolute directory on the platform host, under `MANAGER_LOCAL_REPO_ROOTS`. It is read in place; its version is a hash of its contents. |
| `path` | string | `.platform` | The directory inside `location` with the definitions. |

```json
{ "type": "path", "location": "/repos/payments" }
```

Plain `http://` Git URLs are refused unless the operator lists their host in `MANAGER_HTTP_GIT_HOSTS`.

## Checks across fields

Besides the per-field rules, a sync refuses the file with one of these messages:

| Message | Fix |
|---|---|
| `duplicate team ids: […]` (also `llm connection`, `credential`, `MCP server`) | Make the ids unique. |
| `team id … names a page of the UI (/<tenant>/…); choose another id.` | Rename the team. |
| `MCP server … uses undeclared credential …` | Declare the credential under `credentials`. |
| `MCP server …: credential … may not be sent to … (add it to its hosts)` | Add the host of the server's `url` to the credential's `hosts`. |
| `credential … is used by an LLM connection and must be provided_by ["tenant"]` | Remove `team` and `user` from its `provided_by`. |
| `credential … is required of people but they can't provide it: add "user" to its provided_by` | Add `user`, or remove `required`. |
| `credentials.<n>.test: set exactly one of url or mcp_tool` | Keep one of the two. |
| `tenant.json: not found at the root of the onboarding source` | Put the file at the root (or at the source's `path`). |
