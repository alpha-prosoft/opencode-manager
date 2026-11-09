# 2. Connect your identity provider

This page is for the platform operator and the people who run the company's identity provider. You choose how people sign in, tell the platform how to read tenant, teams and roles from a token, and name the operators. At the end people sign in with their company account and land in the right tenant and teams.

## What the platform takes from the identity provider

Everything about a person comes from the claims in their token: the username, the tenant, the teams with a role in each, and whether they are a tenant admin or an operator. Configuration files hold no member lists. To add someone to a team, you change their groups at the identity provider; the platform sees it at their next sign-in.

## Choose a mode

`MANAGER_AUTH_MODE` (Helm: `auth.mode`) selects how people sign in.

| Mode | How people sign in | Choose it when |
|---|---|---|
| `oidc` | The login page shows **Sign in**, which leads to your provider's login page (OpenID Connect authorization code flow with PKCE, state and nonce). The manager verifies the ID token and issues its own login cookie. | You want the platform to handle the browser login. This is the usual choice. |
| `jwt` (default) | People arrive with a token your provider or a proxy in front already gave them, as a bearer token or in a cookie. The manager verifies it on every request. | An authenticating proxy already sits in front of your internal applications. |
| `login` | A page without passwords listing test people. | Local evaluation only. |

In every mode the claims go through the same claims parser, described below.

## Set up oidc mode

Register a client at your provider:

- confidential, or public with PKCE;
- redirect URI `https://<manager>/api/auth/oidc/callback`;
- post-logout redirect URI `https://<manager>/login`.

Then set:

| Setting | Helm value | Meaning |
|---|---|---|
| `MANAGER_OIDC_DISCOVERY_URL` | `auth.oidc.discoveryUrl` | The provider's full `.well-known/openid-configuration` URL. Endpoints, signing keys and the issuer come from it. |
| `MANAGER_OIDC_ISSUER` | `auth.oidc.issuer` | The issuer, instead of the discovery URL (the document is then read from `<issuer>/.well-known/openid-configuration`) or besides it (the document must name this issuer). |
| `MANAGER_OIDC_CLIENT_ID` | `auth.oidc.clientId` | The client. ID tokens must name it as their audience. |
| `MANAGER_OIDC_CLIENT_SECRET` or `MANAGER_OIDC_CLIENT_SECRET_FILE` | `auth.oidc.clientSecret.existingSecret` (key `client-secret`, mounted as a file) | The client secret. Leave both out for a public client. |
| `MANAGER_OIDC_SCOPES` | `auth.oidc.scopes` | Default `openid profile email`. Add what your provider needs to send groups. |
| `MANAGER_OIDC_REDIRECT_URL` | `auth.oidc.redirectUrl` | Default `<public URL>/api/auth/oidc/callback`. |
| `MANAGER_OIDC_CLAIMS_SOURCE` | `auth.oidc.claimsSource` | Where the claims are read from: `id_token` (default), `access_token`, `userinfo`. Comma separated; later ones win. |

```sh
MANAGER_AUTH_MODE=oidc
MANAGER_OIDC_DISCOVERY_URL=https://login.microsoftonline.com/<tenant-id>/v2.0/.well-known/openid-configuration
MANAGER_OIDC_CLIENT_ID=<application id>
MANAGER_OIDC_CLIENT_SECRET_FILE=/run/secrets/oidc-client-secret
```

!!! note
    This example only gets people as far as the provider's login. Entra ID does not send Keycloak-style claims: its `groups` are object ids, not paths such as `/acme/demo`, and it has no `tenant` claim. With the default claims parser every such login is refused with *Login refused: your account belongs to no tenant*. For a provider like this you also need [a custom claims parser](#a-custom-claims-parser).

If your provider puts groups only into the access token or the userinfo answer, add that source: `MANAGER_OIDC_CLAIMS_SOURCE=id_token,userinfo`.

Signing out of the platform also ends the session at the provider when the provider publishes an end-session endpoint.

## Set up jwt mode

The manager refuses to start in jwt mode unless it knows the audience, the issuer and the keys. The start-up error names what is missing: `MANAGER_AUTH_MODE=jwt requires MANAGER_JWT_AUDIENCE, MANAGER_JWT_ISSUER or MANAGER_JWT_DISCOVERY_URL, and MANAGER_JWT_JWKS_URL, MANAGER_JWT_JWKS_FILE, MANAGER_JWT_DISCOVERY_URL or MANAGER_JWT_SECRET`.

