"""Schemas for the onboarding repository and team repositories."""

import re
from typing import Annotated, Any, Literal

from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator, model_validator

Identifier = Annotated[str, StringConstraints(pattern=r"^[a-z0-9][a-z0-9-]{0,62}$")]
SecretName = Annotated[str, StringConstraints(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")]
Username = Annotated[str, StringConstraints(pattern=r"^[a-z0-9][a-z0-9._-]{0,63}$")]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


SCP_LIKE = re.compile(r"^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:[^/].*$")


class GitSource(Strict):
    type: Literal["git"] = "git"
    url: str = Field(description="https://, ssh (git@host:org/repo.git) or file:// URL of the repository")
    branch: str = "main"
    credentials: SecretName | None = Field(
        default=None, description="Platform secret: a token for https URLs, a private key for ssh URLs"
    )
    username: str = Field(default="x-token-auth", description="Username sent with the token (Basic auth)")
    path: str = Field(default=".platform", description="Directory inside the repository with the definitions")

    @field_validator("url")
    @classmethod
    def _scheme(cls, url: str) -> str:
        if not (url.startswith(("https://", "file://", "ssh://")) or SCP_LIKE.match(url)):
            raise ValueError("use an https://, ssh (git@host:org/repo.git) or file:// repository URL")
        return url


class ArchiveSource(Strict):
    type: Literal["url"]
    url: str = Field(pattern=r"^https://", description="https:// URL of a .tar.gz or .zip archive")
    credentials: SecretName | None = Field(default=None, description="Platform secret sent as a Bearer token")
    path: str = Field(default=".platform", description="Directory inside the archive with the definitions")


class PathSource(Strict):
    type: Literal["path"]
    location: str = Field(description="Absolute directory on the platform host (must be under an allowed root)")
    path: str = Field(default=".platform", description="Directory inside location with the definitions")


TeamSource = Annotated[GitSource | ArchiveSource | PathSource, Field(discriminator="type")]


class ModelPrice(Strict):
    input: float = Field(ge=0, description="Price per million prompt tokens")
    output: float = Field(ge=0, description="Price per million completion tokens")
    cached_input: float | None = Field(default=None, ge=0, description="Price per million cached prompt tokens")


class LlmConnection(Strict):
    id: Identifier
    type: Literal["openai-compatible", "github-copilot"] = "openai-compatible"
    base_url: str | None = Field(default=None, description="Upstream base URL (defaults per type)")
    secret: SecretName = Field(description="Platform secret with the API key or OAuth token")
    models: list[str] = Field(min_length=1, description="Models teams may use through this connection")
    teams: list[Identifier] | Literal["*"] = "*"
    pricing: dict[str, ModelPrice] = Field(default={}, description="Optional prices per model, for cost estimates")

    def upstream(self) -> str:
        if self.base_url:
            return self.base_url.rstrip("/")
        if self.type == "github-copilot":
            return "https://api.githubcopilot.com"
        raise ValueError(f"LLM connection {self.id} needs base_url")


class CredentialTest(Strict):
    url: str | None = Field(default=None, pattern=r"^https?://", description="Endpoint called with the credential; a 2xx answer means it works")
    mcp_tool: str | None = Field(
        default=None, description="Instead of a URL: a tool (without arguments) of the MCP server using this credential, called through it"
    )

    @model_validator(mode="after")
    def _one(self) -> "CredentialTest":
        if bool(self.url) == bool(self.mcp_tool):
            raise ValueError("set exactly one of url or mcp_tool")
        return self


class CredentialSpec(Strict):
    id: SecretName = Field(description="Referenced by MCP servers, repositories and triggers")
    name: str = Field(min_length=1, max_length=80)
    description: str = ""
    kind: Literal["token", "basic", "ssh_key"] = Field(
        default="token", description="token: one secret; basic: username and password; ssh_key: a private key"
    )
    hosts: list[str] = Field(default=[], description="The only hosts this credential may be sent to")
    provided_by: Literal["platform", "platform_or_user", "user"] = Field(
        default="platform",
        description="platform: admins set one value; platform_or_user: users may use their own instead; user: every user sets their own",
    )
    teams: list[Identifier] | Literal["*"] = "*"
    help: str = Field(default="", description="Shown to people when they enter the value, e.g. where to create a token")
    test: CredentialTest | None = None

    @property
    def personal(self) -> bool:
        return self.provided_by != "platform"


class McpAuth(Strict):
    header: str = Field(default="Authorization", pattern=r"^[A-Za-z0-9-]{1,64}$")
    scheme: Literal["bearer", "basic", "raw"] = "bearer"


class McpServerSpec(Strict):
    id: Identifier
    name: str = Field(min_length=1, max_length=80)
    description: str = ""
    url: str = Field(pattern=r"^https?://", description="Streamable HTTP endpoint of the MCP server, reached from the platform only")
    credential: SecretName | None = Field(default=None, description="Credential the platform adds to every request")
    auth: McpAuth = McpAuth()
    tools: list[str] | None = Field(default=None, description="Allow-list of tool names; all tools when omitted")
    teams: list[Identifier] | Literal["*"] = "*"


class TeamBudget(Strict):
    daily_tokens: int | None = Field(default=None, ge=1000, description="Prompt + completion tokens per UTC day")
    max_running_sessions: int | None = Field(default=None, ge=1, le=1000, description="Concurrent running sessions")


class TeamNotifications(Strict):
    webhook: SecretName | None = Field(
        default=None, description="Platform secret holding a webhook URL (e.g. Slack) called when runs finish"
    )
    on: list[Literal["completed", "failed"]] = ["completed", "failed"]


class TeamEntry(Strict):
    id: Identifier
    name: str
    description: str = ""
    repository: TeamSource
    members: list[Username] = []
    admins: list[Username] = []
    secrets: list[SecretName] = Field(default=[], description="Platform secrets this team's workflows may use")
    budget: TeamBudget = TeamBudget()
    notifications: TeamNotifications = TeamNotifications()

    @model_validator(mode="before")
    @classmethod
    def _default_source_type(cls, data):
        if isinstance(data, dict) and isinstance(data.get("repository"), dict) and "type" not in data["repository"]:
            data = {**data, "repository": {**data["repository"], "type": "git"}}
        return data


class PlatformSpec(Strict):
    """platform.json in the onboarding repository."""

    version: Literal[1] = 1
    admins: list[Username] = []
    allowed_images: list[str] = ["ghcr.io/anomalyco/opencode:1.19.5"]
    llm_connections: list[LlmConnection] = []
    secret_hosts: dict[SecretName, list[str]] = Field(
        default={}, description="Hosts each secret may be sent to when used for Git checkouts"
    )
    credentials: list[CredentialSpec] = Field(default=[], description="Credentials people and admins fill in")
    mcp_servers: list[McpServerSpec] = Field(default=[], description="MCP servers run behind the platform gateway")
    teams: list[TeamEntry] = []

    @model_validator(mode="after")
    def _unique(self) -> "PlatformSpec":
        for label, ids in (
            ("team", [t.id for t in self.teams]),
            ("llm connection", [c.id for c in self.llm_connections]),
            ("credential", [c.id for c in self.credentials]),
            ("MCP server", [m.id for m in self.mcp_servers]),
        ):
            duplicates = {i for i in ids if ids.count(i) > 1}
            if duplicates:
                raise ValueError(f"duplicate {label} ids: {sorted(duplicates)}")
        for server in self.mcp_servers:
            if server.credential is None:
                continue
            credential = self.credential(server.credential)
            if credential is None:
                raise ValueError(f"MCP server {server.id} uses undeclared credential {server.credential}")
            host = urlsplit(server.url).hostname
            if host not in credential.hosts:
                raise ValueError(f"MCP server {server.id}: credential {credential.id} may not be sent to {host} (add it to its hosts)")
        llm_secrets = {c.secret for c in self.llm_connections}
        for credential in self.credentials:
            if credential.personal and credential.id in llm_secrets:
                raise ValueError(f"credential {credential.id} is used by an LLM connection and must be provided_by platform")
        return self

    def credential(self, credential_id: str) -> CredentialSpec | None:
        return next((c for c in self.credentials if c.id == credential_id), None)

    def credential_hosts(self, credential_id: str) -> list[str]:
        declared = self.credential(credential_id)
        return list(dict.fromkeys([*(declared.hosts if declared else []), *self.secret_hosts.get(credential_id, [])]))

    def mcp_server(self, server_id: str) -> McpServerSpec | None:
        return next((m for m in self.mcp_servers if m.id == server_id), None)


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


class HarnessPolicy(Strict):
    deny_commands: list[str] = Field(default=[], description="Regular expressions; matching bash commands are blocked")
    deny_paths: list[str] = Field(default=[], description="Regular expressions; matching file paths are blocked for file tools")

    @field_validator("deny_commands", "deny_paths")
    @classmethod
    def _regex(cls, patterns: list[str]) -> list[str]:
        for pattern in patterns:
            try:
                re.compile(pattern)
            except re.error as error:
                raise ValueError(f"invalid regular expression {pattern!r}: {error}") from None
        return patterns


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
    policy: HarnessPolicy = Field(
        default=HarnessPolicy(), description="Guardrails enforced by the platform plugin before a tool runs"
    )
    mcp: list[Identifier] = Field(default=[], description="MCP servers from the onboarding repository this harness may use")
    skills: list[str] = Field(
        default=[], description="Skill directories (each with a SKILL.md) in the team definitions, copied read-only into the sandbox"
    )

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
    """.platform/workflows/<id>.json — a trigger wired to an activity."""

    name: str
    description: str = ""
    activity: Identifier = Field(description="Activity id (see the Activities page)")
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
