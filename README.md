# opencode-manager

Runs one [OpenCode](https://opencode.ai) web container (`opencode web`) per
user session behind Traefik. A FastAPI manager (with a React/MUI UI) owns the
session lifecycle, and Temporal runs session starts and the idle reaper.

```
browser ──► traefik ──► Host(domain)            → manager (UI + /api)
                   ├──► Host(<id>.domain)       → ForwardAuth(manager) → session <id>
                   └──► Host(*.domain) fallback → manager wakes a stopped session
```

OpenCode cannot run under a sub-path, so each session gets its own subdomain.
`/sessions/{sub}/{id}` on the manager checks ownership and redirects to it.

## Run locally (Docker Compose)

```bash
docker compose up -d --build
```

- Manager UI: http://localhost:8080
- Sessions: `http://<sessionId>.localhost:8080`. Browsers and curl resolve `*.localhost` to loopback.
- Traefik dashboard: http://localhost:9090
- Temporal UI: http://localhost:8233

Environment for every session (e.g. provider API keys), as JSON:

```bash
SESSION_ENV='{"ANTHROPIC_API_KEY":"..."}' docker compose up -d
```

Without keys, sessions can still use OpenCode's free models.

### Bitbucket pull request sessions

The manager polls Bitbucket Cloud for new pull requests and creates a **public** session for each one. The session is owned by the `bitbucket` user and labelled with the repository, PR number, branch and commit. To enable it, give compose the token:

```bash
docker compose --env-file /path/to/secrets.env up -d   # provides BITBUCKET_TOKEN
```

`--env-file` only feeds variable substitution, so only `BITBUCKET_TOKEN` reaches the manager. The defaults are workspace `example-workspace`, repos `["example-repo"]`, and a 15-minute interval. Override them with `BITBUCKET_WORKSPACE`, `BITBUCKET_REPOS` (JSON list) and `BITBUCKET_POLL_MINUTES`.

The Temporal schedule `poll-bitbucket` runs `PollPullRequestsWorkflow`. It starts one `PullRequestSessionWorkflow` (ID `pr-session-<repo>-<id>`) per new PR.

The first poll records the PRs that are already open as a baseline and creates no sessions for them; set `BITBUCKET_BACKFILL=true` to change that. Seen PRs are stored in the manager database, so a PR gets at most one session, even across restarts. To trigger a poll right away:

```bash
docker compose exec temporal temporal schedule trigger --schedule-id poll-bitbucket
```

## Deploy on Kubernetes (Helm)

Prerequisite: the Gateway API CRDs.

```bash
kubectl apply --server-side -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.7.0/standard-install.yaml
helm dependency build charts/opencode-manager
helm install om charts/opencode-manager -n opencode --create-namespace --set domain=opencode.example.com
```

The chart installs:
- the manager, its RBAC, and the storage
- a Traefik Middleware for ForwardAuth, and HTTPRoutes for `domain` and `*.domain`
- Traefik (Gateway API provider and its `traefik-gateway`) and Temporal with a small Postgres, as subcharts

To use an existing Gateway or Temporal, set `traefik.enabled=false` / `temporal.enabled=false` and configure `gateway.*` / `temporalConnection.*`.

Put provider API keys in a Secret whose `session-env.json` key holds a JSON
object, and set `session.envSecret.name`.

For pull request sessions, create a Secret with the token and set
`bitbucket.tokenSecret.name`, `bitbucket.workspace` and `bitbucket.repos`.

The shared session volume (`sharedStorage`) needs `ReadWriteMany` when session pods can land on different nodes.

## How it works

| Concern | Docker | Kubernetes |
|---|---|---|
| Containers (`MANAGER_BACKEND`) | Docker socket | Deployment and Service per session; scaled to 0 when stopped |
| Routing (`MANAGER_ROUTER`) | Traefik file provider (`traefik-file`) | Gateway API HTTPRoute per session (`gateway-api`), with a Traefik Middleware via `ExtensionRef` |
| Storage | `opencode-shared` volume, mounted with a volume subpath | one PVC, mounted with `subPath` |

- **Shared storage**: every session mounts `sessions/<id>` from the shared volume as `/session`.
  It contains `home/` (`HOME`, which holds OpenCode's config and state) and `workspace/` (the working directory).
- **ForwardAuth** (`/api/gateway/auth`):
  - checks the caller owns the session;
  - starts the session if it's stopped and shows a "starting" page until it is healthy;
  - injects `Authorization: Basic …` for the session's random `OPENCODE_SERVER_PASSWORD`, so containers reject any request that doesn't come through the gateway.
- **Temporal**:
  - `StartSessionWorkflow` (workflow ID `start-session-<id>`, so concurrent starts are deduplicated) starts the container and waits until it is ready.
  - The `reap-sessions` schedule runs `ReapSessionsWorkflow` every `MANAGER_REAP_INTERVAL_SECONDS`. It stops a session that has been idle for `MANAGER_IDLE_GRACE_SECONDS` and has no busy OpenCode sessions (`GET /session/status`).

## Auth and visibility

- `MANAGER_AUTH_MODE=login` (default): username-only login, with no password for now. The manager signs a JWT into an HttpOnly `oc_auth` cookie.
- `MANAGER_AUTH_MODE=jwt`: identity comes from an external IdP token (`Authorization: Bearer` or the `access_token` cookie), verified with `MANAGER_JWT_JWKS_URL` or `MANAGER_JWT_SECRET`.
- The signing key comes from `MANAGER_SECRET_KEY`. If it isn't set, a random key is generated once and stored next to the database.

Session subdomains get their own cookie. Opening `/sessions/{owner}/{id}` on the manager redirects to the session with a 60-second token bound to that session (`oc_handoff`). The session host swaps it for a host-only cookie and redirects to a clean URL. A visitor without a cookie is sent back to the entry URL and, if needed, to `/login`.

Sessions are `private` (default) or `public`:

| | Owner | Other users, public session | Other users, private session |
|---|---|---|---|
| List, view, open, start | yes | yes (per-session `env` hidden) | 404 |
| Stop, change, delete | yes | 403 | 404 |

## API

| Method | Path | |
|---|---|---|
| POST | `/api/auth/login` | Log in: `{username}` |
| POST | `/api/auth/logout` | Log out |
| GET | `/api/me` | Current user |
| GET | `/api/sessions` | Own sessions plus public ones |
| POST | `/api/sessions` | Create and start: `{name, description, visibility, labels, env}` |
| PATCH | `/api/sessions/{id}` | Owner: change `name`, `description`, `visibility`, `labels` |
| GET | `/api/sessions/{id}` | Session details and status |
| POST | `/api/sessions/{id}/start` | Start |
| POST | `/api/sessions/{id}/stop` | Stop |
| DELETE | `/api/sessions/{id}` | Delete the container, route, and data |

OpenAPI docs: `/api/docs`.

## CI

`.github/workflows/build.yml` does the following:
- builds the UI and checks the backend;
- builds the manager image, which bundles the UI, and pushes it to `ghcr.io/<owner>/<repo>`; pull requests get the image as a build artifact instead;
- lints and packages the Helm chart as an artifact. On `v*` tags, it pushes the chart to `oci://ghcr.io/<owner>/charts`.
