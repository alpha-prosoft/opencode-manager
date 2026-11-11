# Troubleshooting

This page is for anyone who has hit an error. Each entry gives the symptom as the platform shows it, the cause, and the fix. Messages are quoted as the platform writes them; `…` stands for a name that varies. Where the fix is not yours to apply, the entry says whose it is.

## Signing in

| Symptom | Cause | Fix |
|---|---|---|
| *Login refused: your account belongs to no tenant* | The token has no `tenant` claim and no groups that agree on one. | Identity team: add a tenant group or the `tenant` attribute. See note 1. |
| *Login refused: the token names no valid username* | No `preferred_username` or `sub` that is a valid lowercase username. | Identity team: send one, or the operator derives one in a claims parser. |
| *Login refused: platform is the platform's own name* | The username is `platform`. | Use another account. |
| *Login refused: the claims parser's answer is invalid (…)* | A custom parser returned the wrong shape. | Operator: fix the parser file; the message names the problem. |
| *Login refused: the claims parser failed (…)* | A custom parser threw an error. | Operator: fix the parser file. |
| *Login failed: the identity provider could not be reached. Please try again later.* | The manager can't reach the provider's endpoints. | Operator: check the discovery URL and the network path; the manager's log has the detail. |
| *Login failed: the sign-in expired or was started in another browser. Please try again.* | The callback arrived without the cookie that started the login. | Sign in again in one browser window. |
| *Login failed at the identity provider: …* | The provider returned an error. | Read its message; commonly a wrong redirect URI or client secret. |
| *You're in acme but not in a team yet* | The token names the tenant but no team. | Identity team: add a team group. It takes effect at the next sign-in. |
| A team is missing | The group's team id is not a team `id` in `tenant.json`. | Tenant admin: compare `/<tenant>/<team>` with `tenant.json`. |
| Tokens are refused after a key rotation | The new keys are not loaded yet. | Operator: **Reload configuration** on **Tenants**; read the result for the identity provider. |

Note 1: with a provider that doesn't send Keycloak-style claims, every login ends this way until the operator installs a [custom claims parser](../story/identity.md#a-custom-claims-parser). Also check that the claims are in the token the platform reads (`MANAGER_OIDC_CLAIMS_SOURCE`).

The manager refuses to start when its sign-in settings are incomplete. The error in its log names the settings:

| Start-up error begins with | Fix (operator) |
|---|---|
| `MANAGER_AUTH_MODE=jwt requires …` | Set the audience, the issuer or discovery URL, and a key source. |
| `MANAGER_AUTH_MODE=oidc requires MANAGER_OIDC_ISSUER or MANAGER_OIDC_DISCOVERY_URL, and MANAGER_OIDC_CLIENT_ID` | Set them. |
| `MANAGER_OIDC_CLAIMS_SOURCE: unknown …` | Use `id_token`, `access_token`, `userinfo`. |
| `… is not supported (use …)` naming a `JWKS_DEFAULT_ALGORITHM` setting | Use one of the listed algorithms. |
| `Unknown MANAGER_AUTH_MODE …` | Use `jwt`, `oidc` or `login`. |
| `Claims parser … not found (MANAGER_CLAIMS_PARSER, MANAGER_CLAIMS_PARSER_FILE)` | Fix the parser's name or file. |

## Pages you can't open

| Symptom | Cause | Fix |
|---|---|---|
| **Not your tenant**: *This page belongs to globex* | The URL is under another tenant. | Use the link back to your tenant. Nothing of the other tenant is loaded. |
| **Not your tenant** as an operator | Operators without a tenant can't open tenants' pages. | Go to **Tenants**. |
| *You're not in Demo team. Only its members see its workflows, runs and sessions; …* | Your token doesn't name the team. | Ask for membership at the identity provider. |
| *Only members of … can see its runs.* | The run belongs to a team you are not in. | As above. |
| "Not found" for a run, session or team | It belongs to another tenant or was deleted. | Check the link. |
| *Only administrators of this tenant see its admin pages.* | You are not a tenant admin. | — |
| *Only platform operators manage tenants.* | You are not an operator. | — |

## Held on Connect your accounts

**Symptom.** Every page sends you to **Connect your accounts**, **Do this later** is disabled, and the page says *Your teams require the credentials marked required. The platform opens once you have added them.*

