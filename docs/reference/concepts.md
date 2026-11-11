# Concepts and terms

This page is for anyone new to the Agent Platform. It defines the words the rest of the site uses, says who does what, and shows how the parts fit together.

## Terms

These words are used with one meaning everywhere on this site.

| Term | Meaning |
|---|---|
| **Tenant** | An organisation on the platform, for example `acme`. Teams, people, sessions, runs, credentials, usage and audit trails belong to exactly one tenant. Nobody sees another tenant's data. |
| **Onboarding repository** | One Git repository per tenant, owned by its tenant admins. It holds `tenant.json`, the tenant's configuration. |
| **Team** | A group of people inside a tenant with its own repository, budget, credentials, runs and sessions. Who is in a team comes from your identity provider, never from a file. |
| **Team repository** | One Git repository per team. Its `.platform/` directory holds `team.json` and the team's agents, harnesses and workflows. |
| **Agent** | A prompt: the role and instructions given to the model. |
| **Harness** | The sandbox an agent works in: models, network access, tool permissions, tool servers, skills, container image and limits. |
| **Activity** | A kind of work the platform knows how to run and check. There are four: `session` (an OpenCode workspace in the browser, for one person or shared by a team), `review`, `implement` and `analysis`. |
| **Workflow** | Something people can start: an activity combined with an agent and a harness, optionally started by a schedule or by new pull requests. |
| **Run** | One execution of a workflow in a team. It creates a session, collects the result and meters usage. |
| **Session** | One sandboxed OpenCode instance in a team, owned by a person or by the team. People open it in the browser, share it, fork it and lock it. |
| **Credential** | A named secret a tool needs (a Bitbucket token, a Jira account). It is declared once per tenant; values are set by the tenant, a team or each person. |
| **MCP server** | A tool server (Bitbucket, Jenkins, Jira, your own) that agents call through the platform's gateway, which adds the credential. |
| **Project** | A source repository a team works on, with its CI job, declared in `team.json` so workflows can refer to it by name. |

## Who does what

| Role | What they do |
|---|---|
| **Platform operator** | Installs and runs the platform, connects the identity provider, registers tenants. Sees no tenant's data. |
| **Tenant admin** | Owns one organisation's configuration: LLM connections, credentials, tool servers and the list of teams. |
| **Team admin** | Owns one team's agents, sandboxes, workflows, budget and team credentials. |
| **Member** | Runs the team's workflows, works in sessions, reads results. |

The exact permissions of each role are in [Roles and visibility](roles.md).

## How it fits together

```text
 Git                             Platform                                   Outside
 ───                             ────────                                   ───────
 Onboarding repository           Manager (UI, API, sync)
   tenant.json          ──────►    reads tenant.json and team repositories
 Team repositories                 every few minutes
   .platform/team.json
   agents, harnesses,            Workers
   workflows                       start runs, poll pull requests,
                                   run schedules, stop idle sessions
                                        │ starts
                                        ▼
                                 Sandbox network (no internet)
                                   Session ── LLM calls ──► LLM gateway  ──► LLM provider
                                   Session ── tool calls ─► MCP gateway  ──► MCP servers
                                        │                   (adds the credential,
                                        │ every event        checks the allow-list)
                                        ▼
                                 Audit trail (append-only, hash-chained)
```

| Part | What it does |
|---|---|
| Manager | Serves the UI and API, reads the tenants' repositories, stores all state. |
| Workers | Background jobs inside the manager: they start sandboxes, drive runs, poll for pull requests, run schedules and stop idle sessions. |
| Session | A container with OpenCode. It has no credentials and, unless its harness allows internet, reaches only the two gateways. |
| LLM gateway | Adds the LLM key, enforces the model allow-list and the team's token budget, records usage. |
| MCP gateway | Adds the right credential to each tool call, enforces the tool allow-list, writes the call to the audit trail. |
| Audit trail | Every message, tool call and session event, recorded outside the sandbox. |

The reasoning behind this layout is in the [Security model](security.md).
