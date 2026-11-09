# Server settings

This page is for platform operators. It lists every `MANAGER_*` environment variable the manager reads, with its default and meaning, grouped by topic. The compose file and the Helm chart set these for you; the last column of each table names the Helm value where the chart has one.

Rules that hold for all of them:

- A setting that is unset or blank takes its default.
- List settings are comma separated (`MANAGER_OPERATORS=oscar,olga`).
- Settings are read at start. Only the tenants file, the claims parser file and the identity provider's keys reload without a restart.

## Authentication

| Setting | Default | Meaning | Helm |
|---|---|---|---|
| `MANAGER_AUTH_MODE` | `jwt` | `jwt`, `oidc` or `login`. `login` is the password-free test login and must be set explicitly. | `auth.mode` |
| `MANAGER_AUTH_TTL_HOURS` | `12` | Lifetime of the manager's login cookie. | `auth.ttlHours` |
| `MANAGER_SECRET_KEY` | generated | The platform signing key. Login tokens and the per-session gateway keys are derived from it. Unset: generated once into `<data dir>/signing.key`. | — |
| `MANAGER_EMAIL_DOMAIN` | `users.local` | Domain of the e-mail address used for a person whose token has none (`<username>@<domain>`), and of the platform's own commit address. | — |
| `MANAGER_DEV_USERS_FILE` | — | `login` mode only: a JSON or EDN file of username → claims for the test login. | `claims.devUsers.configMap` |

### jwt mode

The manager refuses to start in jwt mode without an audience, an issuer (or a discovery URL) and keys.

| Setting | Default | Meaning | Helm |
|---|---|---|---|
| `MANAGER_JWT_AUDIENCE` | — | The audience tokens must carry. | `auth.jwtAudience` |
| `MANAGER_JWT_ISSUER` | — | The issuer tokens must carry. | `auth.jwtIssuer` |
| `MANAGER_JWT_DISCOVERY_URL` | — | A full `.well-known/openid-configuration` URL; issuer and key set come from it. Its issuer must match `MANAGER_JWT_ISSUER` when both are set. | `auth.jwtDiscoveryUrl` |
| `MANAGER_JWT_JWKS_URL` | — | The key set URL, instead of the discovery document's `jwks_uri`. | `auth.jwtJwksUrl` |
| `MANAGER_JWT_JWKS_FILE` | — | A local key set file instead of a URL. Read again on the refresh interval and when the file changes. | `auth.jwks.file` |
| `MANAGER_JWT_SECRET` | — | A shared secret instead of public keys. | `auth.jwtSecret.existingSecret` |
| `MANAGER_JWT_COOKIE` | `access_token` | The cookie a token is read from when the request has no bearer token. | `auth.jwtCookie` |
| `MANAGER_JWT_JWKS_REFRESH_SECONDS` | `3600` | How often the keys and the discovery document are loaded again. | `auth.jwks.refreshSeconds` |
| `MANAGER_JWT_JWKS_REFRESH_RETRY_SECONDS` | `10` | Least time between reload attempts: a token naming an unknown key id reloads at once within this limit, and a failed reload is retried after it. | `auth.jwks.refreshRetrySeconds` |
| `MANAGER_JWT_JWKS_DEFAULT_ALGORITHM` | `RS256` | The algorithm of keys published without `alg` (EC keys follow their curve). One of `RS256`, `RS384`, `RS512`, `ES256`, `ES384`, `ES512`; anything else stops the manager at start. | `auth.jwks.defaultAlgorithm` |

### oidc mode

| Setting | Default | Meaning | Helm |
|---|---|---|---|
| `MANAGER_OIDC_DISCOVERY_URL` | — | The provider's full `.well-known/openid-configuration` URL. | `auth.oidc.discoveryUrl` |
| `MANAGER_OIDC_ISSUER` | — | The issuer. Without a discovery URL, the document is read from `<issuer>/.well-known/openid-configuration`; with one, the document must name this issuer. One of the two is required. | `auth.oidc.issuer` |
| `MANAGER_OIDC_CLIENT_ID` | — | The client id. ID tokens must name it as audience. | `auth.oidc.clientId` |
| `MANAGER_OIDC_CLIENT_SECRET` | — | The client secret. | — |
| `MANAGER_OIDC_CLIENT_SECRET_FILE` | — | A file holding the client secret. Neither set: a public client. | `auth.oidc.clientSecret.existingSecret` |
| `MANAGER_OIDC_SCOPES` | `openid profile email` | Scopes requested at login. | `auth.oidc.scopes` |
| `MANAGER_OIDC_REDIRECT_URL` | `<public URL>/api/auth/oidc/callback` | The redirect URI registered at the provider. | `auth.oidc.redirectUrl` |
| `MANAGER_OIDC_CLAIMS_SOURCE` | `id_token` | Where claims come from: `id_token`, `access_token`, `userinfo`, comma separated, later ones winning. An unknown source stops the manager at start. | `auth.oidc.claimsSource` |
| `MANAGER_OIDC_JWKS_FILE` | — | A local key set file instead of the provider's URL. | `auth.jwks.file` |
| `MANAGER_OIDC_JWKS_REFRESH_SECONDS` | `3600` | As in jwt mode. | `auth.jwks.refreshSeconds` |
| `MANAGER_OIDC_JWKS_REFRESH_RETRY_SECONDS` | `10` | As in jwt mode. | `auth.jwks.refreshRetrySeconds` |
| `MANAGER_OIDC_JWKS_DEFAULT_ALGORITHM` | `RS256` | As in jwt mode. | `auth.jwks.defaultAlgorithm` |

