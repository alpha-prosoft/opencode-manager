# 4. Set up a team

This page is for the team admin. You create the team's repository: its limits, an agent, a harness and one workflow of each kind. At the end the team's members see workflows they can start.

The complete example is the Demo team's repository, [examples/tenants/acme/teams/demo](https://github.com/alpha-prosoft/opencode-manager/tree/main/examples/tenants/acme/teams/demo). Field-level detail is in the [team.json reference](../reference/team-json.md) and in [Workflows, agents and harnesses](../reference/definitions.md).

## The team repository

The tenant admin has listed your team in `tenant.json` with the address of its repository. Everything the platform reads is in one directory of it, `.platform/` by default:

```text
.platform/
├── team.json        budget, limits, LLM connections, MCP servers, projects, required credentials
├── agents/          one JSON file per agent      (the file name is the id)
├── harnesses/       one JSON file per harness
├── workflows/       one JSON file per workflow
├── prompts/         prompt files the agents refer to
├── context/         guidelines, templates and instructions the workflows refer to
└── skills/          one directory per skill, each with a SKILL.md
```

Only `team.json`, `agents/`, `harnesses/` and `workflows/` are fixed names. `prompts/`, `context/` and `skills/` are how the examples organise the files that definitions refer to; any path inside `.platform/` works. A referenced file can't be outside the directory, not even through a symbolic link.

