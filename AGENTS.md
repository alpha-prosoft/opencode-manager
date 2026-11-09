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
  MANAGER_PUBLIC_PORT_INTERNAL=18000 MANAGER_SANDBOX_PORT=18001 MANAGER_EDD_PORT=18081 \
  MANAGER_CONTENT_SECURITY_POLICY=off clojure -M:run    # CSP off only for a shadow-cljs watch build
cd service && clojure -M:test:unit          # backend unit tests
cd web && clojure -M:shadow-cljs compile test   # UI unit tests (node, jsdom where a DOM is needed)
cd web && clojure -M:it                     # Playwright tests against MANAGER_URL (default http://localhost:18000)
cd service && MANAGER_URL=http://localhost:8080 clojure -M:e2e   # end-to-end against the compose stack (real LLM)
```

The UI is served with a strict Content-Security-Policy (`manager-svc.edge.static/default-csp`). A shadow-cljs
`watch` build needs `eval` and its websocket, so run the service with `MANAGER_CONTENT_SECURITY_POLICY=off` while
developing with watch; a `release` build works with the default policy.

Settings are `MANAGER_*` env vars, see `service/src/manager_svc/config.clj`.
`MANAGER_STORE=memory` (default, data lost on restart) or `postgres`.
`MANAGER_AUTH_MODE=jwt` is the default and needs `MANAGER_JWT_ISSUER`, `MANAGER_JWT_AUDIENCE` and
`MANAGER_JWT_JWKS_URL` (or `MANAGER_JWT_SECRET`); the manager refuses to start without them. `login`
(password-free username login) is for local development only and must be set explicitly.

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
  Domains: `platform` (registry, specs, sync, catalog, harnesses, admin
  views), `sessions`, `runs`, `credentials`, `observability`.
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
- Aggregate ids are UUIDs. Things named by strings (teams, credentials) use
  `(lambda.uuid/named "team:<id>")` via `:id-fn`; keep the string as an
  attribute. Runs and sessions are UUIDs generated by the caller (the UI
  generates the id of a run it starts, then navigates to it).
- Enumerations are keywords (`:running`, `:admin`); timestamps are ISO-8601
  strings from `manager-svc.time/now`.
- Authorization: every `reg-cmd`/`reg-query` declares
  `:auth {:role [...]}` with `:user` (people) and/or `:non-interactive`
  (workers, effects). Team and admin checks are data-dependent and done in the
  handler with the platform facade; fail with `manager-svc.errors`.
- The person is `(manager-svc.auth/user-id ctx)`. The username `platform` (`auth/platform-user`) is the
  platform itself and never a person; compare with `auth/platform-user?` or an explicit marker
  (`:platform-started` on runs, `sessions.api/platform-owned?`), never by prefix.
- `manager-svc.client/command` and `query` throw ex-info `{:status :message}` when edd answers with
  `:exception` or `:error`. Callers that don't read their own writes use `client/command-no-settle`; never
  hold a lock across `effects/settle!`.
- Outgoing HTTP goes through `manager-svc.http` (timeouts, no redirects unless asked), OpenCode through
  `manager-svc.opencode`, git through `manager-svc.git`, secret files through `manager-svc.files`.
- edd answers any query result map with a truthy top-level `:error` as an
  error. Query results never carry `:error`: name it `:error-message` (UI) or
  wrap internal aggregates (`{:run ...}`, `{:session ...}`).
- Lists never search (edd's postgres simple-search stops at 600 results).
  They read directories (`manager-svc.directory`): one aggregate per person
  (edd identity `"user:<username>"`), per team (`"team:<team-id>"`) and one
  for the platform (`"platform"`), each with a fresh UUID found through its
  identity. A directory holds summary rows per collection, enough to render
  a list, at most 10,000 per collection (`directory/max-rows`; the oldest is
  dropped when full). Rows carry the `:id` and `:version` of the aggregate
  they describe, so a late or repeated update never overwrites a newer one.
  - Keep rows in step with `directory/reg-rows`: after a command producing
    one of the given events, its effect sends `:upsert-directory-row` /
    `:remove-directory-row` (role `:non-interactive`) for the aggregate.
    Commands stay pure; the first row creates the directory and its
    identity (a lost creation race fails on the identity's unique
    constraint and succeeds on redelivery).
  - Collections: team `:runs` `:sessions` `:schedules`; user `:runs` (runs
    the person started) `:sessions` (owned) `:credentials` (own values,
    never the sealed value); platform `:credentials` (platform values),
    `:sessions` (sessions without a team) and `:directories` (every
    directory, so "all teams/people" also covers teams that left the
    registry).
  - Read with `directory/rows`, `directory/rows-of` (several directories,
    newest copy per id) and `directory/identities` (`"team"`/`"user"`).
  - Effects travel in-process (memory distributor, router in
    `manager-svc.main`). `manager-svc.effects/settle!` waits for them;
    the edge's `/api/command` and `manager-svc.client/command` call it, so
    the UI and workers read their own writes. In tests use
    `support/apply!` (command, events, effects) or `support/settle!`.
  - An effect command needs an `:id` (postgres' command store); `reg-rows`
    sets the source aggregate's id and `:id-fn` resolves the directory.

## API contract

The UI calls edd queries and commands (`[::api/call {:query {...}}]`). Result
shapes follow the previous REST API (`manager/ui/src/api.ts` in git history) with kebab-case
keys and keyword enum values.

| Previous REST | edd | Domain |
|---|---|---|
| GET /me | query `:get-me` | platform |
| GET /auth/personas | query `:get-personas` (edge `/api/auth/personas`, login mode only, else 404) | platform |
| GET /platform | query `:get-platform` | platform |
| POST /platform/sync | cmd `:sync-platform` | platform |
| GET /marketplace | query `:list-activities` | platform |
| GET /schemas | query `:get-schemas` | platform |
| GET /teams | query `:list-teams` | platform |
| GET /teams/{id} | query `:get-team {:team-id}` | platform |
| GET /harnesses | query `:list-harnesses` | platform |
| GET /admin/overview | query `:get-admin-overview` | platform |
| GET /admin/users | query `:list-admin-users` | platform |
| POST /admin/llm/{id}/test | query `:test-llm-connection {:connection-id}` | platform |
| GET /admin/schedules | query `:list-schedules` | runs |
| GET /sessions | query `:list-sessions` | sessions |
| GET /sessions/{id} | query `:get-session {:session-id}` | sessions |
| PATCH /sessions/{id} | cmd `:update-session {:id :attrs {:name :description :visibility}}` | sessions |
| POST /sessions/{id}/start, /stop | cmd `:start-session`, `:stop-session {:id}` | sessions |
| DELETE /sessions/{id} | cmd `:delete-session {:id}` | sessions |
| POST /sessions/{id}/lock | cmd `:lock-session {:id}` | sessions |
| POST /sessions/{id}/fork | cmd `:fork-session {:id <new> :attrs {:source-id}}` | sessions |
| GET /sessions/{id}/conversations | query `:list-conversations {:session-id}` | sessions |
| POST /admin/sessions/stop-idle | cmd `:stop-idle-sessions` | sessions |
| POST /teams/{t}/workflows/{w}/runs | cmd `:start-run {:id <new> :attrs {:team-id :workflow-id :inputs}}` | runs |
| GET /teams/{t}/workflows/{w}/pull-requests | query `:list-open-pull-requests {:team-id :workflow-id}` | runs |
| GET /runs | query `:list-runs {:team-id?}` | runs |
| GET /runs/{id} | query `:get-run {:run-id}` | runs |
| POST /runs/{id}/cancel, /retry | cmd `:cancel-run {:id}`, `:retry-run {:id <new> :attrs {:source-id}}` | runs |
| GET /me/credentials | query `:get-my-credentials` → `{:credentials :pending}` | credentials |
| PUT /me/credentials/{id} | edge PUT `/api/credentials/{id}` body `{"secret" "username"?}` → edd command response (the edge seals the value and sends `:set-my-credential {:attrs {:credential-id :sealed}}`) | credentials |
| DELETE /me/credentials/{id} | cmd `:delete-my-credential {:attrs {:credential-id}}` | credentials |
| POST /me/credentials/seen | cmd `:mark-credentials-seen {:attrs {:credentials}}` | credentials |
| GET /admin/credentials | query `:list-admin-credentials` | credentials |
| PUT /admin/credentials/{id} | edge PUT `/api/admin/credentials/{id}` body `{"secret" "username"?}` (sends `:set-platform-credential` sealed) | credentials |
| DELETE /admin/credentials/{id} | cmd `:delete-platform-credential {:attrs {:credential-id}}` | credentials |
| GET /admin/mcp-servers | query `:list-mcp-servers` | credentials |
| GET /sessions/{id}/activity | query `:get-session-activity {:session-id}` | observability |
| GET /sessions/{id}/audit | query `:list-audit-events {:session-id :after :limit}` | observability |
| GET /sessions/{id}/audit/verify | query `:verify-audit {:session-id}` | observability |
| GET /sessions/{id}/audit/export | edge GET `/api/sessions/{id}/audit/export` | observability |
| GET /admin/usage | query `:get-admin-usage {:days}` | observability |
| /llm/v1/*, /mcp-gw/*, /api/telemetry | sandbox listener routes | observability, credentials |
| /mcp (submit_* tools) | sandbox listener route | runs |
| /metrics, /sessions/{sub}/{id}, /api/gateway/auth (forward auth) | public edge routes | observability, sessions |

Secrets never travel in an edd command: edd persists command bodies in its
request log, so the edge seals a credential value (bound to the credential and
its owner) before it sends the command.

Commands return only success; the UI re-queries afterwards. A check that must
pass before a change (testing a credential against its service) is a query
dependency of the command (`:deps`), and the command fails with the check's
message.

## UI architecture

- A page is `manager-web.<page>.{core,events,subs,views,elements}`; routes
  and pages are wired in `manager-web.core`.
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

## Testing

- Backend: `edd.test.fixture.dal` (`mock/with-mock-dal`, `mock/handle-cmd`,
  `mock/query`, `mock/verify-state`) with `manager-svc.test-support`
  (`support/ctx` with the directories registered, `as-user`, `as-platform`,
  `apply!`, `settle!`). Domain tests require only their
  domain, not `manager-svc.module`, so one broken domain doesn't break others.
  External systems (Docker, Kubernetes, git, HTTP) sit behind small protocols
  or functions that tests replace.
- End to end: `service/test-e2e/manager_svc/e2e.clj` (alias `:e2e`) drives a
  running compose stack through the API, Traefik and `docker exec` probes.
- UI unit tests: `web/test/unit/manager_web/**/*_test.cljs`, run on node by the shadow-cljs `:test` build
  (`clojure -M:shadow-cljs compile test`). Test event functions and subs as plain functions; where a DOM is
  needed (sanitizing Markdown) use `jsdom` (`(.-window (JSDOM. ""))`).
- UI: Playwright integration tests in `web/test/it/manager_web/*_it.clj` with
  `testing.pw` (`with-ui`, `step`) and `manager-web.helpers`, against a running stack (`MANAGER_URL`). They drive
  real flows (start a workflow and follow it to its result, session actions, credentials, admin actions), not
  only rendering.
- `docs/test-coverage.md` maps every feature to its unit test, API e2e check and UI IT test. Keep it current
  when adding a feature.

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