A key verifies only tokens of its own algorithm; a token whose header names another one is refused.

## Tenants, people and configuration files

| Setting | Default | Meaning | Helm |
|---|---|---|---|
| `MANAGER_TENANTS` | — | The tenants as JSON: `[{"name": "acme", "onboarding": "<location or source object>"}]`. An invalid list stops the manager at start. | `tenants` |
| `MANAGER_TENANTS_FILE` | — | A `.json` or `.edn` file with the same list. Read again when it changes; an invalid file keeps the last good list. | `tenantsFile.configMap` |
| `MANAGER_TENANT_FILE` | `tenant.json` | The name of the tenant file at the root of an onboarding repository. | — |
| `MANAGER_TEAM_CONFIG_FILE` | `team.json` | The name of the team file in a team's definitions directory. | — |
| `MANAGER_OPERATORS` | empty | Usernames with the operator role, besides those the claims parser marks. | `operators` |
| `MANAGER_CLAIMS_PARSER` | `manager-svc.claims/default-parser` | The fully qualified name of the claims parser function. | `claims.parser` |
| `MANAGER_CLAIMS_PARSER_FILE` | — | A `.clj` file, loaded at start and again when it changes, that may define the parser. A file that fails to load later keeps the last good parser. | `claims.parserFile.configMap` |
| `MANAGER_CONFIG_RELOAD_SECONDS` | `10` | How often the tenants file and the claims parser file are checked for changes. | `configReloadSeconds` |
| `MANAGER_SYNC_INTERVAL_MINUTES` | `10` | How often every tenant's onboarding and team repositories are synced. | `syncIntervalMinutes` |

## Secrets and repositories

| Setting | Default | Meaning | Helm |
|---|---|---|---|
| `MANAGER_CREDENTIALS_KEY` | generated | The key that encrypts credential values: 32 bytes, base64. Unset: generated once into `<data dir>/credentials.key`. A value of another length stops the manager. | `credentialsKey.existingSecret` |
| `MANAGER_SECRETS_DIR` | `/config/secrets` | Directory of operator secret files: `<dir>/<tenant>/<NAME>`, then `<dir>/<NAME>`. A basic credential's username is the file `<NAME>.username` next to it. | `platformSecrets.existingSecret` (mounted there) |
| `MANAGER_REPOS_DIR` | `/data/repos` | Where onboarding and team repositories are checked out. | — |
| `MANAGER_LOCAL_REPO_ROOTS` | `/repos` | Directories under which `file://` repositories and `path` sources may live. | — |
| `MANAGER_HTTP_GIT_HOSTS` | empty | Git hosts (`host` or `host:port`) that may be reached over plain `http://`. Every other Git URL must be https, ssh or `file://`. | `httpGitHosts` |

Two environment variables outside the `MANAGER_` prefix also provide operator secrets: `PLATFORM_SECRET_<NAME>` is the value of secret `<NAME>` when no file has it, and `PLATFORM_SECRET_<NAME>_USERNAME` its username. `PLATFORM_KNOWN_HOSTS` names the SSH known-hosts file used for ssh Git URLs (default `/data/known_hosts`).

## Storage

| Setting | Default | Meaning | Helm |
|---|---|---|---|
| `MANAGER_STORE` | `memory` | `memory` (everything is lost on restart) or `postgres`. Compose and the chart set `postgres`. | set by the chart |
| `MANAGER_DATA_DIR` | `/data` | The manager's own data: generated keys and, by default, repository checkouts. | `managerStorage` (volume) |
| `MANAGER_SHARED_MOUNT_PATH` | `/shared` | Where the volume with session workspaces is mounted in the manager. | — |

With `MANAGER_STORE=postgres` the database connection comes from two variables without the prefix: `DatabaseEndpoint` (the host) and `DatabasePassword`. The manager connects as user `postgres` to database `postgres`.

## Listeners and addresses

