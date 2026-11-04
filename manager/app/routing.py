import os
import re
import tempfile
import threading
from abc import ABC, abstractmethod
from pathlib import Path

import yaml

from .backends import ContainerBackend
from .backends.base import LABEL_SESSION, resource_name
from .config import Settings
from .models import SessionRecord

FORWARD_AUTH_PATH = "/api/gateway/auth"
FORWARD_AUTH_HEADERS = ["Authorization"]


class NoAliasDumper(yaml.SafeDumper):
    def ignore_aliases(self, data) -> bool:
        return True


class RouteProvider(ABC):
    def __init__(self, settings: Settings, backend: ContainerBackend):
        self.settings = settings
        self.backend = backend

    @abstractmethod
    def sync(self, sessions: list[SessionRecord]) -> None: ...


class TraefikFileRoutes(RouteProvider):
    MANAGER_PRIORITY = 1000
    SESSION_PRIORITY = 500
    WAKE_PRIORITY = 1

    def __init__(self, settings: Settings, backend: ContainerBackend):
        super().__init__(settings, backend)
        self._lock = threading.Lock()

    def render(self, sessions: list[SessionRecord]) -> dict:
        domain = self.settings.domain
        entrypoints = [self.settings.traefik_entrypoint]
        routers = {
            "manager": {
                "rule": f"Host(`{domain}`)",
                "entryPoints": entrypoints,
                "priority": self.MANAGER_PRIORITY,
                "service": "manager",
            },
            "wake": {
                "rule": f"HostRegexp(`^[a-z0-9]+\\.{re.escape(domain)}$`)",
                "entryPoints": entrypoints,
                "priority": self.WAKE_PRIORITY,
                "service": "manager",
            },
        }
        services = {
            "manager": {"loadBalancer": {"servers": [{"url": self.settings.manager_service_url}]}}
        }
        for session in sessions:
            name = f"session-{session.id}"
            routers[name] = {
                "rule": f"Host(`{self.settings.session_host(session.id)}`)",
                "entryPoints": entrypoints,
                "priority": self.SESSION_PRIORITY,
                "middlewares": [self.settings.forward_auth_middleware],
                "service": name,
            }
            services[name] = {
                "loadBalancer": {"servers": [{"url": self.backend.service_url(session.id)}]}
            }
        middleware = {
            "forwardAuth": {
                "address": f"{self.settings.manager_service_url}{FORWARD_AUTH_PATH}",
                "authResponseHeaders": FORWARD_AUTH_HEADERS,
            }
        }
        return {
            "http": {
                "routers": routers,
                "services": services,
                "middlewares": {self.settings.forward_auth_middleware: middleware},
            }
        }

    def sync(self, sessions: list[SessionRecord]) -> None:
        target = Path(self.settings.traefik_dynamic_file)
        target.parent.mkdir(parents=True, exist_ok=True)
        content = yaml.dump(self.render(sessions), Dumper=NoAliasDumper, sort_keys=False)
        with self._lock:
            fd, tmp = tempfile.mkstemp(dir=target.parent, prefix=".sessions-", suffix=".tmp")
            with os.fdopen(fd, "w") as f:
                f.write(content)
            os.chmod(tmp, 0o644)
            os.replace(tmp, target)


class GatewayApiRoutes(RouteProvider):
    GROUP = "gateway.networking.k8s.io"
    VERSION = "v1"
    PLURAL = "httproutes"

    def __init__(self, settings: Settings, backend: ContainerBackend):
        super().__init__(settings, backend)
        from kubernetes import client

        from .backends.kubernetes import load_kube_config

        load_kube_config()
        self.api = client.CustomObjectsApi()
        self.namespace = settings.k8s_namespace
        self._lock = threading.Lock()

    def _parent_ref(self) -> dict:
        ref = {"name": self.settings.gateway_name}
        if self.settings.gateway_namespace:
            ref["namespace"] = self.settings.gateway_namespace
        if self.settings.gateway_section_name:
            ref["sectionName"] = self.settings.gateway_section_name
        return ref

    def route(self, session: SessionRecord) -> dict:
        return {
            "apiVersion": f"{self.GROUP}/{self.VERSION}",
            "kind": "HTTPRoute",
            "metadata": {"name": resource_name(session.id), "labels": {LABEL_SESSION: session.id}},
            "spec": {
                "parentRefs": [self._parent_ref()],
                "hostnames": [self.settings.session_host(session.id)],
                "rules": [
                    {
                        "filters": [
                            {
                                "type": "ExtensionRef",
                                "extensionRef": {
                                    "group": "traefik.io",
                                    "kind": "Middleware",
                                    "name": self.settings.forward_auth_middleware,
                                },
                            }
                        ],
                        "backendRefs": [
                            {"name": resource_name(session.id), "port": self.settings.session_port}
                        ],
                    }
                ],
            },
        }

    def sync(self, sessions: list[SessionRecord]) -> None:
        args = (self.GROUP, self.VERSION, self.namespace, self.PLURAL)
        with self._lock:
            existing = self.api.list_namespaced_custom_object(*args, label_selector=LABEL_SESSION)
            current = {item["metadata"]["labels"][LABEL_SESSION] for item in existing["items"]}
            wanted = {session.id: session for session in sessions}
            for session_id in wanted.keys() - current:
                self.api.create_namespaced_custom_object(*args, self.route(wanted[session_id]))
            for session_id in current - wanted.keys():
                self.api.delete_namespaced_custom_object(*args, resource_name(session_id))


def create_route_provider(settings: Settings, backend: ContainerBackend) -> RouteProvider:
    if settings.router == "gateway-api":
        return GatewayApiRoutes(settings, backend)
    return TraefikFileRoutes(settings, backend)
