"""Schemas for the onboarding repository and team repositories."""

import re
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator, model_validator

Identifier = Annotated[str, StringConstraints(pattern=r"^[a-z0-9][a-z0-9-]{0,62}$")]
SecretName = Annotated[str, StringConstraints(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")]
Username = Annotated[str, StringConstraints(pattern=r"^[a-z0-9][a-z0-9._-]{0,63}$")]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class GitSource(Strict):
    url: str = Field(description="https:// or file:// URL of the repository")
    branch: str = "main"
    credentials: SecretName | None = Field(default=None, description="Platform secret holding a token for https URLs")
    username: str = Field(default="x-token-auth", description="Username sent with the token (Basic auth)")
    path: str = Field(default=".platform", description="Directory inside the repository with the definitions")

    @field_validator("url")
    @classmethod
    def _scheme(cls, url: str) -> str:
        if not url.startswith(("https://", "file://")):
            raise ValueError("only https:// and file:// repositories are supported")
        return url


class LlmConnection(Strict):
    id: Identifier
    type: Literal["openai-compatible", "github-copilot"] = "openai-compatible"
    base_url: str | None = Field(default=None, description="Upstream base URL (defaults per type)")
    secret: SecretName = Field(description="Platform secret with the API key or OAuth token")
    models: list[str] = Field(min_length=1, description="Models teams may use through this connection")
    teams: list[Identifier] | Literal["*"] = "*"

    def upstream(self) -> str:
        if self.base_url:
            return self.base_url.rstrip("/")
        if self.type == "github-copilot":
            return "https://api.githubcopilot.com"
        raise ValueError(f"LLM connection {self.id} needs base_url")


class TeamEntry(Strict):
    id: Identifier
    name: str
    description: str = ""
    repository: GitSource
    members: list[Username] = []
    admins: list[Username] = []
    secrets: list[SecretName] = Field(default=[], description="Platform secrets this team's workflows may use")


class PlatformSpec(Strict):
    """platform.json in the onboarding repository."""

    version: Literal[1] = 1
    admins: list[Username] = []
    allowed_images: list[str] = ["ghcr.io/anomalyco/opencode:1.19.5"]
    llm_connections: list[LlmConnection] = []
    teams: list[TeamEntry] = []

    @model_validator(mode="after")
    def _unique(self) -> "PlatformSpec":
        for label, ids in (("team", [t.id for t in self.teams]), ("llm connection", [c.id for c in self.llm_connections])):
            duplicates = {i for i in ids if ids.count(i) > 1}
            if duplicates:
                raise ValueError(f"duplicate {label} ids: {sorted(duplicates)}")
        return self


class AgentSpec(Strict):
    """.platform/agents/<id>.json"""

    description: str = ""
    prompt: str | None = None
    prompt_file: str | None = Field(default=None, description="Path relative to the definitions directory")
    temperature: float | None = Field(default=None, ge=0, le=2)
    steps: int | None = Field(default=None, ge=1, le=500, description="Maximum agentic iterations")

    @model_validator(mode="after")
    def _prompt(self) -> "AgentSpec":
        if bool(self.prompt) == bool(self.prompt_file):
            raise ValueError("set exactly one of prompt or prompt_file")
        return self


Permission = Literal["allow", "ask", "deny"]


class HarnessLlm(Strict):
    connection: Identifier
    models: list[str] = Field(min_length=1, description="Tried in order")


class HarnessResources(Strict):
    cpus: float = Field(default=1.0, gt=0, le=8)
    memory_mb: int = Field(default=2048, ge=256, le=32768)
    pids: int = Field(default=512, ge=64, le=4096)


class HarnessSpec(Strict):
    """.platform/harnesses/<id>.json — the sandbox an agent runs in."""

    description: str = ""
    image: str | None = Field(default=None, description="Must be in the platform's allowed_images")
    llm: HarnessLlm
    network: Literal["none", "internet"] = Field(
        default="none", description="none: only the platform LLM gateway is reachable"
    )
    permissions: dict[str, Permission] = Field(
        default={}, description="OpenCode tool permissions, e.g. {\"bash\": \"allow\", \"webfetch\": \"deny\"}"
    )
    resources: HarnessResources = HarnessResources()
    idle_minutes: int = Field(default=15, ge=1, le=1440)

    @field_validator("permissions")
    @classmethod
    def _tools(cls, permissions: dict[str, str]) -> dict[str, str]:
        for tool in permissions:
            if not re.match(r"^[a-z_*]{1,40}$", tool):
                raise ValueError(f"invalid tool name {tool!r}")
        return permissions


class ManualTrigger(Strict):
    type: Literal["manual"] = "manual"


class ScheduleTrigger(Strict):
    type: Literal["schedule"]
    every_minutes: int = Field(ge=5, le=60 * 24 * 7)
    inputs: dict[str, Any] = {}


class PullRequestTrigger(Strict):
    type: Literal["pull_request"]
    provider: Literal["bitbucket", "github"]
    repository: str = Field(pattern=r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", description="workspace/repo or owner/repo")
    credentials: SecretName
    target_branches: list[str] = Field(default=[], description="Only PRs into these branches (empty = all)")
    poll_minutes: int = Field(default=15, ge=1, le=1440)
    backfill: bool = Field(default=False, description="Also run for PRs already open when the trigger is first seen")


Trigger = Annotated[ManualTrigger | ScheduleTrigger | PullRequestTrigger, Field(discriminator="type")]


class WorkflowSpec(Strict):
    """.platform/workflows/<id>.json — a trigger wired to a marketplace activity."""

    name: str
    description: str = ""
    activity: Identifier = Field(description="Marketplace activity id")
    agent: Identifier
    harness: Identifier
    trigger: Trigger = ManualTrigger()
    options: dict[str, Any] = {}
    visibility: Literal["team", "public"] = "team"


DEFINITION_KINDS = {"agents": AgentSpec, "harnesses": HarnessSpec, "workflows": WorkflowSpec}


def json_schemas() -> dict[str, dict]:
    return {
        "platform": PlatformSpec.model_json_schema(),
        **{kind: model.model_json_schema() for kind, model in DEFINITION_KINDS.items()},
    }
