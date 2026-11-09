# Credentials

This page is for tenant admins, team admins and members who need the exact rules for credentials: who may provide a value, which value a call uses, what "required" means, how values are stored, and what the platform answers when one is missing.

## Terms

| Term | Meaning |
|---|---|
| Credential | A named secret a tool needs, declared in `tenant.json` under `credentials` (see the [tenant.json reference](tenant-json.md)). |
| Value | The actual secret. A credential can have one tenant value, one value per team and one value per person. |
| Operator secret | A value the platform operator provides as a file or environment variable. It counts as a tenant value. |

## Who may provide a value

`provided_by` on the credential lists the scopes that may set a value. The default is `["tenant"]`.

| Scope | Who sets it | Where | How many values |
|---|---|---|---|
| `tenant` | Tenant admins | **Admin**, tab **Credentials** | One per tenant |
| `team` | The team's admins and tenant admins | The team's page, **Team settings** | One per team |
| `user` | Each person | **Accounts**, or **Connect your accounts** after sign-in | One per person |

Any combination is allowed. Typical choices:

| `provided_by` | Use it for |
|---|---|
| `["tenant"]` | A shared service account nobody else should handle. LLM connection secrets must be this. |
| `["tenant", "team"]` | A shared account, which a team may replace with its own. |
| `["tenant", "team", "user"]` | A shared read-only account by default; people add their own to act as themselves. |
| `["user"]` | A tool that must always act as the person. No automated workflow can use it. |

A credential's `teams` restricts which teams it applies to. People are asked only for credentials that apply to one of their teams.

## Which value a call uses

Resolution is most specific first. For a call made in a session:

1. the session **owner's own value**, if `provided_by` includes `user` and they have set one;
2. else the **team's value**, if `provided_by` includes `team` and the team has one;
3. else the **tenant's value**, if `provided_by` includes `tenant`:
    1. the value a tenant admin set;
    2. else the operator's file `<secrets-dir>/<tenant>/<NAME>`;
    3. else the operator's file `<secrets-dir>/<NAME>`, shared by every tenant;
    4. else the environment variable `PLATFORM_SECRET_<NAME>`;
4. else the call fails. For an MCP tool call the agent is told *… needs your … credential. Add it under Accounts in the platform.*

`<secrets-dir>` is `MANAGER_SECRETS_DIR`, `/config/secrets` by default: `local/secrets/` on the compose stack, the Secret named by `platformSecrets.existingSecret` on Kubernetes. For a `basic` credential provided this way, the username is the file `<NAME>.username` (or `PLATFORM_SECRET_<NAME>_USERNAME`).

A secret that is used but not declared under `credentials` (for example an LLM connection's secret) has a tenant value only.

**Team sessions and automated runs have no person.** Sessions started by a schedule, a trigger, a webhook or **Team session** belong to the platform, so step 1 never applies to them: they use the team's value, else the tenant's. A workflow that starts automatically and needs a credential whose `provided_by` is only `user` is invalid at sync.

**A fork uses the forking person's values.** The same order applies, with their own value first.

Every MCP tool call is written to the session's audit trail with which kind of value was used: a personal one or a shared one.

## Where values are sent

A value is sent only to the hosts listed in the credential's `hosts`. This applies to MCP servers (the host of the server's `url`), Git checkouts and pull request APIs. For Git checkouts, hosts listed for the secret under `secret_hosts` in `tenant.json` are allowed as well; that list is meant for secrets not declared as credentials.

Values are never sent into a sandbox. The MCP gateway adds the value to the tool call on the platform side; Git operations and SCM API calls run in the manager.

## Kinds

| `kind` | A value is | Notes |
|---|---|---|
| `token` | One secret | |
| `basic` | A username and a secret | Saving without a username is refused: *… needs a username as well*. Used for Atlassian accounts, Gerrit (username and HTTP password) and Jenkins (user and API token). |
| `ssh_key` | A private key | For ssh Git URLs. |

A secret can be at most 16384 characters.

## Checking a value before it is saved

If the credential has a `test`, every save checks the value first and stores it only when the check passes. A failed check answers 422 with *Not saved.* followed by what the check found. The button is then **Check and save** with the credential's name; without a `test` it is **Save** with the name.