**Cause.** You lack your own value of a required credential. Your tenant (`"required": true` in `tenant.json`) or one of your teams (`required_credentials` in `team.json`) requires it. A team's or the tenant's value doesn't count.

**Fix.** Add the credential on that page; its help text says where to create it.

| If | Then |
|---|---|
| The check keeps failing (*Not saved. …*) | Check the value. If it is right, the credential's `test` can't succeed; a tenant admin fixes it in `tenant.json`. **Admin** stays open to tenant admins while they are held. |
| Nobody can meet the requirement | Tenant admin: remove `required` in `tenant.json`. Team admin: remove the id from `required_credentials`. Then sync. |
| *Could not check your credentials before going on: …* | Select **Try again**. If it persists, the operator reads the manager's log. |
| A member is held but a tenant admin of the same team isn't | Expected: requirements apply to teams named in a person's token, and that admin is not a member. |

## Starting work

| Symptom | Status | Cause | Fix |
|---|---|---|---|
| *Team Operations requires your own Jira credential. Add it under Accounts, then start again; team and tenant values don't count.* | 422 | A credential required of you is missing. | Add your own value under **Accounts**. |
| *Team demo already has 10 running sessions (limit 10). Stop one and retry.* | 422 | The team is at `budget.max_running_sessions`. | See below. |
| *Workflow demo/… does not exist or is invalid* | 422 | The workflow failed validation at the last sync. | Team admin: see [Invalid definitions](#invalid-definitions). |
| *Only members of … can work in it. …* | 403 | The workflow belongs to a team you are not in. | Ask for membership. |
| *Workflow … is not interactive: team sessions need an interactive workflow* | 422 | **Team session** on an automated workflow. | Use a `session` workflow. |
| *Run is still active; cancel it before running it again* | 409 | Retry of an unfinished run. | Cancel it first, or wait. |
| *Run is already completed* (or `failed`, `cancelled`) | 409 | Cancelling a finished run. | — |
| *Only the run's creator or a team admin can do that* | 403 | Cancelling someone else's run. | Ask them or a team admin. |

**At the session limit.** The refusal applies to people starting a workflow or a team session, forking, running a run again and restarting a stopped session. Stop a session you no longer need, or wait for idle ones to stop. A team admin can raise the limit in `team.json`; a tenant admin can select **Stop idle sessions in acme** on **Admin → Overview**. A run the platform starts by itself is accepted and then fails with the same message.

## Working in a session

When you can't work in a session, its page says why and offers **Fork**. Forking is the fix in each case:

| On the session's page | Cause |
|---|---|
| *This session is locked, so nobody can continue in it. …* | The owner locked it. This can't be undone. |
| *This session works with bob's personal credentials, so only bob can work in it. …* | It would act with the owner's accounts. |
| *This session is private to bob. …* | It is shared with nobody; you see it as an admin. |
| *Only members of Operations can work in this team session …* | A team session of a team you are not in. |

Other refusals:

| Symptom | Cause | Fix |
|---|---|---|
| *This session is being deleted* (409) | **Start** during a delete. | — |
| *This session was not started from a workflow, so it can't be forked* | Nothing to take its sandbox from. | — |
| *Only the owner or a team admin can change this session* | Renaming, sharing, stopping or deleting someone else's session. | Ask the owner or a team admin. |
| *Only the owner can lock a session* | Admins can't lock other people's sessions. | — |
| *The session is already locked* (409) | Locking twice. | — |
| A session stopped by itself | It was idle longer than its harness or team allows. | Open it again; its workspace is kept. |

What the agent runs into:

| Symptom | Cause | Fix |
|---|---|---|
| 429 *Team … has used its daily token budget* | `budget.daily_tokens` is used up for the UTC day. | Wait, or the team admin raises the budget. |
| 403 *Model … is not allowed for this session* | The model is outside the harness's `llm.models`. | Team admin: add it to the harness. |
| 503 *LLM connection … is not configured* | The connection is gone from `tenant.json`. | Tenant admin: restore it. |
| 503 *Secret … is not configured* | The connection's secret has no value. | Tenant admin sets it under **Admin → Credentials**, or the operator provides the file. |
| 503 *LLM connection … needs base_url* | An `openai-compatible` connection without `base_url`. The sync doesn't catch this. | Tenant admin: add `base_url` in `tenant.json`. |
| 502 *Could not reach LLM connection …* | The upstream doesn't answer. | Check `base_url` and the network path from the manager. |
| *… needs your … credential. Add it under Accounts in the platform.* | No value of the MCP server's credential applies. | Add your own under **Accounts**, or an admin sets a shared value. |
| *Tool … is not allowed on …* | The tool is outside the server's `tools` allow-list. | Tenant admin: add it in `tenant.json`. |
| *This harness may not use MCP server …* | The harness doesn't list the server. | Team admin: add it to the harness's `mcp`. |
| 502 *… is unreachable* on a tool call | The manager can't reach the MCP server. | Check the server and its `url`. |
| *Blocked by platform policy* | The command or path matches the harness's `policy`. | Intended; the team admin adjusts the policy if it is too broad. |

**Admin → LLM connections** has **Test connection**, which shows whether a connection's address and secret work.

## Credentials

| Symptom | Cause | Fix |
|---|---|---|
| *Not saved.* followed by a reason (422) | The credential's `test` rejected the value. | Check the value and its permissions. |
| *… is managed by your tenant or team admins* | `provided_by` doesn't include `user`. | An admin sets it. |
| *… has no team values (see its provided_by)* | `provided_by` doesn't include `team`. | Tenant admin: add `team` if teams should set it. |
| *… is personal only: every user sets their own value* | `provided_by` is only `user`. | Each person adds their own. |
| *… needs a username as well* | A `basic` credential without a username. | Enter both. |
| *Unknown credential* (404) | A personal or team value for an id `tenant.json` doesn't declare. | Tenant admin: declare it. |
| **Missing** on **Admin → Credentials** | No tenant value and no operator secret file. | Set the value there, or the operator provides the file. |
| An automated run lacks a credential that people have | Automated runs never use personal values. | Set a team or tenant value. |

## Sync

| Symptom | Cause | Fix |
|---|---|---|
| *The sync failed; the manager's log says why* | An error whose text may name server paths or hosts. | Operator: read the manager's log. |
| *tenant.json: not found at the root of the onboarding source* | The file is missing or elsewhere. | Tenant admin: put it at the repository root. |
| `tenant.json:` and a field message | The file is invalid. | See the [tenant.json reference](tenant-json.md#checks-across-fields). |
| A team shows an error but still works | Its last good state is in use. | Team admin: see the [team.json messages](team-json.md#sync-messages). |
| *file:// repositories must live under …* | Outside `MANAGER_LOCAL_REPO_ROOTS`. | Use https or ssh, or the operator mounts it under an allowed root. |
| *http:// is only allowed for the hosts in MANAGER_HTTP_GIT_HOSTS, not …* | A plain-http Git URL. | Use https, or the operator allows the host. |
| *only https://, ssh (git@host:repo) and file:// repositories are supported* | Another URL scheme. | Change the URL. |
| *archive sources must use https://* | A `url` source over http. | Use https. |
| *not a valid .tar.gz or .zip archive: …* | The download is not an archive. | Fix the source. |
| *local paths must live under …* | A `path` source outside the allowed roots. | As for `file://`. |
| A pushed change doesn't show up | No sync has run since (every 10 minutes by default). | Tenant admin: **Sync acme's repositories** on **Admin → Overview**. Operator: **Sync now** on **Tenants**. |
| *Tenant … already exists* (409) | The name is configured or registered already. | — |
| *Invalid tenants (MANAGER_TENANTS, MANAGER_TENANTS_FILE): …* at start | The tenant list is invalid. | Operator: fix the entry the message names. |
| *… names a page of the UI …; choose another …* | A tenant name or team id collides with a path of the UI. | Choose another; the message lists the reserved words. |

## Invalid definitions

The team's page shows the reason next to each invalid agent, harness and workflow. After a fix, the definition becomes valid at the next sync. The most common messages:

| Message | Fix |
|---|---|
| `agent … does not exist`, `harness … does not exist` | Create the file or correct the id. |
| `options.…` with a schema message | Compare with the activity's options on the **Activities** page. |
| `… not found in the team definitions` | The file must be inside `.platform/`. |
| `credential … is not granted to team … in the onboarding repository` | Tenant admin: grant it to the team in `tenant.json`. |
| `MCP server … is not in the team's team.json mcp_servers` | Add it there, or remove the list. |
| `runs automatically, but credential … only has personal values; …` | Tenant admin: add `tenant` or `team` to its `provided_by` and set that value. |

Every message is in [Workflows, agents and harnesses](definitions.md#validation-messages).

## Webhooks

The caller is told only the status. The reason is on the webhook's page under **Recent calls**; calls answered 404, 405 or 429 are not listed there.

| The caller gets | Cause | Fix |
|---|---|---|
| `401` | Missing or wrong secret or signature. | See below. |
| `403` | The caller's address is not allowed: *Source … is not allowed*. | See below. |
| `404` | Wrong id or tenant in the URL, or the webhook is disabled or deleted. | Copy the URL from the webhook's page; enable it. |
| `405` *Use POST or PUT* | `GET` on a webhook that doesn't allow it, or another method. | Use `POST`, or allow GET (not possible with HMAC). |
| `422` with `errors` | The mapped inputs fail the workflow's input schema. | Use **Preview inputs** with a real sample and fix the mapping. |
| `429` | Over the rate limit. | Honour `Retry-After`, or raise the limit (up to 600 a minute). |
| `500` | The manager failed while handling the call. | Read the error under **Recent calls**, then the manager's log. |
| `202`, but the run fails | The run started and failed later. | Open the run from **Recent calls**. |

**On 401.** Compare the calling system's configuration with the commands on the webhook's page. For a signature: sign the raw body exactly as sent, with the same algorithm; with a timestamp header sign `<timestamp>.<body>` and check the caller's clock. The reasons recorded are listed in the [Webhooks reference](webhooks.md#delivery-log).

**On 403.** If the recorded source is your proxy's address, the operator adds the proxy to `MANAGER_TRUSTED_PROXIES`. Otherwise add the caller's address or range to the webhook.

When saving or testing a webhook:

| Message | Fix |
|---|---|
| *A signed (HMAC) webhook maps inputs from the body only: …* | Map from body paths, the whole body or fixed values. |
| *A signed (HMAC) webhook can't allow GET: …* | Turn off GET, or use another auth type. |
| *A webhook's scope can't change; create a new webhook instead* | Create a new one in the right scope. |
| *Only tenant admins manage tenant webhooks*, *Only admins of team … manage its webhooks* | Saving or deleting needs that role. |
| *Only admins of the webhook's scope can do this* | **Send test** needs someone who manages the webhook. |

All save messages are in the [Webhooks reference](webhooks.md#messages-when-saving).

## API calls from scripts

| Symptom | Cause | Fix |
|---|---|---|
| A `POST` with the login cookie is refused | Requests that change state and carry cookies must come from the UI's origin. | Send the header `X-Requested-With`, for example `-H "X-Requested-With: cli"`. |
| *Not authenticated* (401) | No valid token or cookie. | Sign in again; in jwt mode send `Authorization: Bearer …`. |
| *Platform operators only* on `/metrics` or reload | The caller is not an operator. | Use an operator's token or cookie. |
| A large request is refused | The body exceeds `MANAGER_MAX_BODY_BYTES` (1 MB by default). | Send less, or the operator raises the limit. |

## After a restart or reinstall

| Symptom | Cause | Fix |
|---|---|---|
| Everything is gone after a restart | `MANAGER_STORE` is `memory`, the default outside compose and the chart. | Operator: set `MANAGER_STORE=postgres`. |
| Every stored credential stopped working | The credentials key changed. | Operator: restore the previous `MANAGER_CREDENTIALS_KEY` or `credentials.key` file. Without it, values must be entered again. |
| The UI is blank with a development (watch) build | The Content-Security-Policy blocks the development build. | Development only: `MANAGER_CONTENT_SECURITY_POLICY=off`. |

## The manager stops answering

**Symptom:** pages stay on their loading indicator and API calls time out, while the manager's container or pod is still running.

**What to do:** take a thread dump before you restart it. The dump says what every request is waiting for, and a restart throws that away.

```bash
# Docker Compose: the dump goes to the manager's log
docker kill --signal QUIT opencode-manager-manager-1
docker logs --since 1m opencode-manager-manager-1 > manager-threads.log

# Kubernetes (release "platform" in namespace "agents")
kubectl -n agents exec deploy/platform-opencode-manager -- kill -3 1
kubectl -n agents logs deploy/platform-opencode-manager --since=1m > manager-threads.log
```

The signal does not stop the manager. Attach the file to the issue you open, together with the manager's log of the minutes before.
