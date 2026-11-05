import logging

from .runner import PLATFORM_ACTOR, RunService
from .scm import provider
from .spec import PullRequestTrigger, ScheduleTrigger

log = logging.getLogger(__name__)

BASELINE = "__baseline__"


def trigger_key(team_id: str, workflow_id: str) -> str:
    return f"{team_id}/{workflow_id}"


class TriggerService:
    def __init__(self, runner: RunService):
        self.runner = runner
        self.registry = runner.registry
        self.runs = runner.runs

    def desired_schedules(self) -> dict[str, dict]:
        schedules = {}
        for team in self.registry.teams():
            for definition in self.registry.workflows(team["id"]):
                if not definition.valid:
                    continue
                trigger = definition.spec["trigger"]
                schedule_id = f"trigger-{team['id']}-{definition.id}"
                if trigger["type"] == "pull_request":
                    schedules[schedule_id] = {"kind": "pull_request", "team": team["id"], "workflow": definition.id, "minutes": trigger["poll_minutes"]}
                elif trigger["type"] == "schedule":
                    schedules[schedule_id] = {"kind": "schedule", "team": team["id"], "workflow": definition.id, "minutes": trigger["every_minutes"]}
        return schedules

    def new_pull_requests(self, team_id: str, workflow_id: str) -> list[str]:
        workflow = self.runner._workflow(team_id, workflow_id)
        trigger = workflow.trigger
        if not isinstance(trigger, PullRequestTrigger):
            return []
        scm = provider(trigger.provider, self.runner.secrets.require(trigger.credentials))
        open_prs = [
            pr for pr in scm.open_pull_requests(trigger.repository)
            if not trigger.target_branches or pr.destination_branch in trigger.target_branches
        ]
        key = trigger_key(team_id, workflow_id)
        seen = self.runs.seen(key)
        if BASELINE not in seen:
            self.runs.mark_seen(key, BASELINE, None)
            if not trigger.backfill:
                for pr in open_prs:
                    self.runs.mark_seen(key, str(pr.id), None)
                log.info("Trigger %s: %d open pull requests recorded as baseline", key, len(open_prs))
                return []
        run_ids = []
        for pr in open_prs:
            if str(pr.id) in seen:
                continue
            run = self.runner.create(
                team_id,
                workflow_id,
                {
                    "type": "pull_request",
                    "scm": {"provider": trigger.provider, "repository": trigger.repository, "credentials": trigger.credentials},
                    "pull_request": pr.dict(),
                },
                {"pull_request": pr.id},
                f"{PLATFORM_ACTOR}:pull_request",
            )
            self.runs.mark_seen(key, str(pr.id), run["id"])
            run_ids.append(run["id"])
        return run_ids

    def scheduled_run(self, team_id: str, workflow_id: str) -> str:
        workflow = self.runner._workflow(team_id, workflow_id)
        inputs = workflow.trigger.inputs if isinstance(workflow.trigger, ScheduleTrigger) else {}
        run = self.runner.create(team_id, workflow_id, {"type": "schedule"}, inputs, f"{PLATFORM_ACTOR}:schedule")
        return run["id"]
