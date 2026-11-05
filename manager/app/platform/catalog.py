from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from .spec import GitSource


class Options(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ScmSource(Options):
    provider: Literal["bitbucket", "github"]
    repository: str = Field(pattern=r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    credentials: str


class SessionOptions(Options):
    repository: GitSource | None = Field(default=None, description="Repository checked out into the workspace")
    instructions_file: str | None = Field(default=None, description="Team repo file appended to the agent prompt")


class SessionInputs(Options):
    title: str = Field(default="", max_length=100, description="Name shown in the session list")
    branch: str | None = Field(default=None, description="Override the repository branch")
    prompt: str | None = Field(default=None, max_length=20000, description="First message sent to the agent")


class ReviewOptions(Options):
    scm: ScmSource | None = Field(default=None, description="Where pull requests live (set by pull_request triggers)")
    guidelines_file: str | None = Field(default=None, description="Team repo file with the review baseline")
    post_mode: Literal["off", "dry-run", "auto"] = Field(default="dry-run", description="What to do with the review")
    max_comments: int = Field(default=30, ge=1, le=100, description="Upper bound on comments posted per PR")


class ReviewInputs(Options):
    pull_request: int = Field(ge=1, description="Pull request number")


class ImplementOptions(Options):
    repository: GitSource = Field(description="Repository the change is made in")
    guidelines_file: str | None = Field(default=None, description="Team repo file with coding guidelines")


class ImplementInputs(Options):
    task: str = Field(min_length=10, max_length=20000, description="What to implement")
    branch: str | None = Field(default=None, description="Base branch (defaults to the repository branch)")


class AnalysisOptions(Options):
    repository: GitSource | None = Field(default=None, description="Optional repository for context")
    template_file: str | None = Field(default=None, description="Team repo file with the document template")


class AnalysisInputs(Options):
    brief: str = Field(min_length=10, max_length=20000, description="What to analyse")


class Activity(BaseModel):
    id: str
    name: str
    category: str
    description: str
    icon: str
    interactive: bool
    result_tool: str | None
    options: type[Options]
    inputs: type[Options]

    def schema(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "category": self.category,
            "description": self.description,
            "icon": self.icon,
            "interactive": self.interactive,
            "result_tool": self.result_tool,
            "options_schema": self.options.model_json_schema(),
            "inputs_schema": self.inputs.model_json_schema(),
        }


CATALOG: dict[str, Activity] = {
    activity.id: activity
    for activity in [
        Activity(
            id="session",
            name="Interactive session",
            category="Workspace",
            description="A personal OpenCode workspace in the browser, optionally with a repository checked out.",
            icon="terminal",
            interactive=True,
            result_tool=None,
            options=SessionOptions,
            inputs=SessionInputs,
        ),
        Activity(
            id="review",
            name="Code review",
            category="Quality",
            description=(
                "Reviews a pull request against the team's baseline. The agent submits a structured review; "
                "the platform plans and (optionally) posts comments with a hard limit."
            ),
            icon="rate_review",
            interactive=False,
            result_tool="submit_review",
            options=ReviewOptions,
            inputs=ReviewInputs,
        ),
        Activity(
            id="implement",
            name="Implement change",
            category="Engineering",
            description="Implements a task in a repository and returns a reviewed patch. Nothing is pushed.",
            icon="build",
            interactive=False,
            result_tool="submit_implementation",
            options=ImplementOptions,
            inputs=ImplementInputs,
        ),
        Activity(
            id="analysis",
            name="Business analysis",
            category="Discovery",
            description="Turns a brief into a structured analysis document (requirements, risks, open questions).",
            icon="description",
            interactive=False,
            result_tool="submit_document",
            options=AnalysisOptions,
            inputs=AnalysisInputs,
        ),
    ]
}


def get_activity(activity_id: str) -> Activity:
    try:
        return CATALOG[activity_id]
    except KeyError:
        raise LookupError(f"Unknown marketplace activity {activity_id!r}") from None
