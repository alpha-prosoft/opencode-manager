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

## Auth

- `MANAGER_AUTH_MODE=dev`: everyone is `MANAGER_DEV_SUB`.
- `MANAGER_AUTH_MODE=jwt`: reads a JWT from `Authorization: Bearer` or the `access_token` cookie. It is verified with `MANAGER_JWT_JWKS_URL` or `MANAGER_JWT_SECRET`. The cookie must be scoped to the parent domain so that session subdomains receive it.

## API

| Method | Path | |
|---|---|---|
| GET | `/api/sessions` | List the caller's sessions |
| POST | `/api/sessions` | Create and start: `{name, description, labels, env}` |
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