| Setting | Default | Meaning | Helm |
|---|---|---|---|
| `MANAGER_DOMAIN` | `localhost` | The UI is served on this domain, each session on `<session id>.<domain>`. | `domain` |
| `MANAGER_PUBLIC_SCHEME` | `http` | `http` or `https`, for links, redirects and cookies. The manager's cookies are always host-only; with `https` they are also marked `Secure` and named with the `__Host-` prefix (`__Host-oc_auth` instead of `oc_auth` for the login cookie). | `publicScheme` |
| `MANAGER_PUBLIC_PORT` | — | The port people use, when it is not the scheme's default (compose: `8080`). | `publicPort` |
| `MANAGER_PUBLIC_BIND_HOST` | `0.0.0.0` | Address the public listener binds to. | — |
| `MANAGER_PUBLIC_PORT_INTERNAL` | `8000` | Port of the public listener: UI, API, login, webhooks, session access checks, `/metrics`. | — |
| `MANAGER_SANDBOX_BIND_HOST` | `0.0.0.0` | Address the sandbox listener binds to. | — |
| `MANAGER_SANDBOX_PORT` | `8001` | Port of the sandbox listener: LLM gateway, MCP gateway, result tools, telemetry. | — |
| `MANAGER_EDD_PORT` | `8081` | Port of the internal command and query service, bound to `127.0.0.1`. | — |
| `MANAGER_MANAGER_SERVICE_URL` | `http://manager:8000` | Where the router reaches the public listener for session access checks. | set by the chart |
| `MANAGER_SANDBOX_SERVICE_URL` | `http://manager:8001` | Where sessions reach the sandbox listener. | set by the chart |
| `MANAGER_MAX_BODY_BYTES` | `1048576` | Largest request body on both listeners (1 MB). | — |
| `MANAGER_TRUSTED_PROXIES` | empty | Proxies whose `X-Forwarded-For` names a caller's address: addresses, CIDRs, or host names standing for their current addresses. Used for webhooks' allowed addresses and delivery log. | `trustedProxies` |
| `MANAGER_CONTENT_SECURITY_POLICY` | built-in strict policy | Replaces the Content-Security-Policy the UI is served with; `off` sends none. Change it only for UI development. | — |
| `MANAGER_UI_DIST_DIR` | `/app/ui` | Directory of the built UI. | — |

## Sessions

| Setting | Default | Meaning | Helm |
|---|---|---|---|
| `MANAGER_BACKEND` | `docker` | Where sessions run: `docker` or `kubernetes`. | set by the chart |
| `MANAGER_SESSION_IMAGE` | `ghcr.io/anomalyco/opencode:1.19.5` | The image of a session whose harness names none. | `sessionImage` |
| `MANAGER_SESSION_PORT` | `4096` | The port OpenCode listens on inside a session. | — |
| `MANAGER_REAP_INTERVAL_SECONDS` | `300` | How often idle sessions are looked for. | `reaper.intervalSeconds` |
| `MANAGER_IDLE_GRACE_SECONDS` | `900` | Idle time after which a session without a stored harness configuration is stopped. Other sessions use their harness's `idle_minutes`. | — |

### Docker backend

| Setting | Default | Meaning |
|---|---|---|
| `MANAGER_DOCKER_SANDBOX_NETWORK` | `opencode-sandbox` | The internal network sessions run on. |
| `MANAGER_DOCKER_EGRESS_NETWORK` | `opencode-egress` | The network added to sessions whose harness has `"network": "internet"`. |
| `MANAGER_DOCKER_SHARED_VOLUME` | `opencode-shared` | The volume with session workspaces. |

The manager talks to Docker through the socket mounted into it; `DOCKER_HOST` overrides the address.

### Kubernetes backend

| Setting | Default | Meaning | Helm |
|---|---|---|---|
| `MANAGER_K8S_NAMESPACE` | `default` | The namespace of session pods. The chart sets the release's namespace. | set by the chart |
| `MANAGER_K8S_SHARED_PVC` | `opencode-shared` | The PersistentVolumeClaim with session workspaces. | `sharedStorage` |

## Routing

