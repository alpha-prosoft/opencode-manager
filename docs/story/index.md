# The path from zero to a working platform

This page is for the person responsible for bringing the Agent Platform into a company. It lists every step from an empty machine to teams that run agents with their own credentials, with who does each step. At the end you know what to prepare and in which order to read the next seven pages.

The pages use one example company throughout, the one shipped in the repository's `examples/` directory, so every command and file can be copied:

| | In the example |
|---|---|
| Tenant | `acme` |
| Teams | Demo team (`demo`), Payments (`payments`), Operations (`ops`) |
| Platform operator | `oscar` |
| Tenant admin | `alice` (also admin of Demo team) |
| Team admin | `frank` (admin of Demo team), `dave` (admin of Payments, member of Operations) |
| Members | `bob` (Demo team and Payments), `henry` (Operations) |

The words tenant, team, harness, workflow, run and session are defined once, in [Terms](../index.md#terms).

## Before you start

| You need | Why |
|---|---|
| Docker with Compose v2 (evaluation) or a Kubernetes cluster whose network plugin enforces NetworkPolicies (production) | The platform runs every session as a container or a pod. |
| An OpenID Connect identity provider (Keycloak, Entra ID, Okta, PingFederate, …) | Sign-in, tenant, team membership and roles all come from its tokens. The evaluation stack has a test login and an optional local Keycloak, so you can postpone this. |
| An LLM provider credential | A GitHub Copilot token or the API key of any OpenAI-compatible endpoint (Azure OpenAI, vLLM, LiteLLM, …). |
| Git hosting the platform can reach | One onboarding repository per tenant and one repository per team. The evaluation stack publishes the examples as local repositories. |

## The checklist

The times are rough estimates for the example setup. In a company, the waiting time for an identity provider client, DNS and a certificate usually decides how long the whole path takes.

| # | Step | Who | Rough time | Page |
|---|---|---|---|---|
| 1 | Start the evaluation stack on a laptop and sign in with the test login | Platform operator | 30 minutes | [Install: evaluation on a laptop](install.md#evaluation-on-a-laptop) |
| 2 | Install on Kubernetes with the Helm chart: domain, storage, secrets, credentials key | Platform operator | Half a day | [Install: production on Kubernetes](install.md#production-on-kubernetes) |
| 3 | Register a client at your identity provider and map groups to tenant, teams and roles | Platform operator with the identity team | Half a day | [Connect your identity provider](identity.md) |
| 4 | Create the tenant's onboarding repository with `tenant.json` and register the tenant | Tenant admin, platform operator | 1 to 2 hours | [Onboard a tenant](tenant.md) |
| 5 | Check the first sync and set the tenant's credential values | Tenant admin | 30 minutes | [Onboard a tenant](tenant.md) |
| 6 | Create a team repository: `team.json`, an agent, a harness, the first workflows | Team admin | 1 to 2 hours per team | [Set up a team](team.md) |
| 7 | Members sign in, connect their accounts and run a workflow | Member | 15 minutes | [First day in a team](first-day.md) |
| 8 | Add schedules, pull request reviews, change delivery and webhooks | Team admin | As needed | [Automate the work](automate.md) |
| 9 | Set budgets, watch usage and the audit trail, plan backups, go through the security checklist | Tenant admin, platform operator | Half a day, then ongoing | [Govern and operate](operate.md) |

!!! note
    Steps 2 and 3 belong together: the Helm chart needs the sign-in settings at install time. If you go straight to Kubernetes, read [Connect your identity provider](identity.md) before you install.

## Who owns which file

Most questions of the kind "where do I change that?" are answered by this table.

| What | Where it lives | Who changes it | Takes effect |
|---|---|---|---|
| Which tenants exist, sign-in, network, storage | Server settings (`MANAGER_*`, Helm values) | Platform operator | On restart; the tenants file and claims parser file reload by themselves |
| LLM connections, allowed images, credentials, MCP servers, the list of teams | `tenant.json` in the onboarding repository | Tenant admin | At the next sync (every 10 minutes by default, or when an admin asks for one) |
| Budget, limits, models, projects, required credentials of a team | `.platform/team.json` in the team repository | Team admin | At the next sync |
| Agents, harnesses, workflows | `.platform/agents`, `harnesses`, `workflows` in the team repository | Team admin | At the next sync |
| Who is in which team with which role | Your identity provider (groups and roles) | Identity team | At the person's next sign-in or token |
| Credential values | The platform UI | Tenant admin, team admin or each person, depending on the credential | At once |

## You now have

- The list of things to prepare.
- The order of the steps and who does each one.

**Next:** [1. Install the platform](install.md)
