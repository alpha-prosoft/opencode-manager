import logging
from datetime import UTC, datetime

import httpx
from pydantic import ValidationError

from .. import opencode
from ..config import Settings
from ..models import Identity, SessionRecord, Visibility
from ..sessions import CONVERSATION_LABEL, SessionService
from . import harness as harness_builder
from .catalog import get_activity
from .catalog import CiFollow
from .handlers import HANDLERS, SUBMISSIONS, RunContext
from .mcp_client import ToolFailed, call_tool
from .registry import PlatformRegistry
from .runs import RunStore
from .secrets import SecretStore
from .spec import AgentSpec, HarnessSpec, PullRequestTrigger, WorkflowSpec

log = logging.getLogger(__name__)

PLATFORM_ACTOR = "platform"


class RunError(Exception):
    pass


class ModelUnavailable(Exception):
    pass


NUDGE = "You have not submitted your result yet. Call the `{tool}` tool now with your complete result."
RETRY_LIMIT = 3


class RunService:
    def __init__(
        self,
        settings: Settings,
        registry: PlatformRegistry,
        sessions: SessionService,
        runs: RunStore,
        secrets: SecretStore,
        signing_key: str,
        credentials=None,
    ):
        self.settings = settings
        self.registry = registry
        self.sessions = sessions
        self.runs = runs
        self.secrets = secrets
        self.credentials = credentials
        self.signing_key = signing_key

    def _workflow(self, team_id: str, workflow_id: str) -> WorkflowSpec:
        workflow = self.registry.definition(team_id, "workflows", workflow_id)
        if workflow is None:
            raise RunError(f"Workflow {team_id}/{workflow_id} does not exist or is invalid")
        return workflow

    def create(self, team_id: str, workflow_id: str, trigger: dict, inputs: dict, created_by: str) -> dict:
        workflow = self._workflow(team_id, workflow_id)
        activity = get_activity(workflow.activity)
        try:
            validated = activity.inputs.model_validate(inputs)
        except ValidationError as error:
            raise RunError("; ".join(f"{'.'.join(map(str, e['loc']))}: {e['msg']}" for e in error.errors())) from error
        return self.runs.create(team_id, workflow_id, workflow.activity, trigger, validated.model_dump(mode="json"), created_by)

    def create_manual(self, identity: Identity, team_id: str, workflow_id: str, inputs: dict) -> dict:
        if not self.registry.role(identity.sub, team_id):
            raise PermissionError(f"{identity.sub} is not a member of team {team_id}")
        trigger = {"type": "manual", "by": identity.sub}
        workflow = self._workflow(team_id, workflow_id)
        self._check_session_limit(team_id)
        if isinstance(workflow.trigger, PullRequestTrigger):
            source = workflow.trigger
            trigger["scm"] = {"provider": source.provider, "repository": source.repository, "credentials": source.credentials}
        return self.create(team_id, workflow_id, trigger, inputs, identity.sub)

    def _context(self, run: dict, workspace) -> RunContext:
        team = self.registry.team(run["team_id"])
        workflow = self._workflow(run["team_id"], run["workflow_id"])
        activity = get_activity(workflow.activity)
        options = dict(workflow.options)
        if run["trigger"].get("scm"):
            options["scm"] = run["trigger"]["scm"]
        session_url = ""
        if run.get("session_id"):
            record = self.sessions.get(run["session_id"])
            session_url = self.sessions.entry_url(record) if record else ""
        return RunContext(
            run=run,
            team=team,
            workflow=workflow,
            activity=activity,
            options=activity.options.model_validate(options),
            inputs=activity.inputs.model_validate(run["inputs"]),
            workspace=workspace,
            secrets=self.credentials.for_user(self.person(run)),
            read_team_file=lambda relative: self.registry.read_team_file(run["team_id"], relative),
            session_url=session_url,
            secret_host_allowed=self.registry.secret_host_allowed,
            local_roots=self.settings.local_repo_roots,
            author=self._author(run),
        )

    def _author(self, run: dict) -> tuple[str, str]:
        person = self.person(run)
        if person is None:
            return ("Agent Platform", f"agent-platform@{self.settings.email_domain}")
        return (f"{person} (via Agent Platform)", f"{person}@{self.settings.email_domain}")

    @staticmethod
    def person(run: dict) -> str | None:
        return None if run["created_by"].startswith(PLATFORM_ACTOR) else run["created_by"]

    def _owner(self, run: dict) -> Identity:
        sub = run["created_by"] if not run["created_by"].startswith(PLATFORM_ACTOR) else PLATFORM_ACTOR
        return Identity(sub=sub, email=f"{sub}@{self.settings.email_domain}", name=sub)

    def prepare(self, run_id: str) -> dict:
        run = self.runs.get(run_id)
        workflow = self._workflow(run["team_id"], run["workflow_id"])
        agent: AgentSpec = self.registry.definition(run["team_id"], "agents", workflow.agent)
        harness: HarnessSpec = self.registry.definition(run["team_id"], "harnesses", workflow.harness)
        if agent is None or harness is None:
            raise RunError("The workflow's agent or harness is missing or invalid")
        activity = get_activity(workflow.activity)

        if run.get("session_id") and self.sessions.get(run["session_id"]):
            record = self.sessions.get(run["session_id"])
        else:
            self._check_session_limit(run["team_id"])
            title = run["inputs"].get("title") or workflow.name
            if run["inputs"].get("pull_request"):
                title = f"{workflow.name} · PR #{run['inputs']['pull_request']}"
            visibility = Visibility.TEAM if workflow.visibility == "team" else Visibility.PUBLIC
            if activity.interactive and run["created_by"] != PLATFORM_ACTOR:
                visibility = Visibility.PRIVATE if workflow.visibility == "team" else Visibility.PUBLIC
            record = self.sessions.create(
                self._owner(run),
                title,
                workflow.description,
                visibility,
                run["team_id"],
                run_id,
                workflow.activity,
                {"workflow": run["workflow_id"], "activity": workflow.activity},
            )
            self.runs.update(run_id, session_id=record.id, status="preparing")
            run = self.runs.get(run_id)

        ctx = self._context(run, self.sessions.storage.workspace(record.id))
        HANDLERS[workflow.activity].prepare(ctx)
        self.sessions.storage.hand_over(record.id)

        instructions = getattr(ctx.options, "instructions_file", None)
        self._write_sandbox(record, run["team_id"], workflow, agent, harness, activity.interactive, bool(activity.result_tool), run_id, instructions)
        self.runs.update(run_id, status="running")
        return {"session_id": record.id, "interactive": activity.interactive, "models": list(harness.llm.models)}

    def _write_sandbox(self, record, team_id, workflow, agent, harness, interactive, result_tool, run_id, instructions=None) -> None:
        prompt = agent.prompt or self.registry.read_team_file(team_id, agent.prompt_file)
        if instructions:
            prompt += "\n\n" + self.registry.read_team_file(team_id, instructions)
        key = lambda purpose: harness_builder.derive_key(self.signing_key, purpose, record.id)  # noqa: E731
        config = harness_builder.opencode_config(
            self.settings,
            record.id,
            workflow.agent,
            agent,
            prompt,
            harness,
            interactive,
            key("llm"),
            key("mcp") if result_tool else None,
            key("tools"),
        )
        sandbox = harness_builder.sandbox(
            self.settings, harness, config, record.secret_key, team_id, run_id, key("telemetry"), self.registry.read_skills(team_id, harness.skills)
        )
        self.sessions.storage.write_sandbox(record.id, sandbox)

    def fork(self, source: SessionRecord, identity: Identity) -> SessionRecord:
        """A private copy of a session (files and conversations) that continues as `identity`, with their credentials."""
        run = self.runs.get(source.run_id) if source.run_id else None
        workflow_id = (run or {}).get("workflow_id") or source.labels.get("workflow")
        if not source.team_id or not workflow_id:
            raise RunError("This session was not started from a workflow, so it can't be forked")
        if source.team_id not in self.registry.teams_of(identity.sub):
            raise PermissionError(f"Only members of team {source.team_id} can fork this session")
        workflow = self._workflow(source.team_id, workflow_id)
        agent: AgentSpec = self.registry.definition(source.team_id, "agents", workflow.agent)
        harness: HarnessSpec = self.registry.definition(source.team_id, "harnesses", workflow.harness)
        if agent is None or harness is None:
            raise RunError("The workflow's agent or harness is missing or invalid")
        self._check_session_limit(source.team_id)
        labels = {k: v for k, v in source.labels.items() if k in ("workflow", CONVERSATION_LABEL)}
        record = self.sessions.create(
            identity,
            f"{source.name} (fork)"[:100],
            source.description,
            Visibility.PRIVATE,
            source.team_id,
            None,
            "session",
            {**labels, "workflow": workflow_id, "forked_from": source.id},
        )
        self.sessions.store.update(record.id, {"forked_from": source.id})
        self.sessions.storage.copy_session(source.id, record.id)
        self._write_sandbox(record, source.team_id, workflow, agent, harness, True, False, None)
        return self.sessions.get(record.id)

    def start_conversation(self, run_id: str, model: str) -> str | None:
        run = self.runs.get(run_id)
        record = self.sessions.get(run["session_id"])
        ctx = self._context(run, self.sessions.storage.workspace(record.id))
        prompt = HANDLERS[run["activity"]].prompt(ctx)
        base_url = self.sessions.backend.service_url(record.id)
        if prompt is None:
            return None
        conversation = opencode.create_session(base_url, record.secret_key, ctx.workflow.name)
        self.sessions.label(record.id, CONVERSATION_LABEL, conversation)
        opencode.prompt_async(base_url, record.secret_key, conversation, f"{harness_builder.PROVIDER_ID}/{model}", prompt)
        if not ctx.activity.interactive:
            self.runs.update(run_id, status="awaiting_result")
        return conversation

    def _session(self, run_id: str):
        run = self.runs.get(run_id)
        record = self.sessions.get(run["session_id"])
        return run, record, self.sessions.backend.service_url(record.id)

    def conversation_finished(self, run_id: str, conversation: str) -> bool:
        _, record, base_url = self._session(run_id)
        status = opencode.session_statuses(base_url, record.secret_key).get(conversation)
        if status and status.get("type") == "retry":
            reason = (status.get("action") or {}).get("reason")
            if reason == "free_tier_limit" or status.get("attempt", 0) >= RETRY_LIMIT:
                raise ModelUnavailable(status.get("message") or "The model is not available")
        if status and status.get("type") != "idle":
            return False
        try:
            opencode.last_reply(base_url, record.secret_key, conversation)
        except RuntimeError as error:
            raise ModelUnavailable(str(error)) from error
        return True

    def nudge(self, run_id: str, conversation: str, model: str) -> None:
        run, record, base_url = self._session(run_id)
        tool = get_activity(run["activity"]).result_tool
        opencode.prompt_async(
            base_url, record.secret_key, conversation, f"{harness_builder.PROVIDER_ID}/{model}", NUDGE.format(tool=tool)
        )

    def abort(self, run_id: str, conversation: str) -> None:
        _, record, base_url = self._session(run_id)
        opencode.abort(base_url, record.secret_key, conversation)

    def validate_submission(self, run_id: str, tool: str, payload: dict) -> dict:
        run = self.runs.get(run_id)
        expected = get_activity(run["activity"]).result_tool
        if tool != expected:
            raise RunError(f"This run expects {expected}, not {tool}")
        return SUBMISSIONS[tool].model_validate(payload).model_dump(mode="json")

    def finalize(self, run_id: str, submission: dict) -> dict:
        run = self.runs.get(run_id)
        ctx = self._context(run, self.sessions.storage.workspace(run["session_id"]))
        result = HANDLERS[run["activity"]].finalize(ctx, submission, self.sessions.store)
        self.runs.update(run_id, status="finalizing", result=result, error=None)
        return result

    def complete(self, run_id: str) -> None:
        run = self.runs.get(run_id)
        if run["status"] == "cancelled":
            return
        result = run["result"] or {"session_id": run["session_id"]}
        self.runs.update(run_id, status="completed", result=result)
        self.notify(run_id)

    def fail(self, run_id: str, message: str) -> None:
        if self.runs.get(run_id)["status"] == "cancelled":
            return
        self.runs.update(run_id, status="failed", error=message[:4000])
        self.notify(run_id)

    def cancel(self, run_id: str, username: str) -> None:
        run = self.runs.get(run_id)
        self.runs.update(run_id, status="cancelled", error=f"Cancelled by {username}", cancelled_by=username)
        if run["session_id"] and self.sessions.get(run["session_id"]):
            record = self.sessions.get(run["session_id"])
            if record.activity != "session":
                self.sessions.stop(record.id)

    def retry(self, run_id: str, username: str) -> dict:
        run = self.runs.get(run_id)
        trigger = {**run["trigger"], "retry_of": run_id, "by": username}
        return self.create(run["team_id"], run["workflow_id"], trigger, run["inputs"], username)

    # ---- following CI ----------------------------------------------------------------

    def _ci(self, run: dict):
        workflow = self._workflow(run["team_id"], run["workflow_id"])
        ci = (workflow.options or {}).get("ci")
        pull_request = (run.get("result") or {}).get("pull_request")
        return (CiFollow.model_validate(ci), pull_request) if ci and pull_request else (None, None)

    def _ci_update(self, run: dict, **changes) -> dict:
        result = dict(run["result"])
        result["ci"] = {**(result.get("ci") or {}), **changes, "checked_at": datetime.now(UTC).isoformat()}
        self.runs.update(run["id"], result=result)
        return result["ci"]

    def _ci_tool(self, run: dict, ci: CiFollow, tool: str, arguments: dict):
        server = self.registry.platform().mcp_server(ci.mcp_server)
        if server is None:
            raise ToolFailed(f"MCP server {ci.mcp_server} is not configured")
        resolved = self.credentials.resolve(server.credential, self.person(run)) if server.credential else None
        if server.credential and resolved is None:
            raise ToolFailed(f"credential {server.credential} is not set up")
        return call_tool(server, resolved.value if resolved else None, tool, arguments)

    def ci_start(self, run_id: str) -> int:
        """Minutes to follow the build for, or 0 when there is nothing to follow."""
        run = self.runs.get(run_id)
        ci, pull_request = self._ci(run)
        if ci is None:
            return 0
        self._ci_update(run, job=ci.job.replace("{pr}", str(pull_request["id"])), state="waiting", build=None)
        self.runs.update(run_id, status="following_ci")
        return ci.timeout_minutes

    def ci_poll(self, run_id: str) -> str:
        run = self.runs.get(run_id)
        ci, pull_request = self._ci(run)
        job = run["result"]["ci"]["job"]
        try:
            build = self._ci_tool(run, ci, "get_build", {"job": job, "number": "lastBuild"})
        except ToolFailed as error:
            if "not found" in str(error).lower():
                self._ci_update(run, state="waiting", detail="The build job doesn't exist yet")
                return "waiting"
            self._ci_update(run, state="waiting", detail=str(error)[:300])
            return "waiting"
        pushed = datetime.fromisoformat(pull_request["pushed_at"]).timestamp() * 1000
        if not isinstance(build, dict) or (build.get("started") or 0) < pushed - 60_000:
            self._ci_update(run, state="waiting", detail="No build of the pushed change yet")
            return "waiting"
        outcome = build.get("result")
        state = "running" if build.get("building") else "passed" if outcome == "SUCCESS" else "failed"
        self._ci_update(run, state=state, build=build, detail=None)
        return state

    def ci_timeout(self, run_id: str) -> None:
        run = self.runs.get(run_id)
        self._ci_update(run, state="timeout", detail="Stopped waiting for the build")

    def ci_failure(self, run_id: str) -> str | None:
        """Start the workflow that analyses a failed build; returns its run id."""
        run = self.runs.get(run_id)
        ci, pull_request = self._ci(run)
        if not ci or not ci.on_failure:
            return None
        build = run["result"]["ci"]["build"]
        job = run["result"]["ci"]["job"]
        try:
            log = self._ci_tool(run, ci, "get_build_log", {"job": job, "number": str(build["number"]), "tail_lines": 150})
        except ToolFailed as error:
            log = f"(log unavailable: {error})"
        failed = [s["name"] for s in build.get("stages") or [] if s.get("status") not in ("SUCCESS", "NOT_EXECUTED", "SKIPPED")]
        context = "\n".join(
            [
                f"The build of pull request #{pull_request['id']} ({pull_request['url']}) failed.",
                f"Jenkins job `{job}`, build #{build['number']}: {build.get('result')} — {build.get('url')}",
                f"Failed stage(s): {', '.join(failed) or 'unknown'}.",
                f"The change is on branch `{pull_request['branch']}` (commit {pull_request['commit'][:12]}).",
                "",
                "Find the cause of the failure and propose a fix. Use the Jenkins tools for more of the log or other builds.",
                "",
                "Last lines of the console log:",
                "```",
                str(log)[-8000:],
                "```",
            ]
        )
        follow_up = self._workflow(run["team_id"], ci.on_failure)
        title = f"Build failure: PR #{pull_request['id']}, build #{build['number']}"
        inputs = {"brief": context} if follow_up.activity == "analysis" else {"title": title, "prompt": context, "branch": pull_request["branch"]}
        trigger = {"type": "ci_failure", "run": run_id, "build": build["number"], "by": run["created_by"]}
        created = self.create(run["team_id"], ci.on_failure, trigger, inputs, run["created_by"])
        self._ci_update(run, follow_up_run=created["id"])
        return created["id"]

    def _check_session_limit(self, team_id: str) -> None:
        team = self.registry.team(team_id)
        limit = team.budget.max_running_sessions if team else None
        if not limit:
            return
        running = set(self.sessions.running_session_ids())
        count = sum(1 for record in self.sessions.store.list() if record.team_id == team_id and record.id in running)
        if count >= limit:
            raise RunError(f"Team {team_id} already has {count} running sessions (limit {limit}). Stop one and retry.")

    def notify(self, run_id: str) -> None:
        run = self.runs.get(run_id)
        team = self.registry.team(run["team_id"])
        if not team or not team.notifications.webhook or run["status"] not in team.notifications.on:
            return
        url = self.secrets.get(team.notifications.webhook)
        if not url:
            return
        link = self.settings.public_url(self.settings.domain) + f"/runs/{run_id}"
        verdict = ((run.get("result") or {}).get("review") or {}).get("verdict")
        text = f"{run['team_id']}/{run['workflow_id']} {run['status']}" + (f" ({verdict})" if verdict else "") + f": {link}"
        payload = {"text": text, "run": {k: run[k] for k in ("id", "team_id", "workflow_id", "activity", "status", "error")}}
        try:
            httpx.post(url, json=payload, timeout=10)
        except httpx.HTTPError as error:
            log.warning("Webhook for run %s failed: %s", run_id, error)
