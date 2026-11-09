# 6. Automate the work

This page is for team admins who want agent work to start without someone pressing a button. You add a schedule, review pull requests as they appear, deliver changes as pull requests and follow their builds, and let other systems start workflows through webhooks. At the end your team's routine work starts by itself, and you know with whose credentials it runs.

## Who an automated run is

A run that nobody started runs **as the platform, in the team's scope**. That has three consequences:

- **Credentials.** It uses the team's value of a credential, else the tenant's. It never uses a person's. A workflow that runs automatically and needs a credential only people provide (`"provided_by": ["user"]`) is marked invalid at sync.
- **Ownership.** Its session is a **team session**: it belongs to the team, not to a person. Only the team's members and tenant admins may work in it, however widely it is shared. Others who can see it fork it and continue in their own copy with their own credentials.
- **Requirements.** Required credentials are asked of people, not of the platform, so they never stop an automated run.
- **Session limit.** The team's `max_running_sessions` still counts: an automated run that would exceed it fails when it tries to create its session, with the limit's message.

A team admin can also start a team session by hand with **Team session** on the team's page; it follows the same rules and is shared with the team or the tenant, never with **Only me**.

## Schedules

A `schedule` trigger starts a workflow on an interval with fixed inputs. The Operations team's `.platform/workflows/scheduled-workspace.json`:

```json
{
  "name": "Scheduled workspace",
  "description": "The platform opens a workspace every few minutes. It works with the team's credentials, so only team members can work in it, although everyone can see it.",
  "activity": "session",
  "agent": "operator",
  "harness": "sandboxed",
  "trigger": { "type": "schedule", "every_minutes": 5 },
  "options": {
    "repository": { "url": "file:///repos/demo-service", "branch": "main" }
  },
  "visibility": "tenant"
}
```

`every_minutes` is between 5 and 10080 (one week). `inputs` holds the inputs every run gets, for example the brief of a weekly analysis:

```json
"trigger": { "type": "schedule", "every_minutes": 10080, "inputs": { "brief": "Summarise last week's incidents." } }
```

Tenant admins see every schedule and trigger under **Admin → Background jobs**, labelled **Run on a schedule (…)** and **Check for new pull requests (…)**.

## Review pull requests as they appear

A `pull_request` trigger polls a repository and starts one review per new pull request. It can only start the `review` activity. The Demo team's `.platform/workflows/github-review.json`:

```json
{
  "name": "Review GitHub pull requests",
  "description": "Reviews every new PR on a GitHub repository (dry-run: nothing is posted).",
  "activity": "review",
  "agent": "reviewer",
  "harness": "read-only",
  "trigger": {
    "type": "pull_request",
    "provider": "github",
    "repository": "your-org/your-repo",
    "credentials": "GITHUB_TOKEN",
    "poll_minutes": 15
  },
  "options": { "guidelines_file": "context/review-guidelines.md", "post_mode": "dry-run", "max_comments": 20 }
}
```

How it behaves:

- Pull requests that are already open when the trigger first runs are skipped, unless `"backfill": true`.
- Each pull request is reviewed once. A new patch set of an already reviewed Gerrit change is not reviewed again.
- `target_branches` limits it to pull requests into those branches.
- The workflow can also be run by hand for any pull request number.

**What gets posted** is decided by `post_mode`, and by the platform, never by the agent. The agent submits a structured review; the platform turns it into at most `max_comments` comments (a summary plus inline comments, anchored only to lines that are in the diff, highest severity first).

| `post_mode` | Effect |
|---|---|
| `off`, `dry-run` (default) | Nothing is posted. The review and the comments the platform would post are on the run's page only. Start here. |
| `auto` | The comments are posted on the pull request. |

### With a project

