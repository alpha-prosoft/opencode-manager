from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="MANAGER_", env_file=".env", env_ignore_empty=True, extra="ignore")

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

    onboarding_url: str | None = None
    onboarding_branch: str = "main"
    onboarding_credentials: str | None = None
    onboarding_username: str = "x-token-auth"
    onboarding_file: str = "platform.json"
    sync_interval_minutes: int = 10
    secrets_dir: str = "/config/secrets"
    repos_dir: str = "/data/repos"
    local_repo_roots: list[str] = ["/repos"]

    session_image: str = "ghcr.io/anomalyco/opencode:1.19.5"
    session_port: int = 4096
    shared_mount_path: str = "/shared"

    temporal_address: str = "temporal:7233"
    temporal_namespace: str = "default"
    temporal_task_queue: str = "opencode-manager"
    reap_interval_seconds: int = 300
    idle_grace_seconds: int = 900

    docker_sandbox_network: str = "opencode-sandbox"
    docker_egress_network: str = "opencode-egress"
    docker_shared_volume: str = "opencode-shared"

    k8s_namespace: str = "default"
    k8s_shared_pvc: str = "opencode-shared"

    def session_host(self, session_id: str) -> str:
        return f"{session_id}.{self.domain}"

    def public_url(self, host: str) -> str:
        port = f":{self.public_port}" if self.public_port else ""
        return f"{self.public_scheme}://{host}{port}"


@lru_cache
def get_settings() -> Settings:
    return Settings()
