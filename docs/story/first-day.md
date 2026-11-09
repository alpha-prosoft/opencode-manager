# 5. First day in a team

This page is for a new member of a team, and for admins who want to know what members will meet. It follows `bob`, a member of Demo team and Payments at `acme`, from signing in to sharing a result. At the end you have run a workflow, read its result and worked in a session. Screenshots of every step are in the [guide for team members](../guide/member.md).

## Sign in

Open the platform's address and sign in with your company account. Your tenant, your teams and your role in each come from that account; nobody adds you inside the platform. If you are in the tenant but in no team yet, the platform tells you so and shows only what is shared with everyone in the tenant. Ask your tenant admin or the people who manage groups at your identity provider.

The sidebar shows where you are: the tenant under the product name, and a team switcher with **All my teams** or one team and your role in it. The team you choose is part of the URL (`?team=demo`), so links and reloads keep it.

## Connect your accounts

Some tools act with your own account: your Bitbucket token, your Jira login. The first time you sign in, and whenever your teams' tools need a credential you haven't seen yet, you land on **Connect your accounts**, with one step per credential. Each step says what the credential is for and where to create it. Its button is **Check and save Bitbucket** when the credential has a check: the value is tested against the service and stored only if it works. A credential without a check has **Save Jira** instead.

A credential that only you can provide and that you still lack is offered again at every sign-in, until you add it.

There are two kinds of credential on this page:

- **Optional ones.** A team or tenant value exists or may exist, and yours would only make the tools act as you. You can select **Do this later** and add them at any time under **Accounts**.
- **Required ones**, marked **Required**. Your tenant or one of your teams has decided that this tool must act as you, never through a shared account.

!!! warning "Required credentials block everything until you add them"
    While you lack a required credential, the platform holds you on this page. **Do this later** is disabled, every other page sends you back here, and the only ways out are to add the credential or to sign out. The service also refuses to start a run, a team session or a fork in the team that requires it.

    The reason is accountability: with a required credential, everything the agent does in that tool is done under your name with your permissions, and a shared account would hide who did it. A team's or the tenant's value never satisfies the requirement.

A requirement applies to you only for teams your account is actually a member of. In the example, `henry` is in Operations, where the tenant requires PagerDuty and the team requires Jira; `bob` is not in Operations and is asked for neither.

Your values are encrypted, never shown again (only a short hint), and never sent into a sandbox. **Accounts** shows, per credential, which value applies in each of your teams, for example "Demo team: team value · Payments: tenant value".

## Run a workflow

**Home** shows your teams' workflows. Automated workflows have a **Run** button, interactive ones **Start session**.

1. Find **Requirements analysis** in Demo team and select **Run**.
2. The dialog states three things before it asks for anything: **Team** (the team the run belongs to), **Runs as** (whose credentials it uses) and **Shared with** (who will see its session). Change **Shared with** if you want: **Only me**, **Team Demo team** or **Everyone in acme**.
3. Fill in the inputs. For an analysis that is the brief: what you want analysed.
4. Select **Run** at the bottom of the dialog (**Start session** for an interactive workflow). You are taken to the run's page.

If the team is at its limit of running sessions, the start is refused with the reason: *Team demo already has 10 running sessions (limit 10). Stop one and retry.*

## Read the result

The run's page shows progress while the agent works, then the result first:

| Workflow kind | Result |
|---|---|
| Analysis | A Markdown document. |
| Implementation | A title and summary, the commands the agent ran to check its work and whether they passed, and a patch you can download. If the workflow delivers changes, the pull request or Gerrit change and its build. |
| Review | A verdict, findings with severity, file, line and a suggested fix, and what was checked. |

Below the result are the inputs, the details (team, who started it, whose credentials, who the session is shared with) and the LLM usage. An active run has **Cancel run**; a finished automated run has **Run again**.

Everyone in the team sees the team's runs and their results.

## Open the session

Every run has a session: the sandbox the agent worked in. The run's page has **Open session**, which takes you straight into OpenCode if you may work in the session. The session also has a page of its own, reached from **Sessions** or from the session's name on the run:

- **Overview**: owner, team, **Shared with**, fork and lock history, usage.
- **Conversations**: what was said.
- **Live activity**: tools the agent used, files it edited, and calls the harness policy blocked.
- **Audit trail**: the complete record, which can be verified and downloaded.

On the session's page, **Open** starts the session in OpenCode in your browser so you can continue the conversation yourself. A session stops when it has been idle for a while and starts again when you open it.

For interactive work, start a **Workspace** workflow with **Start session** instead: it gives you a session of your own from the start, shared with nobody unless you choose otherwise.

## Share, fork and lock

**Share.** A session is shared with **Only me**, your team, or everyone in the tenant. You change this in the **Shared with** section of the session's page.

**Fork.** Anyone who can see a session can fork it. The platform copies the workspace and every conversation into a new session that stays in the same team, is shared with **Only me** (you), and runs with your credentials. Fork when:

- the session belongs to someone else and uses their personal credentials;
- it is a team session and you are not in that team;
- it is locked.

In each of these cases the session's page says why you can't work in it directly and offers the fork.

**Lock.** The owner can lock a session. It stops and can never be continued, not even by the owner, but everyone who could see it can still read and fork it. Lock a session when its state should be kept as a record.

Who can see and work in which session is laid out in [Roles and visibility](../reference/roles.md).

## You now have

- Your accounts connected, including any your teams require.
- A finished run with its result.
- A session you can open, share and fork.

**Next:** [6. Automate the work](automate.md)
