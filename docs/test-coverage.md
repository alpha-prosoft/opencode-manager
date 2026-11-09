# Test coverage

Every feature of the platform and where it is tested:

- **Unit**: `service/test/manager_svc/**` (`cd service && clojure -M:test:unit`) and
  `web/test/unit/manager_web/**` (`cd web && clojure -M:shadow-cljs compile test`).
- **API e2e**: `service/test-e2e/manager_svc/e2e.clj` against the compose stack with the real LLM
  (`MANAGER_URL=http://localhost:8080 clojure -M:e2e`); the section and check names are printed as it runs.
- **UI IT**: Playwright tests in `web/test/it/manager_web/*_it.clj` against the same stack (`clojure -M:it`).

Namespaces are abbreviated: `sessions/core` is `manager-svc.sessions.core-test`, `session_it` is
`manager-web.session-it`. Every command and query is listed with its roles in `authorization` (a new one fails
that test until it is added), which also proves that people can't call platform-only actions and the platform
can't act as a person.

| Feature | Unit | API e2e (section) | UI IT |
|---|---|---|---|
| Login (test mode), personas, reserved/invalid names, logout | login, edge (personas-only-in-login-mode, login-refuses-the-platform-name), auth; web: api_test (404 fallback) | roles-and-administration, authentication-and-browser | login_it |
| JWT mode: issuer/audience/keys, personas 404 | auth, edge (personas-only-in-login-mode), login (404 in jwt mode); web: api_test (`response-event` 404) | not covered (the stack runs in login mode) | covered by unit tests only |
| Roles: platform admin, team admin, member, outsider | platform/sync (roles), platform/views (get-me-describes-the-caller), authorization | platform-and-access, roles-and-administration, authentication-and-browser (get-me) | login_it (no Admin for members), navigation_it (admin-navigation-it) |
| Every command/query declares its roles; platform-only actions refused to people | authorization (3 tests over 54 commands, 47 queries), directory (people-cannot-write-directories, a-directory-command-without-an-identity-fails-cleanly) | authentication-and-browser (people can't send the platform's own commands/queries) | n/a |
| Sync onboarding and team repositories (git, path, url sources), validation of definitions | platform/sync, platform/sources, platform/spec, platform/core | platform-and-access (sync, invalid workflow rejected), roles-and-administration (path source) | admin_it (Sync now) |
| Teams list and team page (workflows, runs, agents & harnesses, people & usage) | platform/views (teams-are-listed-for-members-only, team-detail) | catalog-and-library, platform-and-access (non-member 404) | library_it (team tabs and contents) |
| Harnesses and readiness for the person | platform/views (harness-cards) | catalog-and-library (models, ready) | library_it (ready), library_it/harness-readiness-it (needs setup → Accounts) |
| Activities catalog and schemas | platform/views (platform-status-activities-and-schemas), platform/spec | platform-and-access (marketplace), catalog-and-library (schemas) | library_it (catalog, example file) |
| Start a run: validated inputs, membership, session limit | runs/run (members-start-runs-with-validated-inputs, start-run-is-refused, the-session-limit-is-read-before-the-command), runs/authorization | platform-and-access, session-limit | home_it (start from Home), session_it (run dialog), library_it (dialog cancel) |
| Analysis activity → document | runs/executor, runs/tools | result-runs (analysis) | home_it (follow the run to its result) |
| Implementation activity → patch, tests, pull request, CI follow-up | runs/executor (an-implementation-opens-a-pull-request-and-follows-its-failed-build, ci-polls…), runs/ci, runs/scm, runs/git | result-runs (implement); PR/CI not covered (needs Bitbucket write access and Jenkins) | patch view rendered on the run page (home_it covers the result section) |
| Branch review / PR review → verdict, findings, comment plan | runs/review, runs/executor (a-review-run-from-start-to-posted-comments) | result-runs (review) | run page result (same component path as home_it) |
| Interactive session activity | runs/executor (interactive-workspaces-complete-once-the-agent-is-prompted) | interactive-session | session_it |
| Cancel and retry runs | runs/run (cancel, retry-starts-a-new-run-with-the-same-inputs), runs/authorization | cancel-and-retry | runs_it (cancel, Run again) |
| Run list, run detail, Started by / platform-started marker | runs/run (list-and-get-runs, platform-started-runs-carry-the-marker); web: run/views_test (started-by) | platform-started-sessions, catalog-and-library (outsiders don't see runs) | runs_it, home_it (Started by) |
| Schedules and pull-request triggers | runs/triggers | platform-started-sessions (scheduled run), administration-actions (list-schedules) | admin_it (Background jobs tab) |
| Session open: handoff, session-host cookie, forward auth | sessions/gateway | interactive-session (entry, handoff, Traefik, stranger refused) | session_it (Open in a popup) |
| Session start/stop/delete | sessions/core (lifecycle-requests), sessions/lifecycle, sessions/authorization | session-management | session_it (stop, start offered, delete) |
| Session rename and description | sessions/core (updating); web: session/views_test (rename-spec) | session-management | session_it (rename) |
| Session sharing (private, team, public) and who may see/work in it | sessions/access, sessions/authorization | session-management, interactive-session | session_it (share with team, admin sees it blocked) |
| Lock and fork | sessions/core (locking-sessions, forking), sessions/lifecycle (preparing-forks), sessions/authorization | credentials-and-tools (lock, fork, fork keys) | session_it (fork, lock) |
| Personal-credential sessions only for the owner | sessions/access, sessions/authorization | credentials-and-tools | session_it (blocked alert) |
| Platform-started sessions are team-only | sessions/access, sessions/authorization; web: session/views_test (team-only reason) | platform-started-sessions | not covered (needs a scheduled run in the browser; the blocked text is unit tested) |
| Stop idle sessions (asynchronous reaper) | sessions/core (stopping-idle-sessions), sessions/lifecycle (reaping); web: admin/events_test | administration-actions | admin_it (Stop idle report) |
| Conversations | observability/audit (summary-tracks-conversations, conversations-are-tracked…) | conversations-and-audit | session_it (Conversations tab) |
| Live activity and telemetry | observability/telemetry | monitoring-and-policy, telemetry | session_it (Live activity tab) |
| Harness policy (denied commands) | observability/telemetry (activity-summarises-reports) | monitoring-and-policy | session_it (Live activity shows blocked count when any) |
| Audit trail: list, verify, export, kept after delete | observability/audit, observability/authorization | conversations-and-audit, session-management (kept in store), result-runs (audit-ok) | session_it (verify, JSONL download) |
| Personal credentials: add, replace, remove, check failure | credentials/core, credentials/edge, credentials/authorization | credentials-and-tools, credential-management | accounts_it |
| Setup flow (pending credentials at login, seen, skip, later) | credentials/core (status-and-pending), credentials/authorization; web: setup/events_test | credentials-and-tools, credential-management | accounts_it/setup-it |
| Platform credentials: set, clear, check failure, personal-only refused | credentials/core (admin-credentials), credentials/authorization | credential-management, credentials-and-tools | admin_it (invalid, set, clear) |
| Secrets never in edd commands or API answers | credentials/core (values-reach-edd-only-sealed-for-their-owner), credentials/vault | roles-and-administration, credentials-and-tools, credential-management | n/a |
| MCP gateway: allow-list, forged key, credential injection, audit | credentials/gateway, credentials/mcp | credentials-and-tools | n/a (sandbox-side) |
| MCP servers admin view | credentials/core (admin-mcp-servers) | administration-actions | admin_it (MCP server row) |
| LLM gateway: forged key, allow-list, models, stopped-session key, budget | observability/gateway | interactive-session, telemetry, session-management (stopped key), token-budget | n/a (sandbox-side) |
| Usage, cost and metrics | observability/usage, observability/authorization (/metrics) | result-runs (metered), usage-and-metrics, administration-actions | admin_it (Usage tab, days select), library_it (usage by model) |
| Admin console: overview, people, teams, connections test | platform/views (admin-overview, admin-users, llm-connection-test, admin-views-are-for-platform-admins) | roles-and-administration, administration-actions | admin_it (all tabs, test connection) |
| CSRF (cookie requests need the UI's origin) | edge (state-changing-cookie-requests-must-come-from-the-ui); web: api_test (X-Requested-With on every non-GET) | roles-and-administration, authentication-and-browser | every IT action (the UI's own requests pass) |
| CSP and frame headers | edge (the-ui-gets-security-headers-and-no-source-maps) | authentication-and-browser | every IT test runs under the strict CSP |
| Body limits, failures without internals | edge (bodies-above-the-limit-are-refused, failures-keep-only-message-and-status) | n/a | n/a |
| Two listeners share nothing | listeners, observability/telemetry (sandbox-endpoints-are-only-on-the-sandbox-listener) | interactive-session, sandbox-cannot-reach-api | n/a |
| Sandbox isolation: no internet, read-only config/rootfs/skills/plugin, non-root, no MCP or API route (direct, Traefik, host ports) | sessions/docker, runs/sandbox | interactive-session, monitoring-and-policy, credentials-and-tools, sandbox-cannot-reach-api | n/a |
| Directories (lists without search) | directory, runs/run (runs-are-listed-from…), sessions/core (listing-reads…) | covered through every list check | every list page |
| Navigation, error states, unknown ids | web: urls_test, data_test, format_test | n/a | navigation_it, runs_it, sessions_it (unknown id errors) |

## Counts

- Unit: 290 backend tests (`service/test`), 58 UI tests (`web/test/unit`).
- API e2e: 186 checks in 20 sections.
- UI IT: 12 tests in 10 namespaces (121 assertions).

## Deliberately not covered end to end

- **JWT mode** (identity provider tokens, personas 404): the compose stack runs in login mode; the token checks
  and the 404 fallback are unit tested on both sides.
- **Pull requests and CI** (push, open PR, follow the Jenkins build, failure analysis): they need write access to
  a real Bitbucket repository and a Jenkins job; `runs/executor`, `runs/ci` and `runs/scm` cover them with fakes.
- **Team-only sessions in the browser**: a scheduled run takes 5 minutes to appear; the API e2e checks the rule
  and the UI's text for it is unit tested.
- **Sandbox-side gateways in the browser**: nothing in the UI calls them; the API e2e probes them from inside a
  real sandbox.
- The UI IT fixture expects the local onboarding (`local/sources/onboarding`) for `harness-readiness-it` (carol
  and the devops team).
