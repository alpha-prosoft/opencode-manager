# Agent Platform — development guide

The platform is two Clojure projects:

- `service/` — the backend, an [edd-core](../edd-core) service (`manager-svc`)
  on Undertow. Event-sourced aggregates hold all state; background workers
  carry out side effects (sandboxes, agents, git, SCM). No Temporal.
- `web/` — the UI, an [edd-core-web](https://github.com/alpha-prosoft/edd-core-web)
  re-frame app (`manager-web`) on MUI through edd-web-primitives.


## Running

```sh
cd web && npm install && npm run fonts && clojure -M:shadow-cljs watch app   # or: release app
cd service && MANAGER_UI_DIST_DIR=../web/www MANAGER_DATA_DIR=/tmp/manager MANAGER_AUTH_MODE=login \
  MANAGER_DEV_USERS_FILE=../examples/dev-users.json MANAGER_OPERATORS=oscar \
  MANAGER_TENANTS='[{"name":"acme","onboarding":"file:///repos/acme-onboarding"}]' \
  MANAGER_PUBLIC_PORT_INTERNAL=18000 MANAGER_SANDBOX_PORT=18001 MANAGER_EDD_PORT=18081 \
  MANAGER_CONTENT_SECURITY_POLICY=off clojure -M:run    # CSP off only for a shadow-cljs watch build
cd service && clojure -M:test:unit          # backend unit tests
cd web && clojure -M:shadow-cljs compile test   # UI unit tests (node, jsdom where a DOM is needed)
cd web && clojure -M:it                     # Playwright tests against MANAGER_URL (default http://localhost:18000)
cd service && MANAGER_URL=http://localhost:8080 clojure -M:e2e   # end-to-end against the compose stack (real LLM)
cd service && MANAGER_URL=http://localhost:8080 clojure -M:e2e-ci   # only the Gerrit/Jenkins flows (compose.ci.yml)
```

The UI is served with a strict Content-Security-Policy (`manager-svc.edge.static/default-csp`). A shadow-cljs
`watch` build needs `eval` and its websocket, so run the service with `MANAGER_CONTENT_SECURITY_POLICY=off` while
developing with watch; a `release` build works with the default policy.

Settings are `MANAGER_*` env vars, see `service/src/manager_svc/config.clj`.
`MANAGER_STORE=memory` (default, data lost on restart) or `postgres`.
`MANAGER_AUTH_MODE=jwt` is the default and needs `MANAGER_JWT_AUDIENCE`, `MANAGER_JWT_ISSUER` or
`MANAGER_JWT_DISCOVERY_URL` (a full `.well-known/openid-configuration` URL; its issuer must match
`MANAGER_JWT_ISSUER` when both are set), and keys: `MANAGER_JWT_JWKS_URL`, `MANAGER_JWT_JWKS_FILE`, the discovery
document's `jwks_uri` or `MANAGER_JWT_SECRET`; the manager refuses to start without them. `login`
(password-free username login) is for local development only and must be set explicitly; it takes each
username's claims from `MANAGER_DEV_USERS_FILE`. `oidc` is the browser login through any OpenID Connect provider
(`manager-svc.oidc`, authorization code flow with PKCE, state and nonce): `MANAGER_OIDC_DISCOVERY_URL` and/or
`MANAGER_OIDC_ISSUER` (endpoints from the discovery document, by default `<issuer>/.well-known/openid-configuration`,
whose issuer must match a configured one), `MANAGER_OIDC_CLIENT_ID`, `MANAGER_OIDC_CLIENT_SECRET` or
`MANAGER_OIDC_CLIENT_SECRET_FILE` (neither: a public client), `MANAGER_OIDC_SCOPES` (default `openid profile email`),
`MANAGER_OIDC_REDIRECT_URL` (default `<public URL>/api/auth/oidc/callback`) and `MANAGER_OIDC_CLAIMS_SOURCE`
(`id_token` default, `access_token`, `userinfo`, comma separated, later ones winning). `compose.keycloak.yml` runs
it locally against Keycloak (realm in `deploy/keycloak`, users and passwords in the README's quick start and `docs/story/identity.md`). Tenants, the claims parser and operators are configured as
described in [Tenants, teams and people](#tenants-teams-and-people).

Identity provider key material (`manager-svc.idp`, one implementation for jwt and oidc mode; `<MODE>` is `JWT` or
`OIDC`) and the reloadable configuration files (`manager-svc.reload`: a cell holding the last good value, loaded
again when due, single flight, a failed load keeps the last good value and logs):

| Setting | Default | Meaning |
|---|---|---|
| `MANAGER_<MODE>_DISCOVERY_URL` | — | Discovery document; refreshed like the keys; a reload naming another issuer is refused |
| `MANAGER_<MODE>_JWKS_FILE` | — | Local JWKS instead of the URL; reloaded on the interval and when its mtime/size change |
| `MANAGER_<MODE>_JWKS_REFRESH_SECONDS` | 3600 | Reload interval of keys and discovery document |
| `MANAGER_<MODE>_JWKS_REFRESH_RETRY_SECONDS` | 10 | Least time between reload attempts: an unknown `kid` reloads at once within this limit, a failed reload retries after it |
| `MANAGER_<MODE>_JWKS_DEFAULT_ALGORITHM` | RS256 | `alg` of keys published without one (EC keys: their curve); RS256/384/512, ES256/384/512, anything else refused at start |
| `MANAGER_CONFIG_RELOAD_SECONDS` | 10 | How often the claims parser file and the tenants file are checked for changes |

A key verifies only tokens of its own algorithm; a token whose header names another one is refused. Operators
reload everything now with `POST /api/admin/reload-config` (`manager-svc.platform.reload`, operator only).
Tests move time with `with-redefs [reload/now-ms ...]`.

## Service architecture

```
browser, Traefik ──► edge public listener (8000) ──/api/query, /api/command──► edd (Undertow, 127.0.0.1)
                       login, static UI, session forward-auth,                │ commands → events → aggregates
                       metrics, credential values, audit export               │ queries
sandboxes ─────────► edge sandbox listener (8001)                             │
                       LLM + MCP gateways, /mcp result tools, telemetry       │
                     workers (scheduled jobs) ─────────────────────client─────┘
```

- The two listeners share nothing: the public one serves `routes` and `host-handlers`, the sandbox one only
  `sandbox-routes`. Compose binds them to different networks (`manager-public` on opencode-net,
  `manager-sandbox`/`manager` on opencode-sandbox); Helm gives the sandbox listener its own Service and
  NetworkPolicies. Sandboxes reach it at `MANAGER_SANDBOX_SERVICE_URL`. Traefik joins opencode-sandbox to reach
  session containers but listens only on its opencode-net address (alias `traefik-public`), so a sandbox can't
  reach the public listener through it either.
- Both listeners limit bodies (`MANAGER_MAX_BODY_BYTES`, a route may raise it with `:max-body-bytes`) and read
  times. Read a body with `edge.server/body-text`, after authenticating the caller.
- The public listener refuses state-changing requests that carry cookies unless they come from the UI's origin
  (`Sec-Fetch-Site: same-origin`, a matching `Origin`, or the `X-Requested-With` header the UI sends).
- Edge forwarding to edd sends only `:query` or `:commands` with fresh ids; failures reach people as
  `{:exception {:status :message}}` (`client/failure`), never internal details.
- The edge's own endpoints (credential values, webhooks) share `edge.api/person!` (token and identity or 401),
  `edge.api/json-object!` (the JSON body as a map or 422) and `edge.api/command!` (commands as the caller, effects
  settled). Whatever refuses such a request, the edge or the command, it answers the HTTP status with
  `{:detail message}`; a success answers edd's response.
- The caller's address (webhook allowed addresses, delivery log) is the TCP peer. `X-Forwarded-For` counts only
  when the peer is in `MANAGER_TRUSTED_PROXIES` (comma separated addresses, CIDRs or host names standing for
  their current addresses; default empty): then it is the last address of all its lines. compose trusts
  `traefik-public`, the chart `trustedProxies`.

- `manager-svc.main` starts edd, an in-process router (memory distributor),
  the edge and the workers.
- `manager-svc.system` is the process-wide map handed to everything: edd
  handlers get it as `(:manager/system ctx)`, edge handlers and workers as their
  first argument. Each domain contributes components to it.
- Domains live in `manager-svc.<domain>.*` with an entry point
  `manager-svc.<domain>.core` exposing `register`, `components`, `routes`,
  `sandbox-routes`, `host-handlers`, `workers` (contract in `manager-svc.system`).
  `manager-svc.module` lists the domains once; everything else derives from it.
  Handlers reach the system map with `(manager-svc.system/of ctx)`.
  Domains: `platform` (tenants, registry, specs, sync, catalog, harnesses,
  admin views, projects and the global search in `platform.navigation`), `sessions`, `runs`, `credentials`, `observability`, `webhooks`
  (inbound webhooks starting team workflows; see `docs/reference/webhooks.md`).
- Cross-domain calls go through the facade `manager-svc.<domain>.api`, plain
  functions of `system`. Do not require another domain's internals. In tests,
  replace other domains' facades with `with-redefs`.
- Commands are pure: they validate and return events. Side effects (start a
  container, clone a repo, prompt an agent) happen in workers, which read
  aggregates, act, and record the outcome with platform commands
  (`manager-svc.client/command`, role `:non-interactive`). Reactive jobs
  (`:reactive true`) run right after every UI command, so a "Start" click
  takes effect immediately.
- Queries may read live state (container status, OpenCode conversations).
- Aggregate ids are UUIDs. Tenants, teams and people are entity aggregates
  found through their edd identity (see below). Other things named by strings
  (credential values, schedules) use `(lambda.uuid/named "...")` via `:id-fn`
  with the tenant in the name; keep the strings as attributes. Runs and sessions are UUIDs generated by the caller (the UI
  generates the id of a run it starts, then navigates to it).
- Enumerations are keywords (`:running`, `:admin`); timestamps are ISO-8601
  strings from `manager-svc.time/now`.
- Authorization: every `reg-cmd`/`reg-query` declares
  `:auth {:role [...]}` with `:user` (people) and/or `:non-interactive`
  (workers, effects). Tenant, team and admin checks are data-dependent and done
  in the handler with `manager-svc.roles` over the caller's claims
  (`roles/caller`, `require-tenant!`, `require-tenant-admin!`,
  `require-team-admin!`, `require-operator!`); fail with `manager-svc.errors`.
  Every query and command works within the caller's tenant: another tenant's
  aggregate answers 404, and lists read only rows of the caller's tenant. Inside the tenant, a team or run the
  caller may not see answers 403 with the reason (`errors/forbidden!`), so the UI can explain it.
- The person is `(manager-svc.auth/user-id ctx)`. The username `platform` (`auth/platform-user`) is the
  platform itself and never a person; compare with `auth/platform-user?` or an explicit marker
  (`:platform-started` on runs, `sessions.api/platform-owned?`, `:team-session` on the session views the UI reads), never by prefix.
- `manager-svc.client/command` and `query` throw ex-info `{:status :message}` when edd answers with
  `:exception` or `:error`. Callers that don't read their own writes use `client/command-no-settle`; never
  hold a lock across `effects/settle!`.
- Shared small things live once: the shapes of usernames, identifiers and secret names and the platform's own
  name in `manager-svc.names`; the role sets of `:auth` (`roles/people`, `roles/platform-only`,
  `roles/people-and-platform`) in `manager-svc.roles`; `rows/by-id` for a row of a vector; `http/get-json`.
- What people read of a failure is a message the code wrote itself (`manager-svc.errors`, a sync's
  `source-error`/`git-error`, oidc's `refusal`). Any other exception is logged and shown as a fixed sentence
  (`registry/sync-failed`, `oidc/provider-unreachable`): its text may name server paths or internal hosts.
- SCM providers (Bitbucket, GitHub, Gerrit) are maps of functions made by `manager-svc.runs.scm/provider` from
  the source `{:provider :repository :url :web-url}` and the credential `{:secret :username}`; callers use
  `scm/post-review!`, `open-change!` and `update-change!`, which take a provider's own `:review`, `:deliver` and
  `:update` when it has them. A new provider is a factory in `scm/providers` and an entry in
  `platform.spec/scm-providers` (API host, clone URL or self-hosted); never branch on the provider name
  elsewhere. Plain-http git hosts must be listed in `MANAGER_HTTP_GIT_HOSTS`.
- Outgoing HTTP goes through `manager-svc.http` (timeouts, no redirects unless asked), OpenCode through
  `manager-svc.opencode`, git through `manager-svc.git`, secret files through `manager-svc.files`.
- edd answers any query result map with a truthy top-level `:error` as an
  error. Query results never carry `:error`: name it `:error-message` (UI) or
  wrap internal aggregates (`{:run ...}`, `{:session ...}`).
- Lists never search (edd's postgres simple-search stops at 600 results).
  They read entity aggregates (`manager-svc.directory`): one per tenant, team
  and person (see the identities below) and one for the platform, each with a
  fresh UUID found through its identity. Besides the entity's configuration it
  holds summary rows per collection, enough to render a list, at most 10,000
  per collection (`directory/max-rows`; the oldest is dropped when full).
  Rows carry the `:id` and `:version` of the aggregate they describe, so a late
  or repeated update never overwrites a newer one, and the `:tenant` they
  belong to.
  - Keep rows in step with `directory/reg-rows`: after a command producing
    one of the given events, its effect sends `:upsert-directory-row` /
    `:remove-directory-row` (role `:non-interactive`) for the aggregate.
    Commands stay pure; the first row creates the aggregate and its
    identity (a lost creation race fails on the identity's unique
    constraint and succeeds on redelivery). Commands writing an entity's
    configuration put `directory/with-creation` in front of their events and
    resolve the id with `directory/entity-id`.
  - Collections: team `:runs` `:sessions` `:schedules` `:credentials` (team
    values) `:webhooks` (team and project webhooks); user `:runs` (runs the person started) `:sessions` (owned; sessions the platform owns are listed with their team only)
    `:credentials` (own values, never the sealed value); tenant `:credentials`
    (tenant values), `:webhooks` (tenant webhooks) and `:entities` (every team and person with rows of the
    tenant, never capped); platform `:tenants` (never capped).
  - Read with `directory/tenant-rows` (rows of one tenant over several
    aggregates, newest copy per id), `directory/rows`/`rows-of`,
    `directory/identities ctx tenant "team"|"user"`, `directory/tenants` and,
    for workers over every tenant, `directory/all-identities`.
  - Effects travel in-process (memory distributor, router in
    `manager-svc.main`). `manager-svc.effects/settle!` waits for them;
    the edge's `/api/command` and `manager-svc.client/command` call it, so
    the UI and workers read their own writes. In tests use
    `support/apply!` (command, events, effects) or `support/settle!`.
  - An effect command needs an `:id` (postgres' command store); `reg-rows`
    sets the source aggregate's id and `:id-fn` resolves the directory.

## Tenants, teams and people

Everything is split Tenant → Team → User. A person belongs to one tenant and works in its teams; nobody reads
or changes another tenant's data.

**Identities** (`manager-svc.directory`), one aggregate each:

| Identity | Holds |
|---|---|
| `/platform` | registered tenants, index `:tenants` |
| `/tenant/<tenant>` | tenant.json, sync status and requests, tenant credential rows, index `:entities` |
| `/team/<tenant>/<team>` | its tenant.json entry, team.json, definitions, commit, status; runs, sessions, schedules, team credential rows |
| `/user/<username>` | the person's runs, sessions and credential rows (each row carries its `:tenant`) |

**Configuration files**

- Server (operator): `MANAGER_TENANTS` (JSON) or `MANAGER_TENANTS_FILE` (.json or .edn), a list of
  `{"name": "acme", "onboarding": "file:///repos/acme-onboarding"}`; the onboarding source is a location (git URL,
  https archive, absolute path, file:// directory) or a source map like a team repository
  (`{"type": "git", "url", "branch", "credentials", "path"}`). Operators add tenants at runtime with
  `:register-tenant`. `manager-svc.platform.tenants` validates the list at startup and reads the file again when it
  changes (`tenants/current`; an invalid file keeps the last good list). The `:config-reload` job
  (`manager-svc.platform.reload`) syncs tenants added to the file at once; removed ones are no longer known or
  synced, their aggregates stay.
- Tenant: `tenant.json` at the onboarding root (`MANAGER_TENANT_FILE`), schema `schemas/tenant.schema.json`:
  LLM connections, allowed images, secret hosts, credentials, MCP servers and teams (`id name description
  repository secrets notifications`). No member lists.
- Team: `.platform/team.json` in the team repository (`MANAGER_TEAM_CONFIG_FILE`), schema
  `schemas/team.schema.json`: `budget` (`daily_tokens`, `max_running_sessions`, `idle_minutes`), `required_credentials`,
  `llm_connections` (`[{id, models?}]`) and `mcp_servers` the team uses (leaving one out means everything the
  tenant grants the team), `projects` (`[{id, name?, branch?, scm: {provider, repository, url?, web_url?,
  credentials?}, ci?: {mcp_server, jobs, job?}}]`). A missing file is an empty configuration; an invalid one fails
  the team's sync (the last good one stays).
- A workflow may name a project (`"project": "demo-service"`); `manager-svc.platform.projects/apply-project`
  fills in the pull_request trigger's source and the activity's `scm`, `repository`, `pull_request` and `ci` it
  leaves out, before the registry validates it. The stored definition holds the resolved trigger and options, so
  runs never look projects up. A run of such a workflow carries the project id (`:project`, in its directory rows), and so do its
  session and forks of it, so lists filter by project.
- Both are malli schemas in `manager-svc.platform.spec` (snake_case JSON in, kebab-case inside); keep the JSON
  schemas in `schemas/` and `service/src/manager_svc/platform/schemas/` in step with them.
- The sync (`platform.api/sync!`) runs every tenant concurrently with its own lock and status, so one tenant's
  failure never holds up another; a tenant admin requests a sync of their tenant with `:sync-tenant`.

**Claims parser** (`manager-svc.claims`). Membership comes only from token claims. A parser is
`(fn [claims] {:username :email :name :tenant "acme" :teams [{:id "demo" :role :admin|:member}] :tenant-admin
:operator})` (claims with keyword keys as the token has them; only `:tenant` is required, unless `:operator`).
`MANAGER_CLAIMS_PARSER` names it (default `manager-svc.claims/default-parser`), `MANAGER_CLAIMS_PARSER_FILE` is a
.clj file loaded at startup, and again when it changes, that may define it (a file that fails to load keeps the last
good parser). Its output is validated with malli; invalid output, no tenant or
an invalid username refuse the login with a message (403). The default parser reads Keycloak's conventions:

| Claim | Meaning |
|---|---|
| `preferred_username` (else `sub`) | username |
| `email`, `name` | shown in the UI |
| `tenant` | the tenant (user attribute mapper); without it, the tenant all groups agree on |
| `groups` (full paths) | `/<tenant>/<team>` member, `/<tenant>/<team>/admin` team admin; other tenants' groups are ignored |
| `realm_access.roles` or `roles` | `tenant-admin` administers the tenant, `platform-operator` is an operator |

Login mode takes the claims of a username from `MANAGER_DEV_USERS_FILE` (username → claims, JSON or EDN) and runs
them through the same parser; `/api/auth/personas` lists those users. oidc mode verifies the provider's ID token
(JWKS, issuer, audience = client id, nonce, expiry), merges the claims of the configured sources, runs them through
the parser and issues the login cookie like login mode; a refusal returns to the login page with the parser's
message (carried once in the signed `oc_oidc` cookie, read through `/api/auth/config`). jwt mode passes the verified token's claims
through the parser on every request. The manager's own tokens (login cookie, handoff, session host) carry `sub`,
`email`, `name`, `tenant`, `teams` and the role flags, and edd's `resolve-user` puts them on `[:meta :user]`:
`{:id :email :name :tenant :teams {"demo" :admin} :tenant-admin :operator}`.

**Roles** (`manager-svc.roles`)

| Role | From | Can |
|---|---|---|
| Operator | `MANAGER_OPERATORS` or the parser's `:operator` | register tenants, list every tenant's sync state, sync any tenant, `/metrics` |
| Tenant admin | claims | the Admin pages of their tenant, tenant credentials, sync, stop idle sessions; admin of every team of the tenant |
| Team admin | claims | team credentials, team sessions (`:start-team-session`), manage the team's sessions and runs |
| Member | claims | run the team's workflows, see team sessions, manage their own |

**Credentials.** `provided_by` lists who may provide a value: `["tenant"]` (default), `"team"`, `"user"` in any
combination. Resolution is most specific first: the person's own value (if `user` is allowed), the team's (if
`team`), the tenant's (if `tenant`, or the credential is undeclared: an operator secret file
`<secrets-dir>/<tenant>/<NAME>`, then `<secrets-dir>/<NAME>`). LLM connection secrets are `["tenant"]` only.
Values are sealed at the edge for their owner (`tenant:<t>`, `team:<t>/<team>`, `user:<t>/<username>`), so
plaintext never reaches edd. Automated workflows can't rely on a credential only people provide.

**Required credentials** (`manager-svc.credentials.required`): `tenant.json` marks a credential `"required": true`
(everyone in the teams it applies to), a team's `team.json` lists `required_credentials` (its members); both need
`user` in `provided_by` (`platform.spec` refuses the file otherwise). Required of a person = the tenant's, plus those
of the teams their claims name. Only the person's own value satisfies one (team and tenant values are still used for
calls). `:start-run`, `:start-team-session`, `:retry-run` and `:fork-session` read what the caller lacks in the
team with the `:missing-credentials` dependency (`credentials.api/required-credentials-dep` →
`:get-missing-required-credentials`) and fail with `credentials.api/require-credentials!` (422 naming the team and
credentials). It applies to people whose claims name the team (`roles/claimed-team-ids`), exactly those
`:get-my-credentials` lists as `:missing-required`: never to the platform, someone forking from outside the team
or a tenant admin who isn't the team's member; running sessions are left alone.

**Sessions** always belong to a team (`:tenant`, `:team-id`). Visibility is `:private` (owner), `:team` or
`:tenant` (everyone in the tenant). Sessions started by triggers, schedules or `:start-team-session` are owned by
the platform and work with team credentials, so only the team's members (and tenant admins) may work in them;
others who can see them may fork them. A fork stays in the source's team, is private and owned by the person who
forked it. Runs, usage, audit and budgets carry the tenant and team.

## API contract

The UI calls edd queries and commands (`[::api/call {:query {...}}]`). Result
shapes follow the previous REST API (`manager/ui/src/api.ts` in git history) with kebab-case
keys and keyword enum values.

| Previous REST | edd | Domain |
|---|---|---|
| GET /me | query `:get-me` → `{:sub :email :name :tenant :tenant-admin :operator :teams [{:id :name :role :claimed-role}] :tenant-teams [{:id :name}]}` | platform |
| — | edge GET `/api/auth/config` → `{:mode "login"|"oidc"|"jwt" :error?}` (`:error` once, after a refused provider login) | platform |
| — | edge GET `/api/auth/oidc/login?next=` → 302 to the provider; GET `/api/auth/oidc/callback` → 302 to `/login?next=…&complete=1` with the login cookie, or to `/login?next=…` with the reason (oidc mode only, else 404); POST `/api/auth/logout` answers `{:redirect <end_session_endpoint>}` in oidc mode | platform |
| — | edge POST `/api/admin/reload-config` (operator) → `{:claims-parser :tenants :identity-provider {:discovery :keys} :new-tenants}`, each `{:status "reloaded"|"failed" :error?}` (an operator's reload always makes its own attempt; `:identity-provider` is null in login mode and with a shared JWT secret) | platform |
| GET /auth/personas | edge `/api/auth/personas` → `[{:username :tenant :roles}]` from the dev users file (login mode only, else 404) | platform |
| GET /platform | query `:get-tenant` (the caller's tenant) | platform |
| POST /platform/sync | cmd `:sync-tenant {:attrs {:tenant?}}` (tenant admin: own tenant, another one answers 404; operator: any) | platform |
| GET /tenants | query `:list-tenants` (operator) | platform |
| POST /tenants | cmd `:register-tenant {:attrs {:name :onboarding}}` (operator; listed at once, its first sync requested by the command's effect) | platform |
| GET /marketplace | query `:list-activities` | platform |
| GET /schemas | query `:get-schemas` | platform |
| GET /teams | query `:list-teams` (`:projects` as `:list-projects` shows them, without `:workflows`) | platform |
| GET /teams/{id} | query `:get-team {:team-id}` (`:projects` likewise: never a project's credential or clone URL) | platform |
| — | query `:list-projects {:team-id?}` → projects of the caller's teams `[{:id :name :team-id :team-name :branch :scm {:provider :repository :web-url} :repository-url :ci :workflows}]` | platform |
| — | query `:get-project {:team-id :project-id}` → `{:tenant :team {:id :name :role} :project :workflows [definition] :changes-workflow}` (team visibility as `:get-team`) | platform |
| — | query `:search {:text :limit?}` → `{:projects :teams :sessions}`, each ranked (exact, prefix, word start, anywhere; then shorter names) and at most `:limit` (default 5, at most 20) long; a session hit carries `:team-session` (then `:owner` is nil): projects of the caller's teams, teams of their tenant, sessions they may see; reads the registry and directory rows (`sessions.api/visible-rows`), never simple-search | platform |
| GET /harnesses | query `:list-harnesses` | platform |
| GET /admin/overview | query `:get-admin-overview` | platform |
| GET /admin/users | query `:list-admin-users` | platform |
| POST /admin/llm/{id}/test | query `:test-llm-connection {:connection-id}` | platform |
| GET /admin/schedules | query `:list-schedules` → `[{:id :kind :schedule|:pull-request :team :workflow :next-run :last-run :paused}]` | runs |
| GET /sessions | query `:list-sessions {:team-id? :project-id?}` (`:project-id` counts only with `:team-id`: project ids are unique per team) | sessions |
| GET /sessions/{id} | query `:get-session {:session-id}` | sessions |
| PATCH /sessions/{id} | cmd `:update-session {:id :attrs {:name :description :visibility}}` | sessions |
| POST /sessions/{id}/start, /stop | cmd `:start-session`, `:stop-session {:id}` (start: 409 while the session is being deleted; for a person, 422 when the team's other running sessions fill `max_running_sessions`) | sessions |
| DELETE /sessions/{id} | cmd `:delete-session {:id}` | sessions |
| POST /sessions/{id}/lock | cmd `:lock-session {:id}` | sessions |
| POST /sessions/{id}/fork | cmd `:fork-session {:id <new> :attrs {:source-id}}` (422 at the team's session limit) | sessions |
| GET /sessions/{id}/conversations | query `:list-conversations {:session-id}` | sessions |
| POST /admin/sessions/stop-idle | cmd `:stop-idle-sessions` | sessions |
| POST /teams/{t}/workflows/{w}/runs | cmd `:start-run {:id <new> :attrs {:team-id :workflow-id :inputs :visibility?}}` (`:visibility` `:private`/`:team`/`:tenant`: who sees the run's session; without it the workflow decides; a retry keeps it; 403 with the reason for a team of the tenant the caller isn't in, 404 for a team the tenant doesn't have) | runs |
| POST /teams/{t}/sessions | cmd `:start-team-session {:id <new run> :attrs {:team-id :workflow-id :inputs :visibility?}}` (team admin, interactive workflow; a platform-started run in the team's scope) | runs |
| GET /teams/{t}/workflows/{w}/pull-requests | query `:list-open-pull-requests {:team-id :workflow-id}` | runs |
| GET /runs | query `:list-runs {:team-id? :project-id? :limit?}` (newest first, at most 100; `:project-id` counts only with `:team-id`) | runs |
| GET /runs/{id} | query `:get-run {:run-id}` (a UUID) | runs |
| POST /runs/{id}/cancel, /retry | cmd `:cancel-run {:id}`, `:retry-run {:id <new> :attrs {:source-id}}` (retry: 409 unless the source run is finished, 422 at the team's session limit) | runs |
| GET /me/credentials | query `:get-my-credentials` → `{:credentials :pending :missing-required}` (every credential of the person's teams with its resolved `:state`, `:may-provide`, `:personal-only`, `:required`, `:required-by {:tenant :teams}` and per team `:teams [{:team-id :state :required}]`; `:pending` only those they may provide, required ones they lack first; `:missing-required` the required ones without their own value) | credentials |
| PUT /me/credentials/{id} | edge PUT `/api/credentials/{id}` body `{"secret" "username"?}` → edd command response, or the failure's HTTP status with `{:detail}` (the edge seals the value and sends `:set-my-credential {:attrs {:credential-id :sealed}}`) | credentials |
| DELETE /me/credentials/{id} | cmd `:delete-my-credential {:attrs {:credential-id}}` | credentials |
| POST /me/credentials/seen | cmd `:mark-credentials-seen {:attrs {:credentials}}` | credentials |
| GET /admin/credentials | query `:list-admin-credentials` (rows carry `:required` and `:required-by-teams`) | credentials |
| PUT /admin/credentials/{id} | edge PUT `/api/admin/credentials/{id}` body `{"secret" "username"?}` (sends `:set-tenant-credential` sealed) | credentials |
| DELETE /admin/credentials/{id} | cmd `:delete-tenant-credential {:attrs {:credential-id}}` | credentials |
| — | query `:list-required-credentials {:team-id}` → `[{:id :name :description :kind :required-by {:tenant :team}}]` (team members and admins) | credentials |
| GET /teams/{t}/credentials | query `:list-team-credentials {:team-id}` (team admins with hints; members get `{:id :name :description :provided-by :set}`) | credentials |
| PUT /teams/{t}/credentials/{id} | edge PUT `/api/teams/{t}/credentials/{id}` body `{"secret" "username"?}` (sends `:set-team-credential` sealed) | credentials |
| DELETE /teams/{t}/credentials/{id} | cmd `:delete-team-credential {:attrs {:team-id :credential-id}}` | credentials |
| GET /admin/mcp-servers | query `:list-mcp-servers` | credentials |
| GET /sessions/{id}/activity | query `:get-session-activity {:session-id}` | observability |
| GET /sessions/{id}/audit | query `:list-audit-events {:session-id :after :limit}` | observability |
| GET /sessions/{id}/audit/verify | query `:verify-audit {:session-id}` | observability |
| GET /sessions/{id}/audit/export | edge GET `/api/sessions/{id}/audit/export` | observability |
| GET /admin/usage | query `:get-admin-usage {:days}` | observability |
| /llm/v1/*, /mcp-gw/*, /api/telemetry | sandbox listener routes | observability, credentials |
| /mcp (submit_* tools) | sandbox listener route | runs |
| /metrics, /sessions/{sub}/{id}, /api/gateway/auth (forward auth) | public edge routes | observability, sessions |
| — | query `:list-webhooks {:team-id? :project-id?}` → rows the caller may see `[{:id :name :scope {:kind :team-id :project-id} :target {:team-id :workflow-id} :auth-type :enabled :last-delivery :can-manage :endpoint-path}]` | webhooks |
| — | query `:get-webhook {:webhook-id}` → the webhook with `:auth` (hint only for those who manage it, never the sealed secret), `:mapping`, `:allowed-ips`, `:deliveries` (last 50), `:can-manage`, `:endpoint-path` (team members 403 otherwise, other tenants 404) | webhooks |
| — | query `:list-webhook-targets` → `{:tenant :tenant-scope :teams [{:id :name :manage :projects :workflows [{:id :name :project :inputs-schema}]}]}` | webhooks |
| — | query `:preview-webhook {:target :mapping :request {:body :headers :query}}` → `{:inputs :missing :errors [{:field :message}] :valid}` (dry run, members of the target team, others of the tenant 403; a mapping that couldn't be saved answers its problems as `:errors`) | webhooks |
| — | edge PUT `/api/webhooks/{id}` body `{name description scope target auth {type header-name username algorithm timestamp-header tolerance-seconds secret?} mapping allowed-ips allow-get rate-limit-per-minute enabled}` (the edge seals `auth.secret` and sends only these fields as `:save-webhook`, whose schema is closed; tenant admins any scope, team admins their teams' and projects'; an HMAC webhook maps inputs from the body only and can't allow GET; a project's webhook never targets another project's workflow; failures answer their HTTP status with `{:detail}`); cmd `:delete-webhook {:id}` | webhooks |
| — | edge POST `/api/webhooks/{id}/rotate` → `{:secret}` once (sends `:set-webhook-secret` sealed); POST `/api/webhooks/{id}/test` body `{method body headers query}` → `{:status :body :request}` of a real signed call to the public endpoint (those who manage it), marked by the process's test token, which also lets it skip the allowed addresses; failures of both answer their HTTP status with `{:detail}` | webhooks |
| — | public POST/PUT (GET when allowed) `/hooks/{tenant}/{id}`, no cookies or login: 202 `{:run-id :delivery-id}`, 401 auth, 403 source address, 404 unknown/disabled/other tenant, 405 method, 422 `{:detail :errors [{:field :message}]}`, 429 rate limit (accepted calls per webhook, refused ones per webhook and source address; never recorded), 500 `{:detail}` when the delivery itself fails (recorded); starts `:start-run` as the platform with `:trigger {:type "webhook" :webhook-id :webhook-name :delivery-id}` and records `:record-webhook-delivery` | webhooks |

Secrets never travel in an edd command: edd persists command bodies in its
request log, so the edge seals a credential value (bound to the credential and
its owner) before it sends the command.

Commands return only success; the UI re-queries afterwards. A check that must
pass before a change (testing a credential against its service) is a query
dependency of the command (`:deps`), and the command fails with the check's
message.

## UI architecture

- A page is `manager-web.<page>.{core,events,subs,views,elements}`; pages are wired in `manager-web.core`, routes
  in `manager-web.routes`.
- URLs (`manager-web.routes`): tenant pages live under the tenant (`/acme`, `/acme/runs/<id>`,
  `/acme/sessions/<id>`, `/acme/projects`, `/acme/admin`); a team is `/acme/<team>` and its project
  `/acme/<team>/<project>`, like an owner and a repository; operator pages under `/platform` (`/platform/tenants`).
  The first segment after the tenant names a page (`routes/tenant-sections`), so those words are never team ids.
  Every route is marked `:conflicting`, so reitit matches them in order: fixed paths, old ones, then the tenant's.
  Never build paths by hand or dispatch `:edd.events/navigate` with a page: use `layout/link`, `layout/nav-props`,
  `layout/navigate!`, `[::routes/navigate page params query]` and `::data/run`'s `:navigate`, which put the
  person's tenant in. URLs of earlier versions are redirect pages (`routes/redirect-pages`, resolved in
  `manager-web.core` once `:get-me` is loaded); a tenant URL of another tenant renders `shell/not-your-tenant`
  instead of the page, so it loads nothing. The backend still takes the tenant only from the token.
- **Styling lives only in `manager-web.theme`**: palette (light and dark),
  typography, component defaults, `status-colors`, and the named `sx`
  fragments. Views use theme tokens (`"text.secondary"`, `"primary.main"`)
  and `(:fragment theme/sx)`; never hex colors or ad-hoc sizes. Add a fragment
  to the theme rather than inlining styles.
- Shared building blocks are in `manager-web.components.*`
  (`layout` = page header, section, empty state, detail list, stats...).
- Use edd-web-primitives (`web.primitives.components`, `:el-id` support)
  where they exist and MUI directly (`["@mui/material/Paper" :default Paper]`)
  otherwise. Tooltip children must be MUI components (`:>`), not Reagent
  wrappers, because they need a ref.
- Element ids: every element a test touches has an el-id from the page's
  `elements.cljc` (shared by views and tests). Views pass it as `:el-id`
  (primitives) or `:data-testid (el/id ...)` on MUI and hiccup elements
  (`:id (el/id ...)` where MUI puts the id on the input, e.g. TextField).
  Tests find elements with `manager-web.el/sel`. Never select by text, CSS
  classes or structure; add an id instead.
- Loading data: views render `[data/loaded {:key :query :interval :poll-while :error-el-id} render]`
  (`manager-web.data`), which loads the query on mount, polls while the page is visible, keeps only the latest
  reply per key and renders `(render data)` under a loading/error state; `data/poll` loads without rendering.
  Commands and action-like queries go through `[::data/run {:id :command|:query :refetch :navigate :on-success}]`
  with `::data/busy?` and `::data/error` subscriptions keyed by `:id`.
- Page state (tabs, filters, dialogs) lives in `[::data/ui page key default]` / `[::data/set-ui page key value]`.
  A page whose state should start fresh uses `{:init ::data/enter-page}` in its `core`, which resets its
  `::data/ui` entry.
- Backend calls: `[::api/call {:query|:command|:commands ... :on-success :on-failure}]`
  (`manager-web.api`). Every request other than GET carries `X-Requested-With: manager-web` (`api/headers`);
  the edge refuses state-changing cookie requests without it (or a same-origin `Sec-Fetch-Site`/`Origin`), so
  never call `js/fetch` directly. Buttons that trigger a call are disabled and show a
  spinner while it runs; reset the flag in both success and failure handlers.
- Keep events as named functions where they hold logic, so they can be unit
  tested.
- Where the person is: `manager-web.roles` turns `:get-me` (`:teams [{:id :name :role :claimed-role}]`,
  `:tenant-teams [{:id :name}]`) into labels; show teams by name (`roles/team-name`), roles with their source
  (`roles/role-in-team`: admin, member, or tenant admin who isn't a member), sharing from the viewer's side
  (`roles/session-visibility-label`: "Only me" / "Private to dave" / "Team Demo team" / "Everyone in acme") and say
  when an admin sees something only through that role (`roles/admin-access`). Use `format/possessive` for names.
- The team context lives in the URL (`?team=<id>`, `manager-web.components.team-context`): the sidebar switcher
  navigates with it, `team-context/selected` reads it (on a team's or project's page, the team of the path), and
  links to Home, Projects, Runs, Sessions and Harnesses carry it (`layout/nav-props page params query`, `layout/link {:query}`). Pages never keep
  their own team picker.
- Global search (`manager-web.search.*`): the sidebar box, the app bar icon and Ctrl+K / Cmd+K open a dialog that
  sends `:search` debounced; pure logic (rows, arrows, targets, stale replies) lives in `search.events`.
- Detail pages show `layout/breadcrumbs` (tenant / team / project / list / item, the tenant linking Home) and a
  `chips/team-chip`; a segment or link
  the viewer can't follow (a team they aren't in) is plain text, not a link to "not found". Pages acting on more
  than one thing show `layout/scope-chip` (`[:tenant name]`, `[:team name]`, `[:platform]`).
- Anything that starts work says the team, whose credentials it uses and who will see it (the run dialog's context
  summary); tables keep team, owner and sharing on narrow screens as a caption line (`:row-context`).
- `RawTypography` and `RawAlert` render only their first child; use MUI `Typography` for text with several
  children and wrap an alert's content in one element.
- People who lack a required credential see nothing but setup: `manager-web.core` asks
  `required-credentials/gate` (from `:get-my-credentials` `:missing-required`) before any page and sends them to
  `/setup?next=…`, which they leave only by adding it or signing out. Admin stays open to tenant admins and Tenants
  to operators, so a broken credential check can still be fixed. The service refuses to start work regardless.

## Testing

- Backend: `edd.test.fixture.dal` (`mock/with-mock-dal`, `mock/handle-cmd`,
  `mock/query`, `mock/verify-state`) with `manager-svc.test-support`
  (`support/ctx` with the directories registered, `as-user` with claims
  `(as-user ctx "bob" {:teams {"alpha" :admin} :tenant-admin true})`, tenant
  `support/tenant` ("acme") unless given, `person`, `as-platform`, `apply!`,
  `settle!`; test logins read `service/test/dev-users.edn`). Every domain has
  tenant isolation tests: a person of another tenant gets 404 or nothing. Domain tests require only their
  domain, not `manager-svc.module`, so one broken domain doesn't break others.
  External systems (Docker, Kubernetes, git, HTTP) sit behind small protocols
  or functions that tests replace.
- End to end: `service/test-e2e/manager_svc/e2e.clj` (alias `:e2e`) drives a
  running compose stack through the API, Traefik and `docker exec` probes. `e2e_ci.clj` (alias `:e2e-ci`, and
  part of `:e2e` when Gerrit and Jenkins answer) covers the Gerrit review and Jenkins build flows against
  `compose.yml` + `compose.ci.yml`. `e2e_webhooks.clj` (part of `:e2e`) calls `/hooks/...` with every auth type and
  follows a webhook-started analysis run.
- UI unit tests: `web/test/unit/manager_web/**/*_test.cljs`, run on node by the shadow-cljs `:test` build
  (`clojure -M:shadow-cljs compile test`). Test event functions and subs as plain functions; where a DOM is
  needed (sanitizing Markdown) use `jsdom` (`(.-window (JSDOM. ""))`).
- UI: Playwright integration tests in `web/test/it/manager_web/*_it.clj` with
  `testing.pw` (`with-ui`, `step`) and `manager-web.helpers`, against a running stack (`MANAGER_URL`). They drive
  real flows (start a workflow and follow it to its result, session actions, credentials, admin actions), not
  only rendering. `oidc_it` logs in through Keycloak (stack with `compose.keycloak.yml`, skipped otherwise); it
  finds Keycloak's login inputs by their stable ids (`#username`, `#password`, `#kc-login`), the only exception to
  the el-id rule, since that page is third-party.
- `docs/test-coverage.md` maps every feature to its unit test, API e2e check and UI IT test. Keep it current
  when adding a feature.
- End-user documentation is the MkDocs site in `docs/` (`mkdocs.yml`; published by `.github/workflows/docs.yml`):
  `docs/story` (the path from installation to a working team), `docs/reference`, and `docs/guide`, whose pages
  and annotated screenshots are generated by `web/test/guide/manager_web/*` (`make guide`; each guide is shot as a
  person with exactly that role). Change a guide's text in that code, not in `docs/guide/*.md`. The guides are made
  on the documentation stack, so that they are the same on every run and cost nothing: `make docs-stack` (`clean`,
  then `dev-full` plus `compose.docs.yml`) starts `llm-script` (`images/llm-script`, a stateless OpenAI-compatible
  server that plays `scenarios.json`: per task a fixed list of assistant turns with text, tool calls and token
  usage; the scenario is found by phrases in the conversation, the turn by its number of assistant messages) and
  publishes the docs variant of the examples (`SEED_VARIANT=docs scripts/seed-local-repos.sh`: the LLM connection
  points at `llm-script`, operator secrets are the placeholders of `deploy/docs-secrets`, the scheduled workflow runs every 8 minutes, one commit per repository with a fixed
  date; `examples/` stays as shipped). `clojure -M:guide` then plays the cast once per stack
  (`manager-web.guide-seed`: acme's people start runs, share sessions, add credentials and call a webhook in a
  fixed order under fixed ids) before it shoots the guides. A new figure that needs agent output gets a scenario
  and, if it must exist beforehand, a step of the seed. `make docs-all` is `docs-stack`, `guide`, `docs`;
  `make clean && make dev-full` goes back to the examples' real LLM connection. `make docs-check` (`scripts/check-docs.py`, also in CI) renders the
  documented Helm commands and checks every Helm value and `MANAGER_*` setting the docs name against the chart and
  `config.clj`; keep the docs in step when a setting, a chart value or a label on screen changes.

## Clojure conventions

- No comments that restate code; structure code so it reads well. Docstrings
  on namespaces and non-obvious public functions only.
- `let` bindings: name on its own line, value on the next, blank line between
  pairs. Prefer named `let` steps over threading macros, `get-in` over `->`
  for nested access.
- Tests: newline between expected and actual, each `(is ...)` on its own:

```clojure
(is
 (= :running
    (:status session)))
```
