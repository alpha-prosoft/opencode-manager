# Webhooks

This page is for team admins and tenant admins who connect other systems to the platform, and for the people who configure those systems. It gives the endpoint, the authentication types, the mapping syntax, the limits, every status code and what the delivery log keeps. For an introduction, read [Automate the work](../story/automate.md).

## What a webhook is

A webhook is a URL that starts one workflow of a team when another system calls it. It is created in the UI (**Webhooks**, or the **Webhooks** tab of a team or project), not in a repository. Screens are in the [guide for team admins](../guide/team-admin.md).

| Scope | Listed with | Managed by | May target |
|---|---|---|---|
| Tenant | The tenant | Tenant admins | A workflow of any team |
| Team | The team | The team's admins and tenant admins | A workflow of the team |
| Project | The team, filtered by project | The team's admins and tenant admins | A workflow of the project or one of the team's own, never another project's |

- The scope can't change after creation.
- Members of the scope's team and of the target team see the webhook, its URL, mapping and recent calls. They never see the secret or its hint, and they can preview mappings but not edit the webhook, regenerate its secret or send tests.
- People of other teams get 403, other tenants 404.

## Settings of a webhook

| Setting | Limits and default | Meaning |
|---|---|---|
| Name | Up to 100 characters | Shown in lists and recorded on the runs it starts. |
| Description | Up to 1000 characters | |
| Scope | Tenant, team or project | See above. |
| Target | A team and one of its valid workflows | The workflow each call starts. |
| Authentication | One of the [types below](#authentication) | How the caller proves itself. |
| Input mapping | Up to 50 rows | Where each workflow input comes from. |
| Allowed addresses | Up to 50 CIDRs; empty means any | Source addresses that may call. |
| Allow GET | Off by default | Also accept `GET` with query parameters. Not possible with HMAC. |
| Rate limit | 1 to 600 calls a minute, default 60 | See [Rate limit](#rate-limit). |
| Enabled | | A disabled webhook answers 404. |

## Endpoint

```text
POST https://<manager>/hooks/<tenant>/<webhook-id>
PUT  https://<manager>/hooks/<tenant>/<webhook-id>
GET  https://<manager>/hooks/<tenant>/<webhook-id>?…     only when the webhook allows GET
```

The endpoint is on the public listener and is meant for machines: no login, no cookies. Request bodies are limited like every request (`MANAGER_MAX_BODY_BYTES`, 1 MB by default). JSON bodies and form bodies (`application/x-www-form-urlencoded`) can be mapped by path.

## Status codes

| Answer | When |
|---|---|
| `202` `{"run-id": "…", "delivery-id": "…"}` | A run started. |
| `401` `{"detail": "Unauthorized"}` | Missing or wrong credentials. The reason is only in the delivery log. |
| `403` `{"detail": "Forbidden"}` | The caller's address is outside the webhook's allowed addresses. |
| `404` `{"detail": "Not found"}` | Unknown, deleted or disabled webhook, or a path under another tenant. These are indistinguishable. Not recorded as a delivery. |
| `405` `{"detail": "Use POST or PUT"}` | `GET` on a webhook that doesn't allow it, or another method. Not recorded as a delivery. |
| `422` `{"detail": "The request doesn't map to valid inputs of the workflow", "errors": [{"field": "…", "message": "…"}]}` | The mapped inputs fail the workflow's input schema. |
| `429` `{"detail": "Too many requests"}` with `Retry-After` | Over the rate limit. Not recorded as a delivery. |
| `500` `{"detail": "The request could not be processed"}` or `"The run could not start"` | The manager failed while handling the call. The delivery log says why. |

If starting the run is refused for a reason the platform can state (for example the target workflow became invalid), the call is answered with that refusal's status and message.

## What a call starts

The run starts **as the platform in the target team's scope**, like a scheduled run:

- it uses the team's credential values, else the tenant's, never a person's;
- its session belongs to the team; people outside the team fork it to continue;
- it records its origin: `trigger: {type: "webhook", webhook-id, webhook-name, delivery-id}`.

## Authentication

| Type | The caller sends | Secret |
|---|---|---|
| **Header auth** | The secret in a header you name | Required |
| **Bearer token** | `Authorization: Bearer <token>` | Required |
| **Basic auth** | The username you configured and the secret as password | Required |
| **HMAC signature** | A hex HMAC of the raw body in a header you name | Required |
| **None** | Nothing | — |

Secrets are 8 to 4096 characters. You enter one or let the UI generate a random one. Secrets are sealed at the edge like credential values, stored encrypted and shown afterwards only as a hint. **Regenerate secret** creates a new random secret, shows it once, and stops the old one at once. All comparisons are constant-time.

A header name you choose (for the secret, the signature or the timestamp) holds letters, digits and dashes only, starts with a letter or digit, and is at most 64 characters. It can't be `Authorization`, `Cookie`, `Host`, `Content-Type`, `Content-Length` or `X-Forwarded-For`.

```sh
# Header
curl -X POST "$URL" -H "X-Webhook-Token: $WEBHOOK_SECRET" -H "Content-Type: application/json" --data "$BODY"

# Bearer
curl -X POST "$URL" -H "Authorization: Bearer $WEBHOOK_TOKEN" -H "Content-Type: application/json" --data "$BODY"

# Basic
curl -X POST "$URL" -u "ci:$WEBHOOK_PASSWORD" -H "Content-Type: application/json" --data "$BODY"

# None
curl -X POST "$URL" -H "Content-Type: application/json" --data "$BODY"
```

Each webhook's page shows these commands filled in for its URL and type.

!!! warning
    With type **None**, anyone who knows the URL starts the workflow. Restrict the allowed addresses.

### HMAC signatures

| Setting | Values | Meaning |
|---|---|---|
| Header name | A header name | Where the signature is sent, for example `X-Hub-Signature-256` (GitHub) or `X-Gitea-Signature` (Gitea). |
| Algorithm | `sha256` (default) or `sha1` | The HMAC algorithm. |
| Timestamp header | Optional header name | Makes the timestamp part of the signed text. |
| Tolerance | 30 to 3600 seconds, default 300 | How far the timestamp may be from the manager's clock. |

The signature is the lowercase hex HMAC of the raw request body under the secret. A prefix such as `sha256=` is accepted and ignored.

Because the signature covers the body and nothing else, a signed webhook:

- **maps inputs from the body only**: body paths, the whole body, and fixed values. Query parameters and headers can't be mapped. Saving is refused with *A signed (HMAC) webhook maps inputs from the body only: the signature doesn't cover the query string or headers*.
- **can't allow GET**. Saving is refused with *A signed (HMAC) webhook can't allow GET: a GET request has no body to sign*.

With a timestamp header, the signed text is `<timestamp>.<body>`, where the timestamp is unix seconds. A call whose timestamp is outside the tolerance is refused, so a captured call can be replayed only inside that window. The manager keeps no list of calls it has seen.

```sh
BODY='{"issue":{"summary":"Checkout times out under load"}}'
TIMESTAMP=$(date +%s)
SIGNATURE=$(printf '%s' "$TIMESTAMP.$BODY" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | sed 's/^.* //')
curl -X POST "$URL" -H "Content-Type: application/json" \
  -H "X-Hub-Signature-256: sha256=$SIGNATURE" -H "X-Timestamp: $TIMESTAMP" --data "$BODY"
```

Without a timestamp header, drop `$TIMESTAMP.` from the signed text.

## Input mapping

Each row maps one input of the target workflow to a source.

| Source | Value you enter | Reads |
|---|---|---|
| Body path | A path | A value inside a JSON or form body |
| Header | A header name (case-insensitive) | That request header |
| Query parameter | A parameter name | That query parameter |
| Fixed value | Any text | Always that text |
| Whole body as text | — | The raw body, cut to 20,000 characters |

### Body paths

Three notations reach the same value:

| Notation | Example |
|---|---|
| JSONPath-like | `$.pull_request.title`, `$.commits[0].id`, `$['pull_request']['title']` |
| Dotted | `pull_request.title`, `commits.0.id` |
| JSON pointer | `/issue/fields/summary`, `/commits/0/id` |

For the body `{"commits": [{"id": "abc"}], "issue": {"fields": {"summary": "Slow checkout"}}}`, both `$.commits[0].id` and `/commits/0/id` give `abc`.

### Conversion

| The value is | The input expects | Result |
|---|---|---|
| An object or array | anything | Its JSON text |
| A number or boolean | a string | Its text |
| A string of digits such as `"42"` | an integer | The number 42 |
| `"true"` or `"false"` | a boolean | The boolean |

Rows whose source has no value are left out, as are inputs without a row, so the workflow's defaults apply. The workflow's input schema then decides: a missing required input or a wrong type answers `422` with one error per field.

## Source address

The caller's address is the address of the TCP connection. Behind a reverse proxy that is the proxy's address. The manager takes the caller's address from `X-Forwarded-For` **only** when the connection comes from a proxy listed in `MANAGER_TRUSTED_PROXIES` (addresses, CIDRs, or host names standing for their current addresses); it then uses the last address of the header.

| Deployment | `MANAGER_TRUSTED_PROXIES` |
|---|---|
| Default | Empty: `X-Forwarded-For` is never trusted |
| `compose.yml` | `traefik-public` |
| Helm chart (`trustedProxies`) | The private address ranges; narrow it to your pod CIDR |

Without a trusted proxy configured, a webhook's allowed addresses behind a proxy are compared with the proxy's own address.

## Rate limit

Each webhook has a limit of calls per minute (default 60), counted per manager process.

- Calls the webhook accepts share one allowance.
- Refused calls (wrong secret, wrong address) have the same allowance per source address instead, so someone without the secret can't use up the real caller's allowance.
- A call over the limit is answered `429` with `Retry-After` and is not recorded.

## Testing

The **Test** section of a webhook's page takes a sample body, headers and query.

| Action | Who | What it does |
|---|---|---|
| **Preview inputs** | Members of the target team | Shows what each input would be, which are missing, and validation errors. Starts nothing. |
| **Send test** | Those who manage the webhook | The manager calls the webhook's endpoint itself, authenticated with the stored secret, and shows the answer and a link to the run it started. |

What **Send test** does and doesn't prove:

- The call goes to the manager's own public listener (`127.0.0.1` on its internal port), not through your router, TLS or DNS. It proves the secret, the mapping and the workflow, not the path from outside.
- The call carries a token only the manager's process knows. Only such a call is shown as a test under **Recent calls**, and it skips the allowed addresses, because it comes from the manager and not from the real caller.
- It still needs the stored secret, and it starts a real run.
- Of the sample headers, `Host`, `Content-Length`, `Cookie`, `Connection` and `X-Forwarded-For` are not sent.
- The method is `POST`, or `GET` when the sample says so.

## Delivery log

The last 50 calls are kept per webhook and shown on its page under **Recent calls**. Calls answered `404`, `405` or `429` are never recorded.

| Field | Notes |
|---|---|
| Time | |
| Source address | Behind a trusted proxy, the address the proxy appended to `X-Forwarded-For`. |
| Method and answer | The status code returned. |
| Run | A link to the run, when one started. |
| Error | Why a call was refused; see the table below. The caller is never sent this. |
| Headers | At most 50. `Authorization`, `Proxy-Authorization`, `Cookie`, `X-Api-Key`, `X-Auth-Token` and the webhook's own secret or signature header are shown as `[redacted]`. |
| Query | |
| Body | Cut to 4 KB. |

Reasons recorded for a refused call:

| Recorded error | Answer | Meaning |
|---|---|---|
| *Source 203.0.113.9 is not allowed* | 403 | The address is outside the allowed addresses. |
| *Header X-Webhook-Token is missing or wrong* | 401 | Header auth: no header, or another secret. |
| *Bearer token is missing or wrong* | 401 | |
| *Basic credentials are missing or wrong* | 401 | Wrong username or password. |
| *Signature in X-Hub-Signature-256 is missing or wrong* | 401 | The signature doesn't match the body and secret. |
| *Header X-Timestamp is missing or not unix seconds* | 401 | The timestamp header is required and must be a number. |
| *Timestamp is outside the 300 s window* | 401 | The caller's clock, or a replayed call. |
| *The webhook has no secret* | 401 | The webhook needs a secret and has none; regenerate it. |
| The input errors, as `field: message` | 422 | The mapped inputs are invalid. |
| The reason the run could not start | 4xx or 500 | For example the target workflow became invalid. |

## Security notes

- A secret never reaches the platform's command log, events or application logs in plaintext: only the sealed value, bound to the webhook and its tenant.
- An unknown webhook and a disabled one answer the same 404.
- The reason for a refused call is never sent to the caller.
- For sources on the internet, prefer HMAC with a timestamp. For internal sources, add allowed addresses.
- Regenerating a secret stops the old one at once.

## Messages when saving

| Message | Meaning |
|---|---|
| *A name is required*, *The name is at most 100 characters*, *The description is at most 1000 characters* | |
| *Choose the tenant, a team or a project as the webhook's scope* | No scope given. |
| *A webhook's scope can't change; create a new webhook instead* | An edit tried to change the scope. |
| *Team … does not exist*, *Project … does not exist in team …* | The scope names something the tenant doesn't have. |
| *A team's or project's webhook starts a workflow of that team* | The target is in another team. |
| *Workflow … belongs to project …, not …* | A project's webhook can't target another project's workflow. |
| *Workflow …/… does not exist or is invalid* | The target workflow is missing or invalid. |
| *Only tenant admins manage tenant webhooks*, *Only admins of team … manage its webhooks* | The caller may not manage this scope (403). |
| *Choose how callers authenticate* | No valid auth type. |
| *The header name is required*, *The header name may hold letters, digits and dashes only*, *The header name can't be …* | Header auth and HMAC need a usable header name. The same three exist for *The timestamp header*. |
| *Basic auth needs a username* | |
| *The signature algorithm is sha256 or sha1* | |
| *The timestamp tolerance is 30 to 3600 seconds* | |
| *A signed (HMAC) webhook maps inputs from the body only: the signature doesn't cover the query string or headers* | Remove header and query sources from the mapping. |
| *A signed (HMAC) webhook can't allow GET: a GET request has no body to sign* | Turn off GET. |
| *A secret is required for this auth type*, *The secret needs at least 8 characters*, *The secret is at most 4096 characters* | |
| *This webhook takes requests without a secret* | Regenerating a secret on a webhook of type **None**. |
| *The workflow has no input …*, *Every mapped row names an input*, *Unknown source for …* | A mapping row is wrong. |
| *At most 50 mapped inputs*, *At most 50 allowed addresses* | |
| *… is not an IP address or CIDR range* | An allowed address is malformed. |
| *The rate limit is 1 to 600 requests a minute* | |

**Send test** by someone who doesn't manage the webhook answers 403 *Only admins of the webhook's scope can do this*.
