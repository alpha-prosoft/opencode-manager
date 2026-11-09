# Workflows, agents and harnesses

This page is for team admins who write the definition files in a team repository. It lists every field of agent, harness and workflow files, the options and inputs of each activity, and how triggers and projects work. For a guided introduction, read [Set up a team](../story/team.md).

## Files and ids

```text
.platform/
├── agents/<id>.json
├── harnesses/<id>.json
└── workflows/<id>.json
```

- The file name without `.json` is the definition's id. Ids match `^[a-z0-9][a-z0-9-]{0,62}$`.
- Unknown fields are refused, except inside a workflow's `options` (checked against the activity) and a schedule's `inputs`.
- `"$schema"` is allowed in every file and ignored by the platform. Schemas: [agents](https://github.com/alpha-prosoft/opencode-manager/blob/main/schemas/agents.schema.json), [harnesses](https://github.com/alpha-prosoft/opencode-manager/blob/main/schemas/harnesses.schema.json), [workflows](https://github.com/alpha-prosoft/opencode-manager/blob/main/schemas/workflows.schema.json).
- Paths to other files (`prompt_file`, `guidelines_file`, skills, …) are relative to `.platform/` and can't leave it, not even through symbolic links.
- Every definition is validated at each sync. An invalid one is listed with its reason on the team's page and can't be used.

## Agent

`.platform/agents/<id>.json`

| Field | Type | Default | Meaning |
|---|---|---|---|
| `description` | string | `""` | Shown to people. |
| `prompt` | string | none | The prompt, inline. |
| `prompt_file` | string | none | A file with the prompt. |
| `temperature` | number, 0 to 2 | none | Sampling temperature. |
| `steps` | integer, 1 to 500 | none | Maximum number of agentic iterations. |

Set exactly one of `prompt` and `prompt_file`.

```json
{
  "description": "Careful code reviewer that reports only verified issues",
  "prompt_file": "prompts/reviewer.md",
  "temperature": 0.1
}
```

## Harness

`.platform/harnesses/<id>.json`: the sandbox an agent runs in.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `description` | string | `""` | Shown to people. |
| `image` | string | the platform's session image | The container image. It must be in the tenant's `allowed_images`. |
| `llm.connection` | LLM connection id | required | A connection the team uses. |
| `llm.models` | list of strings, at least one | required | Models of that connection, tried in order: if one is unavailable, the next is used. |
| `network` | `none` or `internet` | `none` | `none`: the session reaches only the platform's gateways. `internet`: outbound internet as well; web search and fetch are then allowed. |
| `permissions` | object: tool → `allow`, `ask` or `deny` | `{}` | OpenCode tool permissions, for example `{"bash": "allow", "edit": "deny", "webfetch": "deny"}`. Automated runs never ask questions. |
| `resources.cpus` | number, at most 8 | `1` | CPU limit. |
| `resources.memory_mb` | integer, 256 to 32768 | `2048` | Memory limit in MB. |
| `resources.pids` | integer, 64 to 4096 | `512` | Process limit. |
| `idle_minutes` | integer, 1 to 1440 | `15` | An idle session is stopped after this long. The team's `budget.idle_minutes` caps it. A stopped session starts again when opened. |
| `policy.deny_commands` | list of regular expressions | `[]` | A `bash` call whose command matches one is blocked before it runs. |
| `policy.deny_paths` | list of regular expressions | `[]` | Matched against the file path of `read`, `edit`, `write` and similar tools. |
| `mcp` | list of MCP server ids | `[]` | The MCP servers sessions of this harness get, through the platform's gateway. Each must be declared in `tenant.json`, granted to the team and chosen in `team.json`. |
| `skills` | list of directory paths | `[]` | Skill directories, each with a `SKILL.md` ([Agent Skills](https://opencode.ai/docs/skills/) format). Copied read-only into the session. Text files only, no links, 1 MB in total. |

```json
{
  "description": "No internet and no file edits: for reviews and analysis.",
  "llm": { "connection": "copilot", "models": ["claude-sonnet-5.5"] },
  "network": "none",
  "permissions": { "edit": "deny", "bash": "allow" },
  "resources": { "cpus": 1, "memory_mb": 1536 },
  "idle_minutes": 10
}
```

When a policy blocks a call, the agent sees *Blocked by platform policy* and the block appears in the session's live activity. An invalid regular expression makes the harness invalid.

The harness is turned into an OpenCode configuration that is mounted read-only into the session. The agent can't change its model, permissions or tools.

## Workflow

`.platform/workflows/<id>.json`: a trigger wired to an activity.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `name` | string | required | Shown to people. |
| `description` | string | `""` | Shown to people. |
| `activity` | activity id | required | `session`, `review`, `implement` or `analysis`. |
| `agent` | agent id | required | An agent of the team. |
| `harness` | harness id | required | A harness of the team. |
| `project` | project id | none | A project of the team's `team.json`. It fills in what the trigger and options leave out. |
| `trigger` | [trigger](#triggers) | `{"type": "manual"}` | What starts the workflow. |
| `options` | object | `{}` | The activity's options; see [Activities](#activities). |
| `visibility` | `team` or `tenant` | `team` | What **Shared with** is preset to when someone starts the workflow: the team, or everyone in the tenant. People can change it in the start dialog; interactive workspaces start with **Only me**. |

## Triggers

### manual

```json
"trigger": { "type": "manual" }
```

The default. People start the workflow from the UI.

### schedule

| Field | Type | Default | Meaning |
|---|---|---|---|
| `type` | `schedule` | required | |
| `every_minutes` | integer, 5 to 10080 | required | The interval. |
| `inputs` | object | `{}` | The inputs every run gets. |

```json
"trigger": { "type": "schedule", "every_minutes": 60, "inputs": { "brief": "Summarise open incidents." } }
```

### pull_request

Polls for new pull requests (Gerrit: open changes) and starts one run per pull request. Only the `review` activity can have this trigger.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `type` | `pull_request` | required | |
| `provider` | `bitbucket`, `github` or `gerrit` | from the project | The hosting provider. |
| `repository` | string | from the project | `<workspace>/<repository>`, or the Gerrit project name. |
| `url` | `http(s)://` URL | from the project | Base URL of the provider. Required for `gerrit`; not allowed for `bitbucket` and `github`. |
| `web_url` | `http(s)://` URL | `url` | Where browsers reach the provider. |
| `credentials` | credential id | from the project | The credential used to read pull requests and post reviews. |
| `target_branches` | list of strings | `[]` (all) | Only pull requests into these branches. |
| `poll_minutes` | integer, 1 to 1440 | `15` | How often to look. |
| `backfill` | boolean | `false` | Also review pull requests that are already open when the trigger first runs. |

`provider`, `repository` and `credentials` must be set here or come from the workflow's project. Each pull request is reviewed once; a new patch set of a reviewed Gerrit change is not reviewed again. The workflow can also be started by hand for any pull request number.

Scheduled and triggered workflows run as the platform with team or tenant credential values. One that needs a credential with `"provided_by": ["user"]` is invalid.

## Activities

The **Activities** page of the UI shows the same information for each activity, with an example workflow file. It is generated from the platform's built-in activity catalog.

| Activity | Kind | Result |
|---|---|---|
| `session` | interactive | An OpenCode workspace in the browser, for one person or shared by a team, optionally with a repository checked out. |
| `review` | automated | A structured review: verdict, findings, checked items, and a comment plan. |
| `implement` | automated | Title, summary, changes, verification, and a patch computed by the platform. Optionally a pull request and its build. |
| `analysis` | automated | A Markdown document. |

An automated activity ends when the agent calls a typed result tool (`submit_review`, `submit_implementation`, `submit_document`). The platform validates the result and decides what happens next. The agent never posts or pushes anything itself.

### Repository options

Several activities take a `repository` option, a Git source:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `url` | string | required | An `https://`, ssh (`git@host:org/repo.git`) or `file://` URL. |
| `branch` | string | `main` | The branch checked out. |
| `credentials` | credential id | none | A token for https URLs, a private key for ssh URLs. It must be granted to the team and allowed for the URL's host. |
| `username` | string | `x-token-auth` | The username sent with the token. |

### session

| Option | Type | Default | Meaning |
|---|---|---|---|
| `repository` | Git source | none | The repository checked out into the workspace. |
| `instructions_file` | string | none | A file appended to the agent's prompt. |

| Input | Type | Default | Meaning |
|---|---|---|---|
| `title` | string, up to 100 characters | `""` | The name shown in the session list. |
| `prompt` | string, up to 20000 characters | none | The first message sent to the agent. |
| `branch` | string | the repository's branch | Check out this branch instead. |

### review

| Option | Type | Default | Meaning |
|---|---|---|---|
| `scm` | SCM source: `provider`, `repository`, `credentials` (all required), `url` (required for `gerrit`, not allowed otherwise), `web_url` | none | Where pull requests live. A `pull_request` trigger or a project sets it. |
| `repository` | Git source | none | Review a branch of this repository instead of a pull request. Nothing is posted. |
| `guidelines_file` | string | none | A file with the review baseline. |
| `post_mode` | `off`, `dry-run` or `auto` | `dry-run` | Only `auto` posts the review to the pull request. Branch reviews never post. |
| `max_comments` | integer, 1 to 100 | `30` | The most comments posted per pull request. |

Set exactly one of `scm` (or a `pull_request` trigger) and `repository`.

| Input (pull request review) | Type | Meaning |
|---|---|---|
| `pull_request` | integer ≥ 1, required | The pull request number. |

| Input (branch review) | Type | Default | Meaning |
|---|---|---|---|
| `branch` | string, required | | The branch to review. |
| `base` | string | `main` | The branch it is compared against. |

A review is turned into at most `max_comments` comments: a summary plus inline comments anchored only to lines that are in the diff, highest severity first.

### implement

| Option | Type | Default | Meaning |
|---|---|---|---|
| `repository` | Git source | required | The repository the change is made in. |
| `guidelines_file` | string | none | A file with coding guidelines. |
| `pull_request` | object | none | Push the change and open a pull request (never merged), with the repository's credential. |
| `pull_request.provider` | `bitbucket`, `github` or `gerrit` | required | |
| `pull_request.repository` | string | required | `<workspace>/<repository>`, or the Gerrit project name. |
| `pull_request.url`, `.web_url` | `http(s)://` URL | none | `url` is required for `gerrit` and not allowed for `bitbucket` and `github`. |
| `pull_request.target_branch` | string | the branch worked on | The branch the pull request targets. |
| `ci` | object | none | Follow the pull request's build. Needs `pull_request`. |
| `ci.mcp_server` | MCP server id | required | The server whose `get_build` tool reads the CI system. |
| `ci.job` | string | required | The job that builds the change. With `{pr}` (replaced by the pull request number), the job's last build is followed. Without, the job is shared and the build whose description names the pushed commit is followed. |
| `ci.timeout_minutes` | integer, 5 to 720 | `120` | Give up waiting for the build after this long. |
| `ci.on_failure` | workflow id | none | A manual `analysis` or `session` workflow of the team, started with the failure details when the build fails. |

| Input | Type | Default | Meaning |
|---|---|---|---|
| `task` | string, 10 to 20000 characters, required | | What to implement. |
| `pull_request` | integer | none | Continue on this open pull request: its branch is checked out and the change is pushed to it. |
| `branch` | string | the repository's branch | The base branch. |

The CI's MCP server must allow `get_build`; also `get_build_log` when `on_failure` is set and `get_builds` when the job has no `{pr}`.

### analysis

| Option | Type | Default | Meaning |
|---|---|---|---|
| `repository` | Git source | none | A repository for context. |
| `template_file` | string | none | A file with the document template. |

| Input | Type | Meaning |
|---|---|---|
| `brief` | string, 10 to 20000 characters, required | What to analyse. |

## Projects in workflows

A workflow with `"project": "<id>"` takes what it leaves out from that project of `team.json`:

| Activity | Filled in from the project |
|---|---|
| Any, with a `pull_request` trigger | The trigger's `provider`, `repository`, `url`, `web_url`, `credentials` |
| `review` | `options.scm`, unless the workflow reviews a branch with `options.repository` |
| `implement` | `options.repository` (clone URL, branch, credential), `options.pull_request` and, when the project has `ci.job`, `options.ci` (`mcp_server`, `job`). The workflow adds `on_failure` and `timeout_minutes`. |
| `session`, `analysis` | `options.repository` |

Anything the workflow sets itself wins. The resolved trigger and options are what the platform validates and what the team's page shows. Runs of such a workflow, their sessions and forks carry the project, so lists can be filtered by it.

```json
{
  "name": "Review Gerrit changes",
  "activity": "review",
  "agent": "reviewer",
  "harness": "read-only",
  "project": "demo-service",
  "trigger": { "type": "pull_request", "poll_minutes": 1 },
  "options": { "post_mode": "auto" }
}
```

## SCM providers

Bitbucket Cloud, GitHub and Gerrit are interchangeable wherever a source appears: `options.scm`, a `pull_request` trigger, `options.pull_request` and projects.

| | Bitbucket, GitHub | Gerrit |
|---|---|---|
| `url` | Not allowed: the provider is hosted | Required: the base URL of your Gerrit |
| Pull requests | Open pull requests | Open changes of the project; the latest patch set is checked out |
| Diff | The pull request's diff | The latest patch set's patch |
| Review in `auto` mode | A summary comment and inline comments | One review on the latest patch set: summary, inline comments and a Code-Review vote from the verdict (`APPROVE` +1, `APPROVE_WITH_NITS` 0, `REQUEST_CHANGES` -1) |
| Delivering a change | Push `agent/<run id>`, open a pull request (a draft when the agent's checks didn't pass) | Push to `refs/for/<branch>` with a `Change-Id` derived from the run; continuing a change adds a new patch set |
| Credential | A token | `kind: basic`: the Gerrit username and its HTTP password |
| API host the credential must be allowed for | `api.bitbucket.org`, `api.github.com` | The host of `url` |

## Validation messages

| Message | Meaning |
|---|---|
| `agent … does not exist`, `harness … does not exist` | No such file. |
| `agent … is invalid`, `harness … is invalid` | The referenced definition has an error of its own. |
| `unknown activity …` | Not one of the four activities. |
| `options.<field>: …` | The option doesn't match the activity's schema. |
| `options.<file option>: … not found in the team definitions` | The file is missing or outside `.platform/`. |
| `file: set exactly one of prompt or prompt_file` | Agent with both or neither. |
| `image … is not in the platform's allowed_images` | The harness's `image` isn't in the tenant's `allowed_images`. |
| `llm.connection … does not exist` | The harness names a connection `tenant.json` doesn't have. |
| `team … may not use llm connection …` | The tenant restricts the connection to other teams. |
| `llm connection … is not in the team's team.json llm_connections` | Not chosen in `team.json`. |
| `models […] are not offered by connection …` | The tenant's connection doesn't list those models. |
| `models […] are not allowed by the team's team.json` | `team.json` narrows the connection to other models. |
| `skill …: no SKILL.md found in the team definitions` | The skill directory is missing or has no `SKILL.md`. |
| `skill …: … is not a text file`, `skill …: links are not allowed (…)` | Skills hold text files only, without symbolic links. |
| `skill …: another skill is also named …` | Two skill paths end in the same directory name. |
| `project … is not in the team's team.json projects` | The workflow's `project` isn't declared in `team.json`. |
| a message ending in `gerrit is self-hosted: set url` or `github is hosted: leave out url` (also `bitbucket`) | `url` is required for Gerrit and not allowed for Bitbucket and GitHub. |
| `MCP server … is not configured in the onboarding repository` | Not in `tenant.json`. |
| `team … may not use MCP server …` | The tenant restricts the server to other teams. |
| `MCP server … is not in the team's team.json mcp_servers` | Not chosen in `team.json`. |
| `MCP server … needs credential …, which team … may not use` | The server's credential isn't granted to the team. |
| `skills are larger than 1 MB in total` | Reduce the harness's skills. |
| `credential … is not granted to team … in the onboarding repository` | The tenant admin must grant the secret to the team. |
| `options.repository.url: …` | The URL isn't an allowed kind (see the message). |
| `options.repository: secret … may not be sent to …` | The secret's allowed hosts don't include the repository's host. |
| `options.pull_request needs options.repository.credentials to push and open the pull request` | Add a credential to the repository. |
| `options.pull_request: credential … may not be sent to …` | Add the provider's API host to the credential's `hosts`. |
| `options.ci needs options.pull_request: only pushed pull requests have builds` | Add `pull_request`, or remove `ci`. |
| `options.ci: MCP server … is not configured in the onboarding repository` | Not in `tenant.json`. |
| `options.ci: MCP server … must allow the tools […]` | Add the tools to the server's `tools` in `tenant.json`. |
| `options.ci.on_failure: workflow … does not exist or is invalid` | Fix or create that workflow. |
| `options.ci.on_failure: … must be a manual analysis or workspace workflow` | Point it at a manual `analysis` or `session` workflow. |
| `runs automatically, but credential … only has personal values; let the tenant or team provide it too (provided_by)` | An automated workflow can't use a personal-only credential. |
| `pull_request triggers can only start the review activity` | Change the trigger or the activity. |
| `trigger: set provider, repository, credentials (or name a project)` | The trigger has no source. |
| `set exactly one of options.scm (or a pull_request trigger) to review pull requests, or options.repository to review a branch` | A review with both or neither. |
| `options.post_mode: branch reviews never post; use off or dry-run` | Remove `auto` from a branch review. |
