# Roles and visibility

This page is for anyone who needs to know exactly who can see or do what: the four roles, where they come from, what each may do, who sees a session and who may work in it, and how forking and locking behave.

## Where roles come from

Membership and roles come only from the claims in a person's token, through the [claims parser](../story/identity.md). No file on the platform lists members. A person belongs to one [tenant](concepts.md#terms) and has a role in each of its teams that their claims name.

| Role | Comes from (default parser) | Scope |
|---|---|---|
| Operator | `MANAGER_OPERATORS`, or the realm role `platform-operator` | The platform |
| Tenant admin | The realm role `tenant-admin` | One tenant |
| Team admin | The group `/<tenant>/<team>/admin` | One team |
| Member | The group `/<tenant>/<team>` | One team |

A tenant admin is an admin of every team of the tenant, also of teams they are not a member of. The UI says so ("tenant admin, not a member") and marks what they see only because of that role.

The username `platform` is the platform itself and never a person. Runs and sessions started by schedules, triggers, webhooks and **Team session** belong to it.

## What each role can do

| Action | Member | Team admin | Tenant admin | Operator |
|---|---|---|---|---|
| Run the team's workflows, start own sessions | Yes | Yes | Yes | — |
| See the team's page, workflows, runs and results | Yes | Yes | Yes | — |
| Cancel a run | Own runs | Any run of the team | Any run of the tenant | — |
| Rename, change **Shared with**, stop or delete a session | Own sessions | Any session of the team | Any session of the tenant | — |
| Start a stopped session, open it and work in it | Sessions they may work in (see [below](#who-may-work-in-a-session)) | The same rule: being an admin doesn't add this | The same rule | — |
| Lock a session | Own sessions only | Own sessions only | Own sessions only | — |
| Start a team session | — | Yes | Yes | — |
| Set their own credential values | Yes | Yes | Yes | — |
| See whether a team credential is set | Yes | Yes | Yes | — |
| Set and delete team credential values | — | Yes | Yes | — |
| Set and delete tenant credential values | — | — | Yes | — |
| Create and manage team and project webhooks | — | Yes | Yes | — |
| Create and manage tenant webhooks | — | — | Yes | — |
| Admin pages: people, usage and cost, LLM connections, credentials, MCP servers, background jobs | — | — | Yes | — |
| Sync the tenant's repositories | — | — | Own tenant | Any tenant |
| Stop idle sessions in the tenant | — | — | Yes | — |
| Register tenants, see every tenant's sync state | — | — | — | Yes |
| Reload configuration | — | — | — | Yes |
| Read `/metrics` | — | — | — | Yes |

An operator who has no tenant sees only **Tenants**. Being an operator gives no access to any tenant's data. A person can be an operator and a member of a tenant at the same time, if their claims say both.

## Tenant isolation

Every request works inside the caller's tenant, taken from their token.

| The caller asks for | Answer |
|---|---|
| Something of another tenant | 404, as if it did not exist |
| A team or run of their tenant that they may not see | 403 with the reason, so the UI can explain it |
| A team their tenant doesn't have | 404 |
| A page under another tenant's URL (`/globex/…`) | The page **Not your tenant**; nothing of it is loaded |

Lists contain only rows of the caller's tenant.

## URLs

Every page of a tenant lives under the tenant's name, like an owner and its repositories. The backend still takes the tenant only from the token.

| URL | Page |
|---|---|
| `/acme` | Home |
| `/acme/runs`, `/acme/runs/<id>` | Runs, a run |
| `/acme/sessions`, `/acme/sessions/<id>` | Sessions, a session |
| `/acme/projects` | Projects of your teams |
| `/acme/teams`, `/acme/harnesses`, `/acme/activities`, `/acme/accounts`, `/acme/webhooks`, `/acme/admin` | The other pages |
| `/acme/demo` | Team `demo` (`/acme/teams/demo` leads there too) |
| `/acme/demo/demo-service` | Project `demo-service` of team `demo` |
| `/platform/tenants` | Tenants (operators) |

- The team chosen in the sidebar is kept in the URL as `?team=<id>`, so links, reloads and the back button keep it. Pages with tabs take `?tab=`, for example `/acme/admin?tab=credentials` or `/acme/demo?tab=settings`.
- URLs of earlier versions (`/runs/<id>`, `/sessions/<id>`, `/teams/<id>`, `/tenants`, …) lead to the same page under your tenant and keep their query. Signing in returns you to the page you asked for.
- Because the first path segment is a tenant and the second may be a team, some names are reserved. A **tenant** can't be named `accounts`, `activities`, `admin`, `api`, `harnesses`, `healthz`, `hooks`, `login`, `metrics`, `platform`, `runs`, `sessions`, `setup`, `teams` or `tenants`. A **team id** can't be `accounts`, `activities`, `admin`, `api`, `harnesses`, `login`, `platform`, `projects`, `runs`, `sessions`, `setup`, `teams` or `webhooks`. The tenant list and `tenant.json` are refused with a message naming the word.

## Runs

A run belongs to the team whose workflow it executes. Every member of the team (and tenant admins) sees the team's runs and their results. People outside the team get 403: *Only members of demo can see its runs. Ask an admin of demo or your tenant admin to add you.*

| Action | Who | Refusals |
|---|---|---|
| Cancel a run | The person who started it, or a team admin | 403 *Only the run's creator or a team admin can do that*; 409 *Run is already completed* (or `failed`, `cancelled`) when it has finished |
| Run a run again | Anyone who can see the run | 409 *Run is still active; cancel it before running it again*; 422 when a required credential is missing or the team is at its session limit |

The service accepts a retry of any finished run. The UI offers **Run again** only on finished runs of automated workflows; an interactive workflow is started anew instead.

## Who sees a session

A session always belongs to a team. Who sees it is its **Shared with** setting (the field `visibility` in the API and in workflow files):

| **Shared with** | `visibility` | Shown to others as | Seen by |
|---|---|---|---|
| **Only me** | `private` | "Private to bob" | The owner |
| **Team Demo team** | `team` | "Team Demo team" | The team's members |
| **Everyone in acme** | `tenant` | "Everyone in acme" | Everyone in the tenant |

In addition, admins of the team and tenant admins see every session of the team. When they see one only because of that role, the session's page says so.

When you start a workflow, the dialog shows **Shared with**, preset to what the workflow's `visibility` says. Interactive workspaces start with **Only me**. The owner or a team admin changes it later in the **Shared with** section of the session's page.

## Who may work in a session

Seeing a session and working in it are different. Working in a live session means sending prompts with the credentials the session uses, so the rules are stricter.

| Session | Owner | Team admins, tenant admins | Team members | Others in the tenant |
|---|---|---|---|---|
| **Only me** | Open | See, fork | — | — |
| Shared with the team | Open | Open | Open | — |
| Shared with the tenant | Open | Open | Open | Open |
| Uses the owner's personal credentials | Open | See, fork | See, fork (if shared) | See, fork (if shared) |
| Team session | — | Open | Open | See, fork (if shared) |
| Locked | See, fork | See, fork | See, fork (if shared) | See, fork (if shared) |

The rows are checked from the bottom up: a locked session can't be opened by anyone; a team session only by the team; a session with personal credentials only by its owner.

Starting a stopped session follows the same table: whoever may work in a session may start it, and nobody else, admins included. A person's start is also refused (422) while the team is at its running session limit.

When you can't work in a session, its page says why and offers **Fork**:

| On the session's page | Reason |
|---|---|
| *This session is locked, so nobody can continue in it. Fork it to continue in your own copy.* | The owner locked it. |
| *This session works with bob's personal credentials, so only bob can work in it. Fork it to continue in your own copy, with your own credentials.* | Working in it would act with the owner's accounts. |
| *This session is private to bob. Fork it to continue in your own copy.* (admins: *This is bob's private session. As admin of Demo team you can see, rename and delete it, but only bob can work in it. …*) | It is shared with nobody; you see it as an admin. |
| *Only members of Operations can work in this team session (it uses Operations' credentials). Fork it to get your own private copy in Operations, using your own credentials.* | A team session of a team you are not in. |

Through the API, starting a session you may not work in answers 409 with a shorter form of the same reason (*This session is locked. Fork it to continue.* and so on). Starting a session that is being deleted answers 409 *This session is being deleted*.

## Team sessions

A team session belongs to the team, not to a person. It comes from:

- a team admin selecting **Team session** (shared with the team or the tenant, never **Only me**; only interactive workflows);
- a schedule, a pull request trigger or a webhook.

Team sessions work with the team's credentials, else the tenant's, never a person's. Only the team's members and tenant admins may work in them, however widely they are shared.

## Forking

Anyone who can see a session can fork it. A fork:

- copies the workspace and every conversation into a new session;
- **stays in the source's team**;
- is shared with **Only me**, the person who forked it;
- runs with **that person's** credentials (their own where set, else the team's, else the tenant's).

The fork is refused when the team is at its running session limit (422), and when the person lacks a credential the team requires of them. People forking from outside the team are not held to the team's requirements, because the requirements apply to the team's members.

A session that was not started from a workflow can't be forked.

## Locking

Only the owner can lock a session (others get 403 *Only the owner can lock a session*). A locked session stops and can never be continued, the owner included. Everyone who could see it can still read and fork it. Locking can't be undone; locking again answers 409 *The session is already locked*.

## Required credentials and Connect your accounts

A person who lacks a credential required of them (see [Credentials](credentials.md)) is held on the **Connect your accounts** page and can open no other page until they add it. Two pages stay open so a broken requirement can be repaired: **Admin** for tenant admins and **Tenants** for operators.