If the repository is a [project](team.md#projects) of your `team.json`, the workflow names it and leaves the source out. The Demo team's `.platform/workflows/gerrit-review.json`:

```json
{
  "name": "Review Gerrit changes",
  "description": "Reviews every new change of the demo-service project in Gerrit and posts the review there: a summary, inline comments and a Code-Review vote.",
  "activity": "review",
  "agent": "reviewer",
  "harness": "read-only",
  "project": "demo-service",
  "trigger": { "type": "pull_request", "poll_minutes": 1 },
  "options": { "guidelines_file": "context/review-guidelines.md", "post_mode": "auto", "max_comments": 10 }
}
```

The trigger's provider, repository, address and credential come from the project. Anything the workflow sets itself wins.

### Bitbucket, GitHub and Gerrit

The three providers are interchangeable wherever pull requests appear.

| | Bitbucket, GitHub | Gerrit |
|---|---|---|
| What is reviewed | Open pull requests | Open changes of the project; the latest patch set |
| A review in `auto` mode | A summary comment and inline comments | One review on the latest patch set: summary, inline comments and a Code-Review vote from the verdict (approve +1, approve with nits 0, request changes -1) |
| Credential | A token | `kind: basic`: the Gerrit username and its HTTP password |

The credential's `hosts` must include the provider's API host (`api.bitbucket.org`, `api.github.com`, or the host of a Gerrit's `url`).

## Deliver changes and follow their builds

By default an `implement` workflow returns a patch. With `options.pull_request` it hands the change over as a pull request, and with `options.ci` it follows the build.

```json
"options": {
  "repository": { "url": "https://bitbucket.org/acme/service.git", "branch": "main", "credentials": "BITBUCKET_TOKEN" },
  "pull_request": { "provider": "bitbucket", "repository": "acme/service" },
  "ci": { "mcp_server": "jenkins", "job": "acme/service/PR-{pr}", "timeout_minutes": 120, "on_failure": "build-failure" }
}
```

With a project, all of this comes from `team.json`. The Demo team's `.platform/workflows/implement-gerrit.json`:

```json
{
  "name": "Implement a change in Gerrit",
  "description": "Describe a change; the agent implements it, the platform pushes it to Gerrit for review and follows its Jenkins build.",
  "activity": "implement",
  "agent": "engineer",
  "harness": "sandboxed",
  "project": "demo-service",
  "options": {
    "guidelines_file": "context/coding-guidelines.md",
    "ci": { "timeout_minutes": 30, "on_failure": "build-failure" }
  }
}
```

What happens:

1. **The platform pushes, never the agent.** After the agent submits, the platform commits the working tree as the person who started the run, pushes a branch `agent/<run id>` with that person's credential (or the shared one), and opens a pull request. For Gerrit it pushes to `refs/for/<branch>` instead. Nothing is ever merged.
2. **Unverified work is marked.** If the agent reports that its checks didn't pass, the pull request is opened as a draft and says so.
3. **Continuing on a pull request.** In the run form you can pick an open pull request (the list is read with your credential). Its branch is checked out and the change is pushed to it, without a new pull request. On Gerrit, the change gets a new patch set.
4. **Following the build.** With `ci`, the run gets a **Following build** step. Every minute the platform reads the job through the MCP server's `get_build` tool until the build passes, fails, or `timeout_minutes` runs out. The run's page shows the build number, its stages and a link.
5. **Analysing a failure.** With `ci.on_failure`, a failed build starts that workflow (a manual `analysis` or `session` workflow of the same team) for the same person, with the failed stages and the end of the console log. The original run links to it.

**Which build is followed.** A `job` containing `{pr}` (`acme/service/PR-{pr}`) is a job per pull request; its last build is followed. A job without `{pr}` is shared, like a Gerrit verify job that builds every patch set. The platform then follows the newest build whose description names the pushed commit (the first 12 characters are enough), so the job must put the commit into its build description. Shared jobs need the MCP server's `get_builds` tool too.

The CI's MCP server must allow the tools this needs (`get_build`, `get_build_log` for `on_failure`, `get_builds` for shared jobs); otherwise the workflow is invalid and the message names them.

### Try it locally

The CI overlay adds a Gerrit and a Jenkins to the evaluation stack, with accounts, a project and jobs already set up:

```bash
./scripts/seed-local-repos.sh
docker compose -f compose.yml -f compose.ci.yml up -d --build
```

Nothing needs configuring by hand; every step is automatic and safe to repeat:

- **Gerrit** starts with its admin account. A one-shot `gerrit-init` container sets the admin's HTTP password, creates the platform's account `platform` and the project `demo-service` from `examples/demo-service`.
- **Jenkins** (configuration in `deploy/jenkins`) has the users `admin` and `platform` (with a fixed API token), the job `demo-service-verify` (checks out a patch set, runs `python3 -m unittest`, names the commit in the build description) and `gerrit-poll`, which every minute starts the verify job for each new patch set of an open change.
- The manager gets `MANAGER_HTTP_GIT_HOSTS=gerrit`, because this Gerrit speaks plain http on the compose network, and the acme tenant's values of the `GERRIT_TOKEN` and `JENKINS_TOKEN` credentials as operator secrets. The `mcp-jenkins` server gets `JENKINS_URL=http://jenkins:8080`.

| URL | What |
|---|---|
| <http://gerrit.localhost:8080> | Gerrit. **Sign in** lets you become any account; the API takes `admin` / `gerrit-admin-secret` or `platform` / `gerrit-platform-secret`. |
| <http://jenkins.localhost:8080> | Jenkins (`admin` / `jenkins-admin-secret`) |

The passwords are local defaults; the variables `GERRIT_ADMIN_PASSWORD`, `GERRIT_PLATFORM_PASSWORD`, `JENKINS_ADMIN_PASSWORD`, `JENKINS_PLATFORM_PASSWORD` and `JENKINS_PLATFORM_TOKEN` override them. Both services are routed by Traefik; the manager and the MCP servers reach them as `gerrit:8080` and `jenkins:8080`.

Push a change to see **Review Gerrit changes** review it within a minute:

```bash
git clone http://gerrit.localhost:8080/demo-service && cd demo-service
curl -so .git/hooks/commit-msg http://gerrit.localhost:8080/tools/hooks/commit-msg && chmod +x .git/hooks/commit-msg
# edit, commit, then:
git push http://admin:gerrit-admin-secret@gerrit.localhost:8080/a/demo-service HEAD:refs/for/main
```

Without this overlay the Demo team's two Gerrit workflows have nothing to talk to: the review trigger records a connection error and **Implement a change in Gerrit** is invalid.

The repository's automated check for these two flows, and the command that removes the overlay with its data:

```bash
(cd service && MANAGER_URL=http://localhost:8080 clojure -M:e2e-ci)
docker compose -f compose.yml -f compose.ci.yml down -v
```

## Webhooks from other systems

A webhook is a URL that starts a workflow when another system calls it: an issue tracker when a ticket is created, a CI system when a build fails, a Git host on a push. You create webhooks in the UI under **Webhooks**, not in the repository.

### Scope and target

| Scope | Managed by | May target |
|---|---|---|
| Tenant | Tenant admins | Any team's workflow |
| Team | The team's admins and tenant admins | A workflow of the team |
| Project | The team's admins and tenant admins | A workflow of the project or one of the team's own, never another project's |

The scope can't be changed afterwards. Members of the team see a webhook, its URL, its mapping and its recent calls, but never the secret, and they can't edit it.

### The endpoint

```text
POST or PUT  https://<manager>/hooks/<tenant>/<webhook-id>
```

The caller needs no login. A successful call is answered `202` with the id of the run it started. The run starts as the platform in the target team's scope, as described at the top of this page.

### How the caller authenticates

The types you will use most:

| Type | The caller sends |
|---|---|
| **Header auth** | The secret in a header you name, for example `X-Webhook-Token` |
| **Bearer token** | `Authorization: Bearer <token>` |
| **HMAC signature** | A hex HMAC of the raw body in a header you name, as GitHub's `X-Hub-Signature-256` does |

**Basic auth** and **None** exist too; all five, with `curl` commands, are in the [Webhooks reference](../reference/webhooks.md#authentication). Each webhook's page shows the commands filled in for its URL and type.

You enter the secret or let the UI generate one. It is stored encrypted and afterwards visible only as a hint. **Regenerate secret** creates a new random one, shows it once, and stops the old one at once.

!!! note "Rules for signed (HMAC) webhooks"
    A signature covers the request body and nothing else. So a signed webhook:

    - maps its inputs **from the body only**, not from query parameters or headers;
    - **can't allow GET**, because a GET request has no body to sign.

    Saving a webhook that breaks either rule is refused. With a **timestamp header**, the signed text is `<timestamp>.<body>` and the timestamp (unix seconds) must be within the tolerance of the manager's clock (300 seconds by default), which limits how long a captured call can be replayed.

For sources on the internet, prefer HMAC with a timestamp. For internal sources, add allowed addresses.

### Mapping the request to inputs

Every input of the target workflow gets a row saying where its value comes from: a **Body path** (`$.pull_request.title`, `commits.0.id` or `/issue/fields/summary`), a **Header**, a **Query parameter**, a **Fixed value**, or the **Whole body as text** for free-text inputs such as an analysis brief. The path notations and how values are converted are in the [Webhooks reference](../reference/webhooks.md#input-mapping).

Inputs you don't map are left out, so the workflow's defaults apply. The workflow's input schema decides whether the result is valid; an invalid call is answered `422` with the errors per field.

### Test before you connect the other system

The **Test** section of a webhook's page takes a sample body, headers and query.

- **Preview inputs** shows what each input would be, and its validation error, without starting anything.
- **Send test** makes the manager call the webhook itself, authenticated with the stored secret, and shows the answer and a link to the run it started. It checks the secret, the mapping and the workflow. It does not check the way in from outside: the call goes straight to the manager's own listener, not through your DNS, TLS or proxy, and it skips the allowed addresses. Make one real call from the other system before you rely on it.

The last 50 calls are listed under **Recent calls**, with the time, the source address, the answer, the run and the reason for a refusal. The caller is never told why a call was refused; you read it there.

Limits, every status code and the mapping syntax are in the [Webhooks reference](../reference/webhooks.md).

## You now have

- Workflows that start on a schedule and for every new pull request or Gerrit change.
- Implementation runs that open pull requests and follow their builds.
- Webhooks other systems can call, tested from the platform's side.
- A clear answer to "as whom does this run?": the platform, with the team's credentials.

**Next:** [7. Govern and operate](operate.md)
