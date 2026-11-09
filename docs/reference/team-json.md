# team.json

This page is for team admins. It lists every field of `team.json`, the team's own configuration in its repository, with type, default and an example. For a guided introduction, read [Set up a team](../story/team.md).

- Location: `.platform/team.json` in the team repository (the directory is the `path` of the team's `repository` in `tenant.json`).
- JSON Schema: [schemas/team.schema.json](https://github.com/alpha-prosoft/opencode-manager/blob/main/schemas/team.schema.json). Add `"$schema": "<url>"` for editor validation; the platform ignores that key.
- Examples: [Demo team](https://github.com/alpha-prosoft/opencode-manager/blob/main/examples/tenants/acme/teams/demo/.platform/team.json), [Payments](https://github.com/alpha-prosoft/opencode-manager/blob/main/examples/tenants/acme/teams/payments/.platform/team.json), [Operations](https://github.com/alpha-prosoft/opencode-manager/blob/main/examples/tenants/acme/teams/ops/.platform/team.json).
- Unknown fields are refused.
- A missing file is an empty configuration: no limits, and everything the tenant grants the team. An invalid file fails the team's sync; the last good one stays in use and the error shows on the Admin page and the team's page.

A team can only choose from what the tenant's `tenant.json` offers and grants it.

## Top level

| Field | Type | Default | Meaning |
|---|---|---|---|
| `version` | `1` | `1` | Must be `1`. |
| `budget` | [budget](#budget) | no limits | Token budget, session limit and idle timeout. |
| `llm_connections` | list of [connection choices](#llm_connections) | every connection the tenant grants the team | The tenant's LLM connections, and models, the team uses. An empty list means none. |
| `mcp_servers` | list of MCP server ids | every server the tenant grants the team | The tenant's MCP servers the team uses. An empty list means none. |
| `projects` | list of [projects](#projects) | `[]` | Source repositories and CI jobs the team works on. |
| `required_credentials` | list of credential ids | `[]` | Credentials each member must add their own value of. |

```json
{
  "version": 1,
  "budget": { "daily_tokens": 5000000, "max_running_sessions": 10, "idle_minutes": 30 },
  "llm_connections": [{ "id": "copilot", "models": ["claude-sonnet-5.5"] }],
  "mcp_servers": ["bitbucket", "jenkins"],
  "projects": [
    {
      "id": "demo-service",
      "name": "Demo service",
      "branch": "main",
      "scm": {
        "provider": "gerrit",
        "url": "http://gerrit:8080",
        "web_url": "http://gerrit.localhost:8080",
        "repository": "demo-service",
        "credentials": "GERRIT_TOKEN"
      },
      "ci": { "mcp_server": "jenkins", "jobs": ["demo-service-verify"], "job": "demo-service-verify" }
    }
  ],
  "required_credentials": ["JIRA_TOKEN"]
}
```

## budget

All three are optional; leaving one out means no limit of that kind.

| Field | Type | Meaning |
|---|---|---|
| `daily_tokens` | integer, at least 1000 | Prompt plus completion tokens per UTC day. Once reached, the LLM gateway answers `429`: *Team demo has used its daily token budget*. |
| `max_running_sessions` | integer, 1 to 1000 | Sessions running at the same time. See below. |
| `idle_minutes` | integer, 1 to 1440 | The team's sessions are stopped after this many idle minutes. It caps the `idle_minutes` of every harness of the team. |

What happens at `max_running_sessions`, with the message *Team demo already has 10 running sessions (limit 10). Stop one and retry.*:

| Who starts | Action | Effect |
|---|---|---|
| A person | Starting a workflow, starting a team session, forking a session, running a run again | Refused at once with `422`. |
| A person | Restarting a stopped session | Refused with `422` when the team's other running sessions fill the limit. |
| The platform (schedule, pull request trigger, webhook) | Starting a run | The run is accepted. It fails with the same message when the platform tries to create its session. |
| The platform | Restarting a session | Not limited. |

## llm_connections

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | id | required | An LLM connection of the tenant that is granted to the team. |
| `models` | list of strings, at least one | all models the connection offers | The models the team may use. |

```json
"llm_connections": [
  { "id": "copilot", "models": ["claude-sonnet-5.5", "gemini-3.5-flash"] }
]
```

Sync messages for this field are listed [below](#sync-messages).


## projects

A project is a source repository with its CI job. Workflows refer to it with `"project": "<id>"` and take from it what they leave out (see [Workflows, agents and harnesses](definitions.md)). Each project has a page in the UI at `/<tenant>/<team>/<project>`.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | id | required | How workflows refer to the project. Unique within the team. |
| `name` | string | the id | Shown to people. |
| `branch` | string | `main` | The main branch. |
| `scm` | [scm](#scm) | required | Where the repository and its pull requests live. |
| `ci` | [ci](#ci) | none | The CI system and jobs. |

### scm

| Field | Type | Default | Meaning |
|---|---|---|---|
| `provider` | `bitbucket`, `github` or `gerrit` | required | The hosting provider. |
| `repository` | string | required | `<workspace>/<repository>` for Bitbucket and GitHub; the project name for Gerrit. |
| `url` | `http(s)://` URL | none | The base URL of a self-hosted provider, for example `http://gerrit:8080`. Required for `gerrit`; not allowed for `bitbucket` and `github`. |
| `web_url` | `http(s)://` URL | `url` | Where browsers reach the provider, for links to changes. |
| `credentials` | credential id | none | A credential granted to the team. |

### ci

| Field | Type | Default | Meaning |
|---|---|---|---|
| `mcp_server` | MCP server id | required | The tenant's MCP server of the CI system. |
| `jobs` | list of strings | `[]` | The project's jobs. |
| `job` | string | none | The job that builds the project's changes. Implementation workflows of the project follow it. With `{pr}` in the name it is a job per pull request; without, a shared job. |

## required_credentials

```json
"required_credentials": ["JIRA_TOKEN"]
```

Each member of the team must add their **own** value of every listed credential before they can use the platform. A team or tenant value is still used for calls where there is no personal one, but it doesn't satisfy the requirement. The rules are in [Credentials](credentials.md).

Each id must be declared in `tenant.json` with `user` in its `provided_by`, and granted to the team.

Sync messages for this field are listed [below](#sync-messages).


## Sync messages

Besides the per-field rules above, a sync refuses `team.json` with one of these messages. The team then keeps its last good configuration.

| Message | Fix |
|---|---|
| `llm_connections: connection … is not configured for the tenant` | Use an id from `tenant.json`. |
| `llm_connections: the tenant does not grant connection … to team …` | The tenant admin adds the team to the connection's `teams`. |
| `llm_connections: connection … does not offer model …` | Use a model from the connection's `models`. |
| `mcp_servers: MCP server … is not configured for the tenant` | Use an id from `tenant.json`. |
| `mcp_servers: the tenant does not grant MCP server … to team …` | The tenant admin adds the team to the server's `teams`. |
| `projects.<id>.ci: MCP server … is not configured for the tenant` (or `… does not grant …`) | The project's `ci.mcp_server` must be a server of the tenant that is granted to the team. |
| `gerrit is self-hosted: set url` | Add `url` to the project's `scm`. |
| `bitbucket is hosted: leave out url`, `github is hosted: leave out url` | Remove `url` from the project's `scm`. |
| `repository should be <workspace>/<repository>` | Bitbucket and GitHub repositories are named with their workspace or owner. |
| `required_credentials: credential … is not declared by the tenant` | Use an id from `tenant.json`'s `credentials`. |
| `required_credentials: people can't provide credential … (its provided_by has no "user"), so it can't be required of them` | The tenant admin adds `user` to its `provided_by`. |
| `required_credentials: the tenant does not grant credential … to team …` | The tenant admin adds the team to the credential's `teams`. |
| `duplicate project ids: […]` (also `llm connection`, `MCP server`, `required credential`) | Make the ids unique. |
