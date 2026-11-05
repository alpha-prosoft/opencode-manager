import hashlib
import hmac
import json
from dataclasses import dataclass, field

from ..config import Settings
from .spec import AgentSpec, HarnessSpec

PROVIDER_ID = "platform"
CONFIG_MOUNT = "/etc/opencode"
OPENCODE_GITIGNORE = "node_modules\npackage.json\npackage-lock.json\nbun.lock\n.gitignore\n"
LOCKDOWN_ENV = {
    "XDG_CONFIG_HOME": CONFIG_MOUNT,
    "OPENCODE_DISABLE_PROJECT_CONFIG": "1",
    "OPENCODE_PURE": "1",
    "OPENCODE_DISABLE_MODELS_FETCH": "1",
    "OPENCODE_DISABLE_AUTOUPDATE": "1",
    "OPENCODE_DISABLE_CLAUDE_CODE": "1",
    "OPENCODE_DISABLE_EXTERNAL_SKILLS": "1",
    "OPENCODE_DISABLE_LSP_DOWNLOAD": "1",
}
BASE_PERMISSIONS = {"*": "allow", "external_directory": "allow", "webfetch": "deny", "websearch": "deny"}


def derive_key(signing_key: str, purpose: str, session_id: str) -> str:
    digest = hmac.new(signing_key.encode(), f"{purpose}:{session_id}".encode(), hashlib.sha256).hexdigest()
    return f"{session_id}.{digest}"


def verify_key(signing_key: str, purpose: str, key: str) -> str | None:
    session_id = key.split(".", 1)[0]
    return session_id if hmac.compare_digest(derive_key(signing_key, purpose, session_id), key) else None


@dataclass
class Sandbox:
    image: str
    network: str
    cpus: float
    memory_mb: int
    pids: int
    llm_connection: str
    llm_models: list[str]
    team_id: str
    run_id: str | None = None
    idle_minutes: int = 15
    env: dict[str, str] = field(default_factory=dict)
    files: dict[str, str] = field(default_factory=dict)


def opencode_config(
    settings: Settings,
    session_id: str,
    agent_id: str,
    agent: AgentSpec,
    agent_prompt: str,
    harness: HarnessSpec,
    interactive: bool,
    llm_key: str,
    mcp_key: str | None,
) -> dict:
    models = harness.llm.models
    model = f"{PROVIDER_ID}/{models[0]}"
    permissions = {**BASE_PERMISSIONS, **harness.permissions}
    if harness.network == "internet":
        permissions = {"webfetch": "allow", "websearch": "allow", **permissions}
    if not interactive:
        permissions["question"] = "deny"
    agent_config = {"description": agent.description, "prompt": agent_prompt, "model": model, "mode": "primary"}
    if agent.temperature is not None:
        agent_config["temperature"] = agent.temperature
    if agent.steps is not None:
        agent_config["steps"] = agent.steps
    config = {
        "$schema": "https://opencode.ai/config.json",
        "model": model,
        "small_model": model,
        "default_agent": agent_id,
        "enabled_providers": [PROVIDER_ID],
        "share": "disabled",
        "autoupdate": False,
        "plugin": [],
        "permission": permissions,
        "agent": {agent_id: agent_config},
        "provider": {
            PROVIDER_ID: {
                "npm": "@ai-sdk/openai-compatible",
                "name": "Platform LLM gateway",
                "options": {"baseURL": f"{settings.manager_service_url}/llm/v1", "apiKey": llm_key},
                "models": {m: {"name": m, "tool_call": True} for m in models},
            }
        },
        "mcp": {},
    }
    if mcp_key:
        config["mcp"]["platform"] = {
            "type": "remote",
            "url": f"{settings.manager_service_url}/mcp/",
            "headers": {"X-Platform-Session": session_id, "X-Platform-Key": mcp_key},
            "oauth": False,
            "enabled": True,
        }
    return config


def sandbox(
    settings: Settings, harness: HarnessSpec, config: dict, password: str, team_id: str, run_id: str | None
) -> Sandbox:
    return Sandbox(
        image=harness.image or settings.session_image,
        network=harness.network,
        cpus=harness.resources.cpus,
        memory_mb=harness.resources.memory_mb,
        pids=harness.resources.pids,
        llm_connection=harness.llm.connection,
        llm_models=list(harness.llm.models),
        team_id=team_id,
        run_id=run_id,
        idle_minutes=harness.idle_minutes,
        env={**LOCKDOWN_ENV, "OPENCODE_SERVER_PASSWORD": password},
        files={"opencode/opencode.json": json.dumps(config, indent=2), "opencode/.gitignore": OPENCODE_GITIGNORE},
    )
