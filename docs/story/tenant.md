# 3. Onboard a tenant

This page is for the tenant admin, with one step for the platform operator. You build the tenant's `tenant.json` piece by piece, the operator registers the tenant, and you check the first sync and set the tenant's credential values. At the end the tenant `acme` exists on the platform with its LLM connection, credentials, tool servers and teams.

The finished file is [examples/tenants/acme/onboarding/tenant.json](https://github.com/alpha-prosoft/opencode-manager/blob/main/examples/tenants/acme/onboarding/tenant.json). Every field is listed in the [tenant.json reference](../reference/tenant-json.md).

## The onboarding repository

Each tenant has one [onboarding repository](../reference/concepts.md#terms) with a single file, `tenant.json`, at its root. Tenant admins own it; whoever may push to it decides what the tenant's teams may use. The platform reads it every 10 minutes by default and whenever an admin asks for a sync.

Nothing in it is secret. Secrets are referred to by name (`COPILOT_TOKEN`); the values are set elsewhere.

## Build tenant.json step by step

### Start with the allowed images

```json
{
  "version": 1,
  "allowed_images": [
    "ghcr.io/anomalyco/opencode:1.19.5",
    "opencode-platform/opencode-dev:1.19.5"
  ]
}
```

`allowed_images` are the only container images a harness may use for its sessions. The first one is the plain OpenCode image; the second is the example image with Python, Node and git that `make dev` builds.

### Add an LLM connection

```json
"llm_connections": [
  {
    "id": "copilot",
    "type": "github-copilot",
    "secret": "COPILOT_TOKEN",
    "models": ["claude-sonnet-5.5", "claude-opus-5.5", "gemini-3.5-flash"],
    "pricing": {
      "claude-sonnet-5.5": { "input": 3.0, "output": 15.0, "cached_input": 0.3 }
    }
  }
]
```

- `secret` names the secret holding the key. LLM secrets are always tenant values: no team or person provides them.
- `models` is the allow-list. A harness can only name models listed here.
- `pricing` is optional: prices per million tokens, used for the cost estimates on the Admin page.
- `"teams": ["payments"]` would restrict the connection to some teams; left out, every team may use it.

For any OpenAI-compatible endpoint use `"type": "openai-compatible"` with a `base_url`:

```json
{
  "id": "azure",
  "type": "openai-compatible",
  "base_url": "https://my-azure.openai.azure.com/openai/v1",
  "secret": "AZURE_OPENAI_KEY",
  "models": ["gpt-5"]
}
```

### Declare credentials

A credential is a named secret a tool needs. You declare it once; `provided_by` says who may set a value.

```json
"credentials": [
  {
    "id": "BITBUCKET_TOKEN",
    "name": "Bitbucket",
    "description": "Read access to Bitbucket Cloud repositories and pull requests.",
    "kind": "token",
    "hosts": ["bitbucket.org", "api.bitbucket.org", "mcp-bitbucket"],
    "provided_by": ["tenant", "team", "user"],
    "help": "Create one under Bitbucket → Personal settings → API tokens (read scopes are enough).",
    "test": { "url": "https://api.bitbucket.org/2.0/user" }
  },
  {
    "id": "NEXUS_TOKEN",
    "name": "Nexus",
    "kind": "token",
    "hosts": ["nexus.internal"],
    "provided_by": ["tenant", "team"]
  },
  {
    "id": "PAGERDUTY_TOKEN",
    "name": "PagerDuty",
    "kind": "token",
    "hosts": ["api.pagerduty.com"],
    "provided_by": ["user"],
    "required": true,
    "teams": ["ops"]
  }
]
```

The three examples show the three common shapes:

| Credential | `provided_by` | Effect |
|---|---|---|
| Bitbucket | `tenant`, `team`, `user` | A shared read-only account works for everyone; a team can set its own; a person can add their own token to act as themselves. The most specific value is used. |
| Nexus | `tenant`, `team` | A shared account only. People are never asked for it. |
| PagerDuty | `user`, `required` | Each person must add their own. Nobody in Operations can open the platform's pages or start work there until they have. |

Points to decide for each credential:

- **`hosts`** are the only hosts the value is ever sent to. This holds for MCP servers, Git checkouts and pull request APIs alike.
- **`kind`** is `token` (one secret), `basic` (a username and a password, for example an Atlassian e-mail and API token) or `ssh_key`.
- **`test`** checks a value when someone saves it. `{"url": …}` calls that address with the value; anything but a 2xx answer rejects it. `{"mcp_tool": "whoami"}` calls that tool of the MCP server that uses the credential, which also works for services only the MCP server can reach.
- **`help`** is shown to people when they enter the value. Say where to create the token.
- **`required`** needs `user` in `provided_by`; the sync refuses the file otherwise. Read [what it does to members](first-day.md#connect-your-accounts) before you turn it on.

!!! note
    A workflow that starts by itself (a schedule, a pull request trigger, a webhook) has no person, so it can only use team or tenant values. A credential with `"provided_by": ["user"]` can't be used by such a workflow; the sync marks the workflow invalid and says so.

The rules for choosing a value are in the [Credentials reference](../reference/credentials.md).

### Add MCP servers

An MCP server gives agents tools. Agents never talk to it directly: the platform's gateway forwards each call and adds the credential.

```json
"mcp_servers": [
  {
    "id": "bitbucket",
    "name": "Bitbucket (read-only)",
    "description": "Pull requests, diffs and repository details from Bitbucket Cloud.",
    "url": "http://mcp-bitbucket:8000/",
    "credential": "BITBUCKET_TOKEN",
    "tools": ["whoami", "list_pull_requests", "get_pull_request", "get_pull_request_diff"]
  },
  {
    "id": "jira",
    "name": "Jira",
    "url": "https://your-company.atlassian.net/mcp/",
    "credential": "JIRA_TOKEN",
    "auth": { "scheme": "basic" },
    "teams": ["payments"]
  }
]
```

- `url` is a Streamable HTTP MCP endpoint that only the manager needs to reach.
- `credential` is added by the gateway to every request, as `Authorization: Bearer …` unless `auth` says otherwise.
- `tools` is an allow-list. Other tools are hidden from the agent and refused when called. Left out, all tools are allowed.
- `teams` restricts which teams may use the server.

**Services behind a VPN or a company CA.** Only the MCP server has to reach a tool's service, not the manager. Use `"test": {"mcp_tool": "whoami"}` for its credential so the check goes through the MCP server too. On the compose stack, if a service is reachable only through a VPN on the host, run that MCP server with host networking, bound to the Docker bridge address (`HOST=172.17.0.1`), and point `mcp_servers[].url` at `http://host.docker.internal:<port>/`. For internal certificates, mount the company CA bundle read-only into the MCP server and set `SSL_CERT_FILE`. Keep such overrides in a compose file of your own, named in `COMPOSE_FILE` in `.env`.

The two MCP servers of the examples are in the repository: `images/mcp-bitbucket`, a small read-only Bitbucket Cloud server, and `images/mcp-jenkins`, a read-only Jenkins server (`whoami`, `list_jobs`, `get_builds`, `get_build`, `get_build_log`). Neither holds credentials of its own: each forwards the `Authorization` header the gateway adds.

### Limit where Git secrets go

```json
"secret_hosts": {
  "GITHUB_TOKEN": ["github.com"]
}
```

`secret_hosts` is for Git secrets that are **not** declared under `credentials`, such as an operator's token for checkouts: it lists the hosts each one may be sent to. A declared credential has its own `hosts` for that. A secret is never sent to a host outside these lists, even if a team repository asks for it.

### List the teams

```json
"teams": [
  {
    "id": "demo",
    "name": "Demo team",
    "description": "Sample team with one workflow for every activity.",
    "repository": { "url": "file:///repos/acme-demo", "branch": "main" },
    "secrets": []
  },
  {
    "id": "payments",
    "name": "Payments",
    "repository": { "type": "git", "url": "file:///repos/acme-payments", "branch": "main" },
    "secrets": []
  },
  {
    "id": "ops",
    "name": "Operations",
    "repository": { "type": "git", "url": "file:///repos/acme-ops", "branch": "main" },
    "secrets": []
  }
]
```

- `id` is what the identity provider's groups use (`/acme/demo`) and what appears in URLs (`/acme/demo`). It can't be a word the UI uses as a page name; the sync refuses such an id and names it.
- `repository` says where the team's definitions live. The examples use local `file://` repositories; in production it is an https or ssh Git URL with a credential:

    ```json
    "repository": {
      "type": "git",
      "url": "https://bitbucket.org/acme/payments-agents.git",
      "branch": "main",
      "credentials": "BITBUCKET_TOKEN",
      "username": "x-bitbucket-api-token-auth"
    }
    ```

- `secrets` grants the team secrets that are not declared under `credentials`. There are two ways a team gets to use a secret in its workflows (repository checkouts, pull request triggers, MCP servers):

    | The secret is | The team may use it when |
    |---|---|
    | declared under `credentials` | the credential's `teams` includes the team (the default `"*"` means every team) |
    | not declared under `credentials`: an operator secret | the team's `secrets` lists it |

    For example, `GITHUB_TOKEN` above is an operator secret with `secret_hosts` but no entry under `credentials`. The Demo team's workflow **Review GitHub pull requests** uses it, so the team needs:

    ```json
    { "id": "demo", "name": "Demo team", "repository": { "url": "file:///repos/acme-demo" }, "secrets": ["GITHUB_TOKEN"] }
    ```

    Without that grant the workflow is invalid: `credential GITHUB_TOKEN is not granted to team demo in the onboarding repository`. The shipped example leaves `secrets` empty, so that workflow shows as invalid until you add the grant and provide the secret.
- `notifications` optionally names a secret holding a webhook URL (for example a Slack incoming webhook) that the platform calls when a run of the team completes or fails.

There are no member lists. People appear in a team when their token says so.

## Do it: publish the onboarding repository

In production:

1. Create an empty Git repository the platform can reach, for example `https://git.example.com/acme/onboarding.git`. Let only tenant admins push to it.
2. Save your file as `tenant.json` at the root of the repository.
3. Commit and push to the branch the platform reads (`main` unless the operator names another):

    ```bash
    git add tenant.json
    git commit -m "Onboard acme"
    git push origin main
    ```

4. Give the operator the repository's address and, if it is private, a token that can read it. The operator stores the token as an operator secret and registers the tenant (next section).

On the laptop the repository already exists: the seed script publishes `examples/tenants/acme/onboarding` as the local repository `acme-onboarding`.

1. Edit `examples/tenants/acme/onboarding/tenant.json`.
2. Publish the change. The script prints the new commit, for example `acme-onboarding: 3f2a9c1`:

    ```bash
    ./scripts/seed-local-repos.sh
    ```

3. Sync, as described under [The first sync](#the-first-sync-and-how-to-read-it).

## Register the tenant

This step is the platform operator's. A tenant is a name and the location of its onboarding repository. There are three ways to register it.

**In the server configuration**, as JSON in `MANAGER_TENANTS` (Helm: `tenants`):

```json
[
  { "name": "acme", "onboarding": "https://git.example.com/acme/onboarding.git" },
  { "name": "globex", "onboarding": { "type": "git", "url": "https://git.example.com/globex/onboarding.git",
                                      "branch": "main", "credentials": "GLOBEX_GIT_TOKEN" } }
]
```

**In a tenants file**, `MANAGER_TENANTS_FILE` (a `.json` or `.edn` file with the same list; Helm: `tenantsFile.configMap`). The file is checked for changes every 10 seconds by default. A tenant you add is synced at once; an invalid file keeps the last good list.

**On the Tenants page**, at runtime: an operator enters the name and the onboarding repository and selects **Register tenant**. The tenant is listed at once and its first sync starts.

The onboarding source is a Git URL, an https `.tar.gz` or `.zip` archive, or an absolute path or `file://` directory on the platform host; or a source object like a team's `repository`. The credential it names is an operator secret: a file `<secrets-dir>/<NAME>` or, on Kubernetes, a key of the Secret named in `platformSecrets.existingSecret`.

The evaluation stack registers `acme` and `globex` for you:

```json
[{"name":"acme","onboarding":"file:///repos/acme-onboarding"},{"name":"globex","onboarding":"file:///repos/globex-onboarding"}]
```

The click-by-click version is in the [guide for platform operators](../guide/operator.md).

## The first sync and how to read it

A sync reads `tenant.json`, then every team's repository. Each tenant syncs on its own, so a broken repository of one tenant never holds up another. It happens every 10 minutes by default, and at once when an admin asks:

- **Tenant admin** (`alice`): open **Admin**, tab **Overview**, and select **Sync acme's repositories**. The page says *The sync is running.*, then how it went: *Synced: all 3 team repositories are up to date.*, *Synced, but 1 of 3 team repositories failed.* or *The sync failed: …*. The link **See each team** next to it opens the **Teams** tab.
- **Operator** (`oscar`): open **Tenants** and select **Sync now** in the tenant's row.

To check that your change arrived, compare **Onboarding repository version** on **Admin → Overview** with the commit you pushed (on the laptop, the one the seed script printed).

| Who | Where | What it shows |
|---|---|---|
| Platform operator | **Tenants** | Per tenant: the onboarding repository, the time and result of the last sync, the number of teams. |
| Tenant admin | **Admin → Overview** | The commit of the onboarding repository in use, or *The last onboarding sync failed:* with the reason. |
| Tenant admin | **Admin → Teams** | The sync state of every team repository. |
| Team members | The team's page | Workflows, agents and harnesses with the reason for every invalid one. |

How failures behave:

- **`tenant.json` is invalid or can't be fetched.** The tenant keeps its last good configuration. The message names the file and the problem, for example `tenant.json: not found at the root of the onboarding source`.
- **A team's repository can't be fetched, or its `team.json` is invalid.** That team keeps its last good state. Other teams are not affected.
- **One workflow, agent or harness is invalid.** Only that definition can't run. The rest of the team works.
- **A team is removed from `tenant.json`.** It disappears from the platform; its history stays.

If the message is *The sync failed; the manager's log says why*, the cause is something the platform doesn't show to people because it may name server paths or internal hosts. Ask the operator to read the manager's log. Messages and fixes are in [Troubleshooting](../reference/troubleshooting.md#sync).

## Set the tenant's credential values

Open **Admin → Credentials** as the tenant admin (`alice` in the example). Every credential declared in `tenant.json` is listed with who provides it, how many team and personal values exist, and where it is used. For each credential that allows a tenant value, enter the value and save. If the credential has a `test`, the value is checked against the service first and a wrong value is not stored.

A value is encrypted before it is stored and is never shown again; the page shows only a short hint. To change it, enter a new one.

The secrets of LLM connections are listed on the same page. They are tenant values only, and there are two ways to provide one: a tenant admin sets it here, or the operator provides it as a secret file, in which case the page shows **From secret file**. On the evaluation stack that file is the `local/secrets/COPILOT_TOKEN` you created during installation; on Kubernetes it is a key of the platform secrets. A credential without any value shows **Missing**.

**Admin → LLM connections** has a **Test connection** button that sends a tiny prompt to the first model of a connection, which tells you whether the address and the secret are right.

Screens and every button are in the [guide for tenant admins](../guide/tenant-admin.md).

## You now have

- An onboarding repository, pushed, whose `tenant.json` declares the LLM connection, images, credentials, MCP servers and teams of `acme`.
- The tenant registered and synced, and a way to read the sync state.
- Tenant-wide credential values in place.

**Next:** [4. Set up a team](team.md)