| Setting | Helm value | Meaning |
|---|---|---|
| `MANAGER_JWT_AUDIENCE` | `auth.jwtAudience` | The audience tokens must carry. |
| `MANAGER_JWT_ISSUER` | `auth.jwtIssuer` | The issuer tokens must carry. |
| `MANAGER_JWT_DISCOVERY_URL` | `auth.jwtDiscoveryUrl` | A full `.well-known/openid-configuration` URL. The issuer and the keys come from it; its issuer must match `MANAGER_JWT_ISSUER` when both are set. |
| `MANAGER_JWT_JWKS_URL` | `auth.jwtJwksUrl` | The key set URL, instead of the discovery document's `jwks_uri`. |
| `MANAGER_JWT_JWKS_FILE` | `auth.jwks.file` | A local key set file instead of a URL. |
| `MANAGER_JWT_SECRET` | `auth.jwtSecret.existingSecret` | A shared secret instead of public keys. |
| `MANAGER_JWT_COOKIE` | `auth.jwtCookie` | The cookie the token is read from when there is no bearer token. Default `access_token`. |

Signing keys rotate without a restart in both modes; see [Govern and operate](operate.md).

## How claims become tenant, teams and roles

The **claims parser** turns a token's claims into a person. The default parser reads what Keycloak sends with a group membership mapper (full group paths) and realm roles:

| Claim | Meaning |
|---|---|
| `preferred_username` (else `sub`) | The username. `platform` is reserved for the platform itself. |
| `email`, `name` | Shown in the UI. |
| `tenant` | The tenant (in Keycloak, a user attribute mapper). Without it, the tenant all of the person's groups agree on. |
| `groups` | `/acme/demo`: member of team `demo` of tenant `acme`. `/acme/demo/admin`: admin of that team. Groups of other tenants are ignored. |
| `realm_access.roles` (or `roles`) | `tenant-admin`: administers the tenant. `platform-operator`: a platform operator. |

For the example company this gives:

| Person | Groups | Roles | Result |
|---|---|---|---|
| alice | `/acme/demo/admin` | `tenant-admin` | Tenant admin of acme, admin of Demo team |
| bob | `/acme/demo`, `/acme/payments` | — | Member of Demo team and Payments |
| dave | `/acme/payments/admin`, `/acme/ops` | — | Admin of Payments, member of Operations |
| frank | `/acme/demo/admin` | — | Admin of Demo team |
| henry | `/acme/ops` | — | Member of Operations |
| oscar | — | `platform-operator` | Operator, in no tenant |

A person belongs to exactly one tenant. Someone in a tenant but in no team can sign in and sees the sessions shared with everyone in the tenant, and a note saying they are not in a team yet.

### A custom claims parser

If your provider doesn't send group paths, write your own parser: a Clojure function in a `.clj` file. It receives the token's claims as a map with keyword keys and returns a map describing the person.

The example fits a provider that sends application roles such as `demo.admin` or `ops.member` in a `roles` claim, an e-mail address as `preferred_username`, and the id of its directory in `tid`, as Entra ID does:

```clojure
;; /config/claims_parser.clj
(ns my.claims
  (:require [clojure.string :as string]))

(def tenants
  {"00000000-0000-0000-0000-000000000000" "acme"})

(defn- membership
  [role]
  (let [[team level]
        (string/split role #"\.")]
    (when (contains? #{"admin" "member"} level)
      {:id team :role (keyword level)})))

(defn parse
  [claims]
  (let [roles
        (:roles claims)

        login
        (string/lower-case (str (:preferred_username claims)))]
    {:username (first (string/split login #"@"))
     :email (:email claims)
     :name (:name claims)
     :tenant (get tenants (:tid claims))
     :teams (vec (keep membership roles))
     :tenant-admin (boolean (some #{"tenant-admin"} roles))
     :operator (boolean (some #{"platform-operator"} roles))}))
```

```sh
MANAGER_CLAIMS_PARSER_FILE=/config/claims_parser.clj
MANAGER_CLAIMS_PARSER=my.claims/parse
```