You change the team by pushing to this repository. The platform reads it at the next sync (every 10 minutes by default). How to push and sync is in [Do it](#do-it-publish-the-team-repository) below.

For editor validation, add `"$schema": "<url of the schema>"` to a file; the platform ignores that key. The schemas are in the repository's [schemas](https://github.com/alpha-prosoft/opencode-manager/tree/main/schemas) directory.

## team.json: budget, limits and choices

```json
{
  "version": 1,
  "budget": {
    "daily_tokens": 5000000,
    "max_running_sessions": 10,
    "idle_minutes": 30
  },
  "llm_connections": [
    { "id": "copilot" }
  ],
  "mcp_servers": ["bitbucket", "jenkins"]
}
```

| Field | What it does |
|---|---|
| `budget.daily_tokens` | A cap on prompt plus completion tokens per UTC day. Once it is reached, the LLM gateway answers 429 and agents of the team stop getting answers until the next day. |
| `budget.max_running_sessions` | A cap on sessions running at the same time. While the team is at the limit, people can't start a workflow, fork a session, run a run again or restart a stopped session, and a run the platform starts by itself fails when it tries to create its session. |
| `budget.idle_minutes` | Sessions of the team are stopped after this many idle minutes. It caps the `idle_minutes` of each harness. |

`llm_connections` and `mcp_servers` choose which of the tenant's LLM connections (optionally narrowed to some `models`) and MCP servers the team uses. Bounds and sync messages for every field are in the [team.json reference](../reference/team-json.md#budget).

A team can only choose from what `tenant.json` offers and grants it. Leaving `llm_connections` or `mcp_servers` out means everything the tenant grants the team. A repository without a `team.json` works too: no limits, everything granted.

An invalid `team.json` fails the team's sync. The last good version stays in use and the error shows on the Admin page and on the team's page.

### Projects

A project is a source repository the team works on, with its CI job. Declaring it once lets workflows refer to it by name instead of repeating addresses and credentials, and gives the repository its own page in the UI.

```json
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
    "ci": {
      "mcp_server": "jenkins",
      "jobs": ["demo-service-verify"],
      "job": "demo-service-verify"
    }
  }
]
```

`scm.provider` is `bitbucket`, `github` or `gerrit`. `repository` is `<workspace>/<repository>` for Bitbucket and GitHub and the project name for Gerrit. `url` and `web_url` are for a self-hosted provider: where the platform reaches it and where browsers do.

### Required credentials

```json
"required_credentials": ["JIRA_TOKEN"]
```

Each listed credential must be one the tenant declares with `user` in `provided_by` and grants to your team. Every member must then add their **own** value: a team or tenant value doesn't count. Until they do, the platform holds them on the **Connect your accounts** page and refuses to start work in the team. Use this for tools that must act as the person, never for convenience. The example Operations team requires Jira this way.

## An agent

An agent is a prompt. The file name is its id: `.platform/agents/reviewer.json`.

```json
{
  "description": "Careful code reviewer that reports only verified issues",
  "prompt_file": "prompts/reviewer.md",
  "temperature": 0.1
}
```

Set exactly one of `prompt` (inline text) or `prompt_file` (a path relative to `.platform/`).

## A harness

A [harness](../index.md#terms) is the sandbox an agent works in. `.platform/harnesses/sandboxed.json`:

```json
{
  "description": "No internet. Only the platform LLM gateway is reachable.",
  "image": "opencode-platform/opencode-dev:1.19.5",
  "llm": {
    "connection": "copilot",
    "models": ["claude-sonnet-5.5", "gemini-3.5-flash"]
  },
  "network": "none",
  "permissions": { "bash": "allow", "edit": "allow" },
  "resources": { "cpus": 1, "memory_mb": 2048 },
  "idle_minutes": 15,
  "policy": {
    "deny_commands": ["\\bgit\\s+push\\b", "\\bcurl\\b.*\\|\\s*(ba)?sh\\b"],
    "deny_paths": ["(^|/)\\.env$"]
  }
}
```

What to decide:

- **`llm.models`** are tried in order: if a model is unavailable the session moves on to the next.
- **`network`** is `none` (the session reaches only the platform's gateways) or `internet` (outbound internet as well).
- **`permissions`** are OpenCode's tool permissions, `allow`, `ask` or `deny` per tool. A review harness typically sets `"edit": "deny"`. Automated runs never ask questions.
- **`policy`** blocks `bash` commands and file paths by regular expression before the tool runs. The agent sees *Blocked by platform policy*, and the block shows in the session's live activity.
- **`mcp`** lists the MCP servers sessions of this harness get, and **`skills`** the skill directories copied into the session.

The harness becomes an OpenCode configuration that is mounted read-only into the session, so the agent can't change its own model, permissions or tools.

A harness that gives the agent Bitbucket tools and a skill, `.platform/harnesses/bitbucket-tools.json`:

```json
{
  "description": "No internet. Bitbucket read-only tools through the platform gateway, with your own credential when you have set one.",
  "image": "opencode-platform/opencode-dev:1.19.5",
  "llm": { "connection": "copilot", "models": ["claude-sonnet-5.5", "gemini-3.5-flash"] },
  "network": "none",
  "permissions": { "bash": "allow", "edit": "allow" },
  "mcp": ["bitbucket"],
  "skills": ["skills/pr-triage"],
  "idle_minutes": 15
}
```

A skill is a directory with a `SKILL.md` in the [Agent Skills](https://opencode.ai/docs/skills/) format: text files only, no links, at most 1 MB for all skills of a harness together.

## A first workflow of each kind

A [workflow](../index.md#terms) wires an activity to an agent and a harness. The file name is its id. The **Activities** page in the UI lists every activity's options and inputs with an example file.

### A workspace session

`.platform/workflows/workspace.json`: an interactive OpenCode workspace with a repository checked out.

```json
{
  "name": "Workspace",
  "description": "Personal OpenCode workspace with the demo service checked out.",
  "activity": "session",
  "agent": "engineer",
  "harness": "sandboxed",
  "options": {
    "repository": { "url": "file:///repos/demo-service", "branch": "main" },
    "instructions_file": "context/workspace.md"
  }
}
```

People start it with **Start session**, optionally with a title, a first prompt and a branch. The session is shared with nobody (**Only me**) unless they choose otherwise under **Shared with**.

### An analysis

`.platform/workflows/requirements.json`: the agent writes a Markdown document from a brief.

```json
{
  "name": "Requirements analysis",
  "description": "Turn a feature brief into a requirements document.",
  "activity": "analysis",
  "agent": "analyst",
  "harness": "read-only",
  "options": {
    "repository": { "url": "file:///repos/demo-service", "branch": "main" },
    "template_file": "context/analysis-template.md"
  },
  "visibility": "tenant"
}
```

`"visibility": "tenant"` presets **Shared with** in the start dialog to everyone in the tenant instead of the team.

### An implementation

`.platform/workflows/implement-change.json`: the agent implements a task and the platform returns a patch.

```json
{
  "name": "Implement a change",
  "description": "Describe a change; get back a patch with a summary and test results.",
  "activity": "implement",
  "agent": "engineer",
  "harness": "sandboxed",
  "options": {
    "repository": { "url": "file:///repos/demo-service", "branch": "main" },
    "guidelines_file": "context/coding-guidelines.md"
  }
}
```

The result is a title, a summary, the commands the agent used to verify its work and whether they passed, and a patch that the platform computes from the working tree. Opening a pull request with the change is covered in [Automate the work](automate.md).

### A review

`.platform/workflows/review-branch.json`: the agent reviews a branch against a base branch. Nothing is posted anywhere.

```json
{
  "name": "Review a branch",
  "description": "Reviews a branch of demo-service against main. Nothing is posted.",
  "activity": "review",
  "agent": "reviewer",
  "harness": "read-only",
  "options": {
    "repository": { "url": "file:///repos/demo-service" },
    "guidelines_file": "context/review-guidelines.md"
  }
}
```

The result is a verdict, findings with severity, file, line and suggested fix, and the list of what was checked. Reviewing pull requests as they appear, and posting the comments, is covered in [Automate the work](automate.md).

## Do it: publish the team repository

In production:

1. Create a Git repository for the team and let only its admins push to it.
2. Put the files under `.platform/` as shown at the top of this page: `team.json`, and at least one file each in `agents/`, `harnesses/` and `workflows/`.
3. Commit and push to the branch `tenant.json` names for the team (`main` by default):

    ```bash
    git add .platform
    git commit -m "First workflows"
    git push origin main
    ```

4. Ask the tenant admin to add the team to `tenant.json` with this repository's address, if it isn't there yet.

On the laptop the Demo team's repository is `examples/tenants/acme/teams/demo`:

1. Edit or add files under `examples/tenants/acme/teams/demo/.platform/`.
2. Publish the change. The script prints the new commit of `acme-demo`:

    ```bash
    ./scripts/seed-local-repos.sh
    ```

Then sync and check, in both cases:

1. Wait for the next sync, or ask for one. A tenant admin (`alice`) selects **Sync acme's repositories** on **Admin → Overview**; an operator (`oscar`) selects **Sync now** on **Tenants**. Team admins who are not tenant admins can't start a sync.
2. Open the team's page (**Teams**, then **Demo team**). Your workflows are listed; one with a problem shows the reason next to it.
3. Open **Home** as a member (`bob`): the new workflow has a **Run** or **Start session** button.

## What "invalid" messages mean

Every definition is validated at each sync. An invalid one is shown with its reason on the team's page and can't be started; starting it anyway answers *Workflow demo/review-branch does not exist or is invalid*. Fix the file, push, and sync again.

The messages you are most likely to meet first:

| Message | What to fix |
|---|---|
| `agent reviewer does not exist`, `harness sandboxed does not exist` | The workflow names a file that isn't in `agents/` or `harnesses/`. |
| `options.guidelines_file: … not found in the team definitions` | The file isn't inside `.platform/`. |
| `MCP server … is not in the team's team.json mcp_servers` | Add it to `mcp_servers` in `team.json`, or leave that list out. |
| `credential … is not granted to team … in the onboarding repository` | The tenant admin must grant the credential or secret to the team in `tenant.json`. |

Every message with its meaning is in the reference: [definitions](../reference/definitions.md#validation-messages) and [team.json](../reference/team-json.md#sync-messages). The screens a team admin uses, including setting team credentials, are in the [guide for team admins](../guide/team-admin.md).

## You now have

- A team repository, pushed and synced, with `team.json`, an agent, a harness and one workflow of each kind.
- A budget, a session limit and an idle timeout for the team.
- A way to find out why a definition is invalid.

**Next:** [5. First day in a team](first-day.md)
