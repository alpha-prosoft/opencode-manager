# 1. Install the platform

This page is for the platform operator. You first start the platform on a laptop to see it work, then install it on Kubernetes for real use. At the end you have a running platform you can sign in to.

## Evaluation on a laptop

You need Docker with Compose v2 and `git`.

```bash
git clone https://github.com/alpha-prosoft/opencode-manager
git clone https://github.com/alpha-prosoft/edd-core
cd opencode-manager
```

The manager image is built from source, and the build takes [edd-core](https://github.com/alpha-prosoft/edd-core) from a checkout next to this repository (`../edd-core`, named in `compose.yml` and in the `Dockerfile`). Without it `make dev` fails at the build.

### Give the platform an LLM credential

The example tenant uses GitHub Copilot through a secret named `COPILOT_TOKEN`.

1. Get a token. Log in once with OpenCode:

    ```bash
    docker run --rm -it -v "$PWD/local/opencode:/root/.local/share/opencode" ghcr.io/anomalyco/opencode:1.19.5 auth login
    ```

    This writes `local/opencode/auth.json`. Open it and copy the `refresh` value, which starts with `gho_`.

2. Store it as a secret. On the evaluation stack, a secret is a file named after it in `local/secrets/`. The directory is ignored by Git and is mounted only into the manager, never into a session.

    ```bash
    mkdir -p local/secrets && chmod 700 local/secrets
    printf '%s' 'gho_…' > local/secrets/COPILOT_TOKEN
    ```

Any OpenAI-compatible provider works too; you change the connection in `tenant.json` (see [Onboard a tenant](tenant.md)).

### Start the stack

```bash
make dev
```

`make dev` does three things, which you can also run one by one:

```bash
./scripts/seed-local-repos.sh                         # publish the example repositories under local/repos
docker compose --profile images build opencode-dev    # build the session image with Python, Node and git
docker compose up -d --build                          # build and start the stack
```

The seed script turns `examples/tenants/<tenant>/onboarding` and every `examples/tenants/<tenant>/teams/<team>` into local Git repositories (`acme-onboarding`, `acme-demo`, `acme-payments`, `acme-ops`, …) and publishes the demo service the example workflows work on. Run it again after you change a file under `examples/`; the platform picks the change up at its next sync.

`make logs` follows the manager's log, `make down` stops the stack and `make clean` also removes its volumes (the database, session workspaces) and any session containers.

`make dev` starts whatever compose files your `.env` names in `COMPOSE_FILE` (by default only `compose.yml`). `make dev-full` always starts the stack together with Keycloak, Gerrit and Jenkins, described under [Optional overlays](#optional-overlays).

### Your own repositories

To try your own onboarding and team repositories on the laptop, either put them anywhere Git can reach (`https://…`) or keep them as directories under `local/sources/<name>/`: the seed script publishes each one as the local repository `file:///repos/<name>` next to the examples. Everything under `local/` is ignored by Git. Name your tenants and their onboarding repositories in the `TENANTS` variable of your `.env`, as JSON:

```sh
TENANTS='[{"name":"acme","onboarding":"file:///repos/acme-onboarding"}]'
```

### What is running

| Container | What it is |
|---|---|
| `manager` | The platform: UI, API, the workers, the LLM gateway and the MCP gateway. |
| `postgres` | The manager's database. All platform state is stored here. |
| `traefik` | The HTTP router in front of the manager and of every session. |
| `mcp-bitbucket`, `mcp-jenkins` | Two small read-only MCP servers used by the examples. They hold no credentials. |
| one container per running session | Created by the manager when a session starts. |

| URL | What |
|---|---|
| <http://localhost:8080> | The platform UI and API |
| `http://<session>.localhost:8080` | OpenCode sessions (browsers resolve `*.localhost` to your machine) |
| <http://localhost:9090> | The Traefik dashboard (local only) |

### Sign in with the test login

Open <http://localhost:8080>. The evaluation stack runs with `MANAGER_AUTH_MODE=login`: a login page without passwords that lists test people. Each person's claims come from `examples/dev-users.json`, in the shape an identity provider would send them.

| User | Tenant | Teams and roles |
|---|---|---|
| `alice` | acme | tenant admin; admin of Demo team |
| `bob` | acme | member of Demo team and Payments |
| `carol` | acme | in no team |
| `dave` | acme | admin of Payments, member of Operations |
| `frank` | acme | admin of Demo team (not a tenant admin) |
| `erin` | acme | member of Payments |
| `henry` | acme | member of Operations |
| `gina`, `hank`, `ivy` | globex | a second tenant, to see that tenants are isolated |
| `oscar` | — | platform operator |

!!! warning
    The test login has no passwords. It is for local evaluation only and must be chosen explicitly. Without it the manager runs in `jwt` mode and refuses to start until the token settings are there.

### Check that it works

1. Sign in as `oscar`. You land on **Tenants** and see `acme` and `globex` with the state of their last sync.
2. Sign out and sign in as `bob`. You land on **Connect your accounts**, because Bob's teams use tools he has no credential for yet. None of them is required of him, so select **Do this later**. Home then shows the workflows of Demo team and Payments.
3. Select **Start session** on the **Workspace** workflow of Demo team, confirm with **Start session**, and open the session. If the agent answers, the LLM connection works.

The repository also has an automated check that drives the whole stack through its API with a real LLM (it needs the Clojure CLI):

```bash
cd service && MANAGER_URL=http://localhost:8080 clojure -M:e2e
```

### Optional overlays

```bash
make dev-full                                                                   # everything below at once
docker compose -f compose.yml -f compose.keycloak.yml up -d                     # sign in through a local Keycloak
docker compose -f compose.yml -f compose.keycloak.yml -f compose.ci.yml up -d   # plus a local Gerrit and Jenkins
```

The Keycloak overlay is described in [Connect your identity provider](identity.md#worked-example-keycloak-on-your-laptop), the Gerrit and Jenkins overlay in [Automate the work](automate.md#try-it-locally).

## Production on Kubernetes

Read [Connect your identity provider](identity.md) before you install: the chart needs the sign-in settings at install time.

The Helm chart in `charts/opencode-manager` installs:

- the manager and a Postgres database for it, with Traefik as a sub-chart;
- an HTTPRoute for your domain and for `*.<domain>` (each session gets its own host name);
- a ForwardAuth middleware that checks every request to a session, and a middleware that removes cookies before a request reaches a session;
- a separate Service for the manager's sandbox listener, which no route exposes;
- NetworkPolicies that isolate session pods and the manager;
- a shared volume for session workspaces.

### The manager image

The chart's default image is `ghcr.io/alpha-prosoft/opencode-manager` with the chart's app version as tag. The repository's CI (`.github/workflows/build.yml`) builds and pushes that image on every push to `main` (tag `main`) and for every `v*` Git tag (the version as tag). Set `image.tag` to a tag that exists in the registry, or build and push your own and set `image.repository` and `image.tag`:

```bash
docker build --build-context edd-core=../edd-core -t registry.example.com/opencode-manager:1 .
docker push registry.example.com/opencode-manager:1
```

### Prepare

```bash
kubectl apply --server-side -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.7.0/standard-install.yaml
kubectl create namespace agents

# Operator secrets: one key per secret name. Mounted read-only into the manager, never into session pods.
kubectl -n agents create secret generic platform-secrets \
  --from-literal=COPILOT_TOKEN=gho_… --from-literal=GIT_TOKEN=…

# The key that encrypts every stored credential value. Create it once and keep a copy.
kubectl -n agents create secret generic opencode-credentials-key \
  --from-literal=key=$(openssl rand -base64 32)

# oidc mode: the client secret of the client you registered at your identity provider.
# The chart mounts the key client-secret as a file.
kubectl -n agents create secret generic agent-platform-oidc \
  --from-literal=client-secret='<the client secret>'
```

!!! warning
    Losing the credentials key makes every stored credential value unreadable. Store a copy where you store other recovery material. If you leave `credentialsKey.existingSecret` empty, the manager generates a key on its data volume instead.

### Install

```bash
helm dependency build charts/opencode-manager
helm install platform charts/opencode-manager -n agents \
  --set domain=agents.example.com \
  --set auth.mode=oidc \
  --set auth.oidc.issuer=https://idp.example.com/realms/company \
  --set auth.oidc.clientId=agent-platform \
  --set auth.oidc.clientSecret.existingSecret=agent-platform-oidc \
  --set-json 'tenants=[{"name":"acme","onboarding":{"url":"https://git.example.com/acme/onboarding.git","credentials":"GIT_TOKEN"}}]' \
  --set-json 'operators=["oscar"]' \
  --set platformSecrets.existingSecret=platform-secrets \
  --set credentialsKey.existingSecret=opencode-credentials-key
```

The identity settings (`auth.*`, `claims.*`) are explained in [Connect your identity provider](identity.md); the tenant list in [Onboard a tenant](tenant.md#register-the-tenant).

Point DNS for both `agents.example.com` and `*.agents.example.com` at the gateway.

### TLS

The chart has no TLS setting of its own. Its routes attach to the Gateway `gateway.name` (default `traefik-gateway`, created by the Traefik sub-chart) on the listener `gateway.sectionName` (default `web`). At the same time `publicScheme` defaults to `https`, so the manager builds `https://` links and sets cookies that browsers accept only over https. You therefore need one of:

| Setup | What you set |
|---|---|
| TLS ends in front of the gateway (a load balancer or ingress that holds the certificate for `domain` and `*.domain` and forwards to the gateway) | Nothing more: keep `gateway.sectionName=web` and `publicScheme=https`. |
| TLS ends at the Gateway | Give the Gateway an https listener with a certificate for `domain` and `*.domain` (in the Traefik sub-chart's values under `traefik`, or in a Gateway of your own), and set `gateway.sectionName` to that listener's name; for your own Gateway also `gateway.name` and `gateway.namespace`. |
| No TLS (a throwaway test only) | `--set publicScheme=http` |

### Values that matter

| Value | Default | What to decide |
|---|---|---|
| `domain` | `opencode.example.com` | The UI is served on `domain`, every session on `<session id>.<domain>`. |
| `auth.mode` and `auth.*` | `jwt` | How people sign in. See [identity](identity.md). |
| `tenants` or `tenantsFile.configMap` | `[]` | The tenants and their onboarding repositories. |
| `platformSecrets.existingSecret` | none | The Secret whose keys are operator secrets. |
| `credentialsKey.existingSecret` | none | The Secret with the key that encrypts credential values. |
| `postgres.password` | `change-me` | Change it. |
| `sharedStorage.accessModes`, `.size` | `ReadWriteMany`, `50Gi` | One volume for all session workspaces. It needs `ReadWriteMany` when session pods can land on different nodes. |
| `trustedProxies` | the private address ranges | Narrow it to your pod CIDR; see below. |

Every value, with the setting behind it, is in [Server settings](../reference/settings.md).

The chart runs one manager replica and replaces it on upgrade (`Recreate`). Sessions run as Deployments scaled to zero while idle.

### The two listeners and the network policies

The manager listens on two ports that share nothing:

| Listener | Port | Serves | Reachable from |
|---|---|---|---|
| Public | 8000 | UI, API, login, webhooks, session access checks, `/metrics` | The gateway (Traefik) only |
| Sandbox | 8001 | LLM gateway, MCP gateway, result tools, telemetry | Session pods only |

With `networkPolicies.enabled`, session pods can reach only the sandbox listener and DNS, and can be reached only by the manager and the gateway. A harness with `"network": "internet"` adds outbound internet, except the address ranges in `networkPolicies.internetExcept`, so a session can't reach in-cluster services such as MCP servers directly.

!!! warning
    These rules exist only if your network plugin enforces NetworkPolicies. Check that before you rely on them.

### Trusted proxies

Webhooks can restrict callers by source address. Behind a proxy the manager sees the proxy's address, so it takes the caller's address from `X-Forwarded-For` only when the request comes from a proxy listed in `trustedProxies`. The chart's default trusts every private address, which is sound as long as the network policy lets only Traefik reach the public listener. Narrow it to your cluster's pod CIDR, and set it to `[]` when nothing proxies the manager.

### Sizing

The sources give defaults, not sizing guidance: the chart's volume sizes (`sharedStorage` 50Gi, `managerStorage` 1Gi, `postgres.storage.size` 8Gi), and per session the harness limits (1 CPU, 2048 MB memory and 512 processes by default; at most 8 CPUs and 32768 MB). A team's `max_running_sessions` bounds how many sessions it runs at once, so the number of teams and their limits give you the upper bound for cluster capacity.

### Check that it works

1. `kubectl -n agents get pods`: the manager, Postgres and Traefik are running.
2. Open `https://agents.example.com`. In `oidc` mode the login page shows a **Sign in** button that leads to your identity provider and brings you back signed in.
3. Sign in as an operator and open **Tenants**: your tenant is listed with the state of its first sync.

## You now have

- An evaluation stack on your laptop with the example tenant `acme`.
- A production installation on Kubernetes with its database, storage, secrets and network isolation.

**Next:** [2. Connect your identity provider](identity.md)