| Returned key | Required | Meaning |
|---|---|---|
| `:username` | No; defaults to the `sub` claim | Lowercase letters, digits, `.`, `_` and `-`, at most 64 characters. An e-mail address is not a valid username, which is why the example cuts it at `@`. |
| `:email`, `:name` | No | Shown in the UI. |
| `:tenant` | Yes, unless `:operator` | The tenant's name as the operator registered it. |
| `:teams` | No | A list of `{:id "demo" :role :admin}` or `{:id "demo" :role :member}`; the ids are team ids of `tenant.json`. |
| `:tenant-admin` | No | `true` for a tenant admin. |
| `:operator` | No | `true` for a platform operator. |

The platform validates what the parser returns. An answer of the wrong shape refuses the login and says why.

With Helm, put the file in a ConfigMap and set `claims.parserFile.configMap` (key `parser.clj` by default) and `claims.parser`.

The file is loaded at startup and again when it changes. A file that fails to load keeps the last good parser and logs the reason; a parser that is broken at startup stops the manager.

## Operators

An operator is anyone the parser marks (`platform-operator` with the default parser) or whose username is in `MANAGER_OPERATORS` (Helm: `operators`, comma separated in the environment variable). Operators register tenants, see every tenant's sync state, reload configuration and read `/metrics`. They see no tenant's data: an operator without a tenant lands on **Tenants** and nothing else.

## What a refused login looks like

A login is refused when the parser's answer can't be used. In oidc mode the person is returned to the login page, which shows the reason. In jwt mode there is no login page: the request is answered 403 with the same message.

| Message | Cause |
|---|---|
| *Login refused: your account belongs to no tenant* | No `tenant` claim and no groups that agree on one, and the person is not an operator. This is the one you will meet first. |
| *Login refused: the token names no valid username* | No usable `preferred_username` or `sub`. |
| *Login failed: the identity provider could not be reached. Please try again later.* | The manager couldn't reach the provider's endpoints. |

Every refusal with its fix is in [Troubleshooting](../reference/troubleshooting.md#signing-in).

## Worked example: Keycloak on your laptop

The repository has a ready-made Keycloak realm. Start the evaluation stack with the Keycloak overlay:

```sh
docker compose -f compose.yml -f compose.keycloak.yml up -d
```

This adds Keycloak in development mode with the realm `agent-platform` imported from `deploy/keycloak/agent-platform-realm.json`, and switches the manager to oidc mode with these settings:

```sh
MANAGER_AUTH_MODE=oidc
MANAGER_OIDC_DISCOVERY_URL=http://keycloak.localhost:8180/realms/agent-platform/.well-known/openid-configuration
MANAGER_OIDC_CLIENT_ID=agent-platform
MANAGER_OIDC_CLIENT_SECRET=agent-platform-local-secret
MANAGER_OIDC_SCOPES="openid profile email"
MANAGER_OIDC_REDIRECT_URL=http://localhost:8080/api/auth/oidc/callback
```

The realm shows what your own provider needs:

| In the realm | Purpose |
|---|---|
| Groups `/acme/demo`, `/acme/demo/admin`, `/acme/payments`, `/acme/payments/admin`, `/acme/ops`, `/acme/ops/admin` (and the same for `globex`) | Team membership and team admins |
| Realm roles `tenant-admin` and `platform-operator` | Tenant admins and operators |
| A `tenant` user attribute | The person's tenant |
| The confidential client `agent-platform` with PKCE S256 | The platform's client |
| Client mappers `tenant`, `groups` (full paths) and `realm roles` | Put the three claims into the ID token, the access token and userinfo |

The users are the people of the [test login](install.md#sign-in-with-the-test-login), with the password equal to the username. One more user, `nobody`, has no tenant: signing in as `nobody` shows the refusal *Login refused: your account belongs to no tenant*.

Keycloak's admin console is at <http://keycloak.localhost:8180> (`admin` / `admin`). Keycloak has its own port instead of a route through Traefik so that the browser and the manager reach it at the same URL; the manager checks that the issuer in the discovery document and in every token is the same.

!!! warning
    This Keycloak runs in development mode over plain http with known passwords. Use it to learn the mapping, not as an identity provider.

## You now have

- People signing in with their company account.
- Their tenant, teams and roles taken from token claims.
- Named operators.

**Next:** [3. Onboard a tenant](tenant.md)
