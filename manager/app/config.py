from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="MANAGER_", env_file=".env", extra="ignore")

    backend: Literal["docker", "kubernetes"] = "docker"
    router: Literal["traefik-file", "gateway-api"] = "traefik-file"
    database_path: str = "/data/manager.db"
    ui_dist_dir: str = "/app/ui"

    domain: str = "localhost"
    public_scheme: str = "http"
    public_port: int | None = None

    traefik_dynamic_file: str = "/traefik/dynamic/sessions.yml"
    traefik_entrypoint: str = "web"
    manager_service_url: str = "http://manager:8000"

    gateway_name: str = "traefik-gateway"
    gateway_namespace: str | None = None
    gateway_section_name: str | None = None
    forward_auth_middleware: str = "opencode-forward-auth"

    auth_mode: Literal["login", "jwt"] = "login"
    auth_ttl_hours: int = 12
    secret_key: str | None = None
    jwt_secret: str | None = None
    jwt_jwks_url: str | None = None
    jwt_audience: str | None = None
    jwt_algorithms: list[str] = ["RS256", "HS256"]
    jwt_cookie: str = "access_token"
    email_domain: str = "users.local"

    session_image: str = "ghcr.io/anomalyco/opencode:1.19.5"
    session_port: int = 4096
    session_env: dict[str, str] = {}
    session_opencode_config: dict = {"permission": {"external_directory": "allow"}}

    shared_mount_path: str = "/shared"

    temporal_address: str = "temporal:7233"
    temporal_namespace: str = "default"
    temporal_task_queue: str = "opencode-manager"
    reap_interval_seconds: int = 300
    idle_grace_seconds: int = 300

    bitbucket_token: str | None = None
    bitbucket_workspace: str | None = None
    bitbucket_repos: list[str] = []
    bitbucket_poll_minutes: int = 15
    bitbucket_session_owner: str = "bitbucket"
    bitbucket_backfill: bool = False

    review_enabled: bool = True
    review_model_auth_file: str = "/config/opencode/auth.json"
    review_models: list[str] = [
        "github-copilot/claude-sonnet-5.5",
        "opencode/deepseek-v4.1-flash-free",
        "opencode/glm-5.3-flash-free",
        "opencode/mimo-v2.6-flash-free",
        "opencode/nemotron-3.5-lightning-free",
    ]
    review_guidelines_dir: str = "/config/review"
    review_post_mode: Literal["off", "dry-run", "auto"] = "dry-run"
    review_max_comments: int = 30
    review_opencode_config: dict = {
        "permission": {
            "read": "allow",
            "edit": "allow",
            "bash": "allow",
            "external_directory": "allow",
            "webfetch": "deny",
            "doom_loop": "allow",
            "question": "deny",
        }
    }

    docker_network: str = "opencode-net"
    docker_shared_volume: str = "opencode-shared"

    k8s_namespace: str = "default"
    k8s_shared_pvc: str = "opencode-shared"

    @property
    def bitbucket_enabled(self) -> bool:
        return bool(self.bitbucket_token and self.bitbucket_workspace and self.bitbucket_repos)

    def session_host(self, session_id: str) -> str:
        return f"{session_id}.{self.domain}"

    def public_url(self, host: str) -> str:
        port = f":{self.public_port}" if self.public_port else ""
        return f"{self.public_scheme}://{host}{port}"


@lru_cache
def get_settings() -> Settings:
    return Settings()
