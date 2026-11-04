# opencode-manager

Runs one Open WebUI container per user session behind Traefik. A FastAPI
manager (with a React/MUI UI) owns the session lifecycle, and Temporal runs
session starts and the idle reaper.

```
browser ──► traefik ──► Host(domain)            → manager (UI + /api)
                   ├──► Host(<id>.domain)       → ForwardAuth(manager) → session <id>
                   └──► Host(*.domain) fallback → manager wakes a stopped session
```

Open WebUI cannot run under a sub-path, so each session gets its own subdomain.
`/sessions/{sub}/{id}` on the manager checks ownership and redirects to it.

## Run locally (Docker Compose)

```bash
docker compose up -d --build
```

- Manager UI: http://localhost:8080
- Sessions: `http://<sessionId>.localhost:8080`. Browsers and curl resolve `*.localhost` to loopback.
- Traefik dashboard: http://localhost:9090
- Temporal UI: http://localhost:8233

Extra Open WebUI settings for every session, as JSON:

```bash
OPENWEBUI_ENV='{"OLLAMA_BASE_URL":"http://host.docker.internal:11434"}' docker compose up -d
```

## Deploy on Kubernetes (Helm)

Prerequisite: the Gateway API CRDs.

```bash
kubectl apply --server-side -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.7.0/standard-install.yaml
helm dependency build charts/opencode-manager
helm install om charts/opencode-manager -n owui --create-namespace --set domain=opencode.example.com
```

The chart installs:
- the manager, its RBAC, and the storage
- a Traefik Middleware for ForwardAuth, and HTTPRoutes for `domain` and `*.domain`
- Traefik (Gateway API provider and its `traefik-gateway`) and Temporal with a small Postgres, as subcharts

To use an existing Gateway or Temporal, set `traefik.enabled=false` / `temporal.enabled=false` and configure `gateway.*` / `temporalConnection.*`.

The shared session volume (`sharedStorage`) needs `ReadWriteMany` when session pods can land on different nodes.

## How it works

| Concern | Docker | Kubernetes |
|---|---|---|
| Containers (`MANAGER_BACKEND`) | Docker socket | Deployment and Service per session; scaled to 0 when stopped |
| Routing (`MANAGER_ROUTER`) | Traefik file provider (`traefik-file`) | Gateway API HTTPRoute per session (`gateway-api`), with a Traefik Middleware via `ExtensionRef` |
| Storage | `owui-shared` volume, mounted with volume subpaths | one PVC, mounted with `subPath` |

- **Shared storage**: every session uses `sessions/<id>` on the shared volume.
  `cache/embedding/models` is shared, so the embedding model is downloaded only once.
- **ForwardAuth** (`/api/gateway/auth`):
  - checks the caller owns the session;
  - starts the session if it's stopped and shows a "starting" page until it is healthy;
  - passes `X-Forwarded-Email`, which Open WebUI's trusted-header auth uses to log the user in.
- **Temporal**:
  - `StartSessionWorkflow` (workflow ID `start-session-<id>`, so concurrent starts are deduplicated) starts the container and waits until it is ready.
  - The `reap-sessions` schedule runs `ReapSessionsWorkflow` every `MANAGER_REAP_INTERVAL_SECONDS`. It stops a session that has been idle for `MANAGER_IDLE_GRACE_SECONDS` and has no running Open WebUI tasks (`GET /api/tasks`).

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