| `test` | Check |
|---|---|
| `{"url": "…"}` | The URL is called with the value. A 2xx answer passes; any other answer, or a redirect to a login page, fails. |
| `{"mcp_tool": "whoami"}` | That tool of the MCP server using the credential is called through the server. Use this for services only the MCP server can reach. |

## Required credentials

A required credential must be provided **by the person themselves**.

| Declared by | How | Applies to |
|---|---|---|
| The tenant admin | `"required": true` on the credential in `tenant.json` | Everyone in the teams the credential applies to (its `teams`) |
| A team admin | The credential's id in `required_credentials` of the team's `team.json` | The team's members |

Both need `user` in the credential's `provided_by`; the sync refuses the file otherwise.

The rules:

- **Only the person's own value satisfies the requirement.** A team or tenant value is still used for calls, but doesn't count.
- **It applies to teams named in the person's claims.** A tenant admin who administers a team without being its member is not held to that team's requirements, and neither is someone who forks a session from outside the team.
- **It never applies to the platform.** Scheduled, triggered and webhook-started runs need no person's value.
- **Running sessions are left alone.** A new requirement doesn't stop work in progress.

What a person who lacks a required credential experiences:

| Where | What happens |
|---|---|
| Every page | They are sent to **Connect your accounts** (`/setup`). **Do this later** is disabled and the page says *Your teams require the credentials marked required. The platform opens once you have added them.* They leave only by adding the credential or signing out. |
| Admin (tenant admins), Tenants (operators) | Stay open, so that a requirement nobody can meet can be repaired. |
| Starting a run, a team session, a retry or a fork in the team | Refused with `422`: *Team Operations requires your own Jira credential. Add it under Accounts, then start again; team and tenant values don't count.* |

**Connect your accounts** lists required credentials first. **Team settings** and **Admin → Credentials** show which credentials are required and whether `tenant.json` or `team.json` requires them.

In the example tenant, PagerDuty is required by the tenant of everyone in Operations, and Operations requires Jira of its members.

## What people see

**Accounts** lists every credential of the person's teams with:

- the state of the value that applies: their own, the team's, the tenant's, or missing;
- per team, which value applies there, for example "Demo team: team value · Payments: tenant value";
- whether they may provide it themselves;
- whether it is required, and by whom.

Admins get links to where the shared values are set (**Set for Demo team**, **Set for acme**).

Team members who are not admins see, for team credentials, only whether a value is set.

## Storage

- A value is **sealed at the edge**: the public listener encrypts it, bound to the credential and its owner (`tenant:acme`, `team:acme/demo`, `user:acme/bob`), before anything else in the platform sees it. Commands, events and logs carry only the sealed value.
- Encryption is AES-GCM under `MANAGER_CREDENTIALS_KEY` (32 bytes, base64). If the setting is unset, a key is generated once into `credentials.key` on the manager's data volume.
- A value is **never shown again**. The API returns only a short hint.
- Because a sealed value is bound to its credential and owner, it can't be moved to another credential, person, team or tenant.

!!! warning
    Whoever loses the credentials key loses every stored value. Back it up separately from the database.

## Messages

| Message | Status | Meaning |
|---|---|---|
| *Unknown credential* | 404 | Setting a personal or team value for an id that is not declared in `tenant.json`. (A tenant value can be set for an undeclared secret, such as an LLM connection's.) |
| *… is managed by your tenant or team admins* | 403 | A person tried to set a credential whose `provided_by` lacks `user`. |
| *… is not used by your teams* | 403 | The credential's `teams` don't include any of the person's teams. |
| *… has no team values (see its provided_by)* | 422 | A team admin tried to set a credential whose `provided_by` lacks `team`. |
| *… is not used by team …* | 403 | The credential's `teams` don't include that team. |
| *… is personal only: every user sets their own value* | 422 | A tenant admin tried to set a credential whose `provided_by` is only `user`. |
| *… has no tenant value (see its provided_by)* | 422 | The credential's `provided_by` lacks `tenant`. |
| *A secret is required*, *The secret is too long*, *… needs a username as well* | 422 | The submitted value is incomplete or too large. |
| *Not saved.* followed by the check's finding | 422 | The credential's `test` rejected the value; nothing was stored. |
| *The credential could not be checked* | 422 | The check gave no result; nothing was stored. |
| *Only members of team … can see its credentials* | 403 | Not a member of the team. |
| *Team … requires your own … credential. …* | 422 | A required credential is missing; see above. |
