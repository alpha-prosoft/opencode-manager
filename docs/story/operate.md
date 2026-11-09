# 7. Govern and operate

This page is for tenant admins and platform operators who keep the platform healthy after the first teams are working. It covers budgets and usage, the audit trail, idle sessions, sync failures, reloading configuration, key rotation, backups, upgrades and monitoring, and ends with a checklist to go through before going live. At the end you know what to watch, what to back up and what to check.

## Budgets and usage

**Set limits per team** in the team's `team.json`:

```json
"budget": { "daily_tokens": 5000000, "max_running_sessions": 10, "idle_minutes": 30 }
```

| Limit | What happens when it is reached |
|---|---|
| `daily_tokens` | The LLM gateway answers `429` with *Team demo has used its daily token budget*. Agents of the team get no further answers until the next UTC day. |
| `max_running_sessions` | People can't start, fork, retry or restart a session: *Team demo already has 10 running sessions (limit 10). Stop one and retry.* A run the platform starts by itself fails with the same message when it creates its session. |
| `idle_minutes` | The team's sessions are stopped after this many idle minutes, whatever their harness says. |

Bounds for each field are in the [team.json reference](../reference/team-json.md#budget). The limits live in the team's repository, so the team admin sets them. A tenant admin who wants to enforce limits controls who may push to the team repositories.

**Read usage** as a tenant admin under **Admin → Usage & cost**: LLM usage for a period and its estimated cost. Cost appears only for models that have `pricing` in `tenant.json`. **Admin → Overview** shows today's numbers and running sessions; **Admin → People** shows who ran workflows or called a model in the last 30 days.

Usage is recorded by the LLM gateway from the provider's answer, not reported by the agent, for every call: prompt, completion and cached tokens, latency and errors, per session, run, team and model.

## The audit trail

For every running session the manager follows OpenCode's event stream and records messages, tool calls with their inputs and outputs, and session events. The record is made outside the sandbox, where the agent can't reach it. Every MCP tool call is recorded with which credential was used: a personal one or a shared one.

The trail is append-only and hash-chained: each entry's hash covers the previous entry's hash, so a changed or removed entry breaks the chain from that point on.

On a session's **Audit trail** tab you can:

- **read** the entries;
- **verify** the chain, which recomputes every hash;
- **download** the complete trail as JSONL (one JSON object per line) for your own archive or SIEM.

The trail is kept when the session is deleted.

The session's **Live activity** tab is a convenience view of tool calls and blocked calls. The audit trail is the record.

## Idle sessions

A session that has been idle longer than its limit is stopped by a background job that runs every 5 minutes by default (`MANAGER_REAP_INTERVAL_SECONDS`, Helm `reaper.intervalSeconds`). The limit is the harness's `idle_minutes` (default 15), capped by the team's `budget.idle_minutes`. A session with a busy conversation is never stopped. A stopped session keeps its workspace and starts again when someone opens it.

A tenant admin can stop every idle session in the tenant at once with **Stop idle sessions in acme** on **Admin → Overview**, for example to free capacity.

## Sync failures

Every tenant syncs on its own schedule and with its own status, so one tenant's failure never delays another.

| Symptom | Where you see it | What stays in use |
|---|---|---|
| The onboarding repository can't be fetched, or `tenant.json` is invalid | **Tenants** (operator), **Admin → Overview** (tenant admin) | The tenant's last good configuration |
| A team repository can't be fetched, or its `team.json` is invalid | **Admin → Teams**, the team's page | The team's last good configuration and definitions |
| One definition is invalid | The team's page, next to the definition | Everything else of the team |

Messages the platform writes itself say what is wrong. Anything else is shown as *The sync failed; the manager's log says why*, because the raw error may name server paths or internal hosts; the operator finds the cause in the manager's log (`make logs` on the evaluation stack).

After fixing the repository, wait for the next sync or ask for one. Tenant admins select **Sync acme's repositories** on **Admin → Overview**; the page then says how the sync went (*Synced: all 3 team repositories are up to date.* or how many failed) with the link **See each team**. Operators select **Sync now** on **Tenants**. Specific messages are in [Troubleshooting](../reference/troubleshooting.md#sync).

## Reload configuration

Some server-side configuration reloads without a restart:

| What | Reloads |
|---|---|
| The tenants file (`MANAGER_TENANTS_FILE`) | When it changes, checked every `MANAGER_CONFIG_RELOAD_SECONDS` (default 10). New tenants sync at once. Tenants removed from the file are no longer synced and leave the tenant list; their data stays and is back when the tenant returns. |
| The claims parser file (`MANAGER_CLAIMS_PARSER_FILE`) | When it changes, on the same interval. |
| The identity provider's discovery document and signing keys | On their refresh interval (see below). |

A file that fails to load or validate keeps the last good version and logs why.

Operators can reload all of it at once, regardless of the intervals, with the **Reload configuration** button on **Tenants**. The page then shows, per part, whether it was reloaded or failed and why; a part that failed keeps its last good value. The same is available over the API:

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" -H "X-Requested-With: cli" https://<manager>/api/admin/reload-config
```

```json
{"claims-parser":{"status":"reloaded"},"tenants":{"status":"reloaded"},
 "identity-provider":{"discovery":{"status":"reloaded"},"keys":{"status":"reloaded"}},"new-tenants":["globex"]}
```

The `Authorization` header works in jwt mode. In oidc mode there is no bearer token: use the **Reload configuration** button, or send your browser's login cookie (`__Host-oc_auth` over https, `oc_auth` over http) with `-H "Cookie: __Host-oc_auth=…"` in place of the `Authorization` header. Everything in `MANAGER_*` environment variables other than these files needs a restart.

## Key rotation at the identity provider

The identity provider's signing keys rotate without a restart, in both jwt and oidc mode (`<MODE>` is `JWT` or `OIDC`):

- The keys and the discovery document are loaded again every `MANAGER_<MODE>_JWKS_REFRESH_SECONDS` (default 3600).
- A token that names a key id the manager doesn't know reloads the keys at once, but at most every `MANAGER_<MODE>_JWKS_REFRESH_RETRY_SECONDS` (default 10), so invented key ids can't flood the provider.
- A reload that fails or returns no usable keys keeps the last good key set and logs why. Requests then fail only as an invalid token would.
- A reloaded discovery document that names another issuer is refused.

If your provider's keys are delivered as a file (`MANAGER_<MODE>_JWKS_FILE`, for example written by a sidecar), the file is read again when it changes. Write it atomically: write a new file, then rename it.

## Backups: what lives where

| State | Where | Lose it and |
|---|---|---|
| Everything the platform knows: tenants, teams' synced definitions, runs and results, sessions, credential values (encrypted), webhooks, usage, audit trails | The Postgres database (`MANAGER_STORE=postgres`) | The platform starts empty. **Back this up.** |
| The credentials key | `MANAGER_CREDENTIALS_KEY` (Helm: the Secret in `credentialsKey.existingSecret`), or, if unset, the file `credentials.key` on the manager's data volume | Every stored credential value becomes unreadable; people and admins must enter them again. **Keep a copy outside the cluster.** |
| The signing key | `MANAGER_SECRET_KEY`, or, if unset, the file `signing.key` on the manager's data volume | Login cookies and the keys of running sessions become invalid; people sign in again. |
| Checked-out configuration repositories | The manager's data volume (`MANAGER_REPOS_DIR`, default `/data/repos`) | Nothing: they are fetched again at the next sync. |
| Session workspaces | The shared volume (Docker volume `opencode-shared`, Helm `sharedStorage`) | Uncommitted work in sessions is gone. Results of finished runs are in the database. |
| Operator secrets | Your secret files or Kubernetes Secret | LLM connections and repository fetches fail until you restore them. |
| Tenant and team configuration | The Git repositories | Nothing on the platform side: Git is the source. |

A restore therefore needs the database **and** the credentials key that was in use when the backup was taken.

!!! warning
    `MANAGER_STORE` defaults to `memory`, which loses everything on restart. The compose stack and the Helm chart set `postgres`. If you start the manager any other way, set it yourself.

## Upgrades

The manager creates and migrates its database schemas when it starts, so an upgrade is: back up the database, deploy the new version, let it start.

```bash
helm dependency build charts/opencode-manager
helm upgrade platform charts/opencode-manager -n agents --reuse-values
```

The chart runs one manager replica and replaces it (`Recreate`), so the UI and API are unavailable while the pod restarts. Sessions run as their own pods.

On the evaluation stack: `git pull`, then `make dev`.

## Monitoring

`GET /metrics` on the public listener serves Prometheus metrics to operators (a request needs an operator's bearer token in jwt mode, or an operator's login cookie):

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `opencode_llm_tokens_total` | counter | `tenant`, `team`, `model`, `type` (`prompt`, `completion`) | Tokens processed through the LLM gateway |
| `opencode_llm_requests_total` | counter | `tenant`, `team`, `model` | LLM gateway requests |
| `opencode_llm_errors_total` | counter | `tenant`, `team`, `model` | LLM gateway requests that failed |
| `opencode_tool_calls_total` | counter | `tenant`, `team`, `tool` | Tool calls reported by the session monitor |
| `opencode_tool_blocked_total` | counter | `tenant`, `team`, `tool` | Tool calls a harness policy blocked |
| `opencode_tool_duration_ms_total` | counter | `tenant`, `team`, `tool` | Total tool execution time |
| `opencode_sessions_running` | gauge | — | Running sessions |

Useful things to alert on: a rising `opencode_llm_errors_total` (the LLM provider or its secret), `opencode_tool_blocked_total` (agents hitting policy), and a tenant whose last sync failed (on **Tenants**).

**Admin → Background jobs** shows tenant admins the recurring jobs of their tenant: **Sync repositories**, **Stop idle sessions**, and one line per workflow trigger, **Run on a schedule (…)** or **Check for new pull requests (…)**.

## Before you go live

Go through this list once. Each item names the setting or page that covers it.

**Sign-in**

- `MANAGER_AUTH_MODE` is `oidc` or `jwt`, never `login`.
- `publicScheme` is `https` and the gateway serves TLS, so the login cookie is `Secure` and carries the `__Host-` prefix. (It is host-only in either case.)
- `MANAGER_OPERATORS` and the operator role name only the people who run the platform.
- A person without a tenant is refused at login (try one).

**Secrets**

- The credentials key is set explicitly and a copy is stored safely.
- Operator secrets come from a Kubernetes Secret or protected files, not from the image or the repository.
- The Postgres password is changed from the chart's default.
- Every credential in `tenant.json` lists only the `hosts` it must reach, and `secret_hosts` covers every secret used for Git.

**Network**

- Your network plugin enforces NetworkPolicies, and `networkPolicies.enabled` is `true`.
- From inside a session, the public listener and the internet are unreachable (for a harness with `"network": "none"`).
- `trustedProxies` is narrowed to the proxies that really sit in front of the manager.
- `MANAGER_HTTP_GIT_HOSTS` is empty unless you really have a plain-http Git host.
- No tenant uses `file://` repositories, and nothing is mounted under the allowed local roots (`MANAGER_LOCAL_REPO_ROOTS`, default `/repos`).
- The Content-Security-Policy is the default (`MANAGER_CONTENT_SECURITY_POLICY` is unset).

**Tenants and teams**

- Only tenant admins can push to onboarding repositories; only team admins to team repositories.
- `allowed_images` lists only images you build or trust.
- MCP servers have a `tools` allow-list, and read-only ones where reading is enough.
- Harnesses use `"network": "none"` unless a workflow needs the internet.
- Harness policies deny `git push` where the platform should deliver changes.
- Every team has a `daily_tokens` budget and a `max_running_sessions` limit.
- Review workflows start in `dry-run` and move to `auto` once the team trusts the output.
- Internet-facing webhooks use HMAC with a timestamp; internal ones have allowed addresses.

**Operations**

- The database is backed up and a restore has been tried together with the credentials key.
- `/metrics` is scraped and sync failures are noticed.
- Someone knows how to verify and export an audit trail.

The reasoning behind each item is in the [Security model](../reference/security.md).

## You now have

- Limits per team and a view of usage and cost.
- An audit trail you can verify and export.
- A backup plan that includes the credentials key.
- A checklist for going live.

**Next:** this is the end of the path. Use the guides for click-by-click instructions ([members](../guide/member.md), [team admins](../guide/team-admin.md), [tenant admins](../guide/tenant-admin.md), [operators](../guide/operator.md)) and the reference to look things up: [server settings](../reference/settings.md), [roles](../reference/roles.md), [security model](../reference/security.md) and [troubleshooting](../reference/troubleshooting.md).