| Setting | Default | Meaning | Helm |
|---|---|---|---|
| `MANAGER_ROUTER` | `traefik-file` | How session routes are published: `traefik-file` (a Traefik dynamic configuration file, compose) or `gateway-api` (HTTPRoutes, Kubernetes). | set by the chart |
| `MANAGER_TRAEFIK_DYNAMIC_FILE` | `/traefik/dynamic/sessions.yml` | `traefik-file`: the file the manager writes. | — |
| `MANAGER_TRAEFIK_ENTRYPOINT` | `web` | `traefik-file`: the Traefik entry point of session routes. | — |
| `MANAGER_GATEWAY_NAME` | `traefik-gateway` | `gateway-api`: the Gateway session routes attach to. | `gateway.name` (chart default `traefik-gateway`) |
| `MANAGER_GATEWAY_NAMESPACE` | — | `gateway-api`: the Gateway's namespace, when it is not the manager's. | `gateway.namespace` |
| `MANAGER_GATEWAY_SECTION_NAME` | — | `gateway-api`: the Gateway listener to attach to. | `gateway.sectionName` (chart default `web`) |
| `MANAGER_FORWARD_AUTH_MIDDLEWARE` | `opencode-forward-auth` | `gateway-api`: the Traefik middleware that checks access to a session. | set by the chart |
| `MANAGER_STRIP_COOKIE_MIDDLEWARE` | `opencode-strip-cookie` | `gateway-api`: the Traefik middleware that removes cookies before a request reaches a session. | set by the chart |

## Keys inside Secrets and ConfigMaps

Where a Helm value names a Secret or ConfigMap, a second value names the key inside it:

| Value | Default key | Names the key of |
|---|---|---|
| `credentialsKey.key` | `key` | The Secret in `credentialsKey.existingSecret` |
| `auth.oidc.clientSecret.key` | `client-secret` | The Secret in `auth.oidc.clientSecret.existingSecret` |
| `auth.jwtSecret.key` | `jwt-secret` | The Secret in `auth.jwtSecret.existingSecret` |
| `tenantsFile.key` | `tenants.json` | The ConfigMap in `tenantsFile.configMap` |
| `claims.parserFile.key` | `parser.clj` | The ConfigMap in `claims.parserFile.configMap` |
| `claims.devUsers.key` | `dev-users.json` | The ConfigMap in `claims.devUsers.configMap` |

The Secret in `platformSecrets.existingSecret` has one key per operator secret name.

## What compose.yml sets differently

The evaluation stack overrides these defaults. `local/manager.env`, if you create it, adds or overrides further settings.

| Setting | In `compose.yml` | Setting's default |
|---|---|---|
| `MANAGER_AUTH_MODE` | `login` | `jwt` |
| `MANAGER_STORE` | `postgres` | `memory` |
| `MANAGER_PUBLIC_PORT` | `8080` | none |
| `MANAGER_PUBLIC_BIND_HOST` | `manager-public` | `0.0.0.0` |
| `MANAGER_SANDBOX_BIND_HOST` | `manager-sandbox` | `0.0.0.0` |
| `MANAGER_MANAGER_SERVICE_URL` | `http://manager-public:8000` | `http://manager:8000` |
| `MANAGER_TRUSTED_PROXIES` | `traefik-public` | empty |
| `MANAGER_TENANTS` | `acme` and `globex` with `file:///repos/…` onboarding repositories | none |
| `MANAGER_OPERATORS` | `oscar` | empty |
| `MANAGER_DEV_USERS_FILE` | `/config/dev-users.json` (from `examples/dev-users.json`) | none |

`compose.keycloak.yml` switches `MANAGER_AUTH_MODE` to `oidc` and sets the `MANAGER_OIDC_*` settings; `compose.ci.yml` sets `MANAGER_HTTP_GIT_HOSTS=gerrit`.

## Helm values without a setting

These values of the chart configure Kubernetes objects rather than the manager:

| Value | Default | Meaning |
|---|---|---|
| `image.repository`, `image.tag`, `image.pullPolicy`, `imagePullSecrets` | `ghcr.io/alpha-prosoft/opencode-manager`, the chart's app version, `IfNotPresent`, none | The manager image. |
| `networkPolicies.enabled` | `true` | Create the NetworkPolicies that isolate session pods and the manager. |
| `networkPolicies.internetExcept` | the private, carrier-grade NAT and link-local ranges | Addresses that sessions with `"network": "internet"` still can't reach. |
| `networkPolicies.ingressFrom` | Traefik's pods | Who may reach the manager's public listener. |
| `sharedStorage.size`, `.storageClassName`, `.accessModes` | `50Gi`, cluster default, `ReadWriteMany` | The volume shared by all sessions. |
| `managerStorage.size`, `.storageClassName` | `1Gi`, cluster default | The manager's data volume. |
| `postgres.enabled`, `.serviceName`, `.image`, `.password` | `true`, `opencode-manager-postgres`, `postgres:18.6-alpine`, `change-me` | The manager's database. |
| `postgres.storage.size`, `postgres.storage.storageClassName` | `8Gi`, cluster default | The database's volume. |
| `traefik.enabled` | `true` | Install Traefik as a sub-chart with a Gateway named `traefik-gateway`. |
| `resources`, `nodeSelector`, `tolerations`, `affinity`, `extraVolumes`, `extraVolumeMounts` | empty | Pod settings of the manager. |
