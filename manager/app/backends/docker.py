import logging

import docker
from docker.errors import ImageNotFound, NotFound
from docker.types import Mount

from ..models import SessionRecord, SessionStatus
from ..platform.harness import Sandbox
from .base import (
    LABEL_NETWORK,
    LABEL_SESSION,
    LABEL_SUB,
    LABEL_TEAM,
    SESSION_UID,
    WORKSPACE_DIR,
    ContainerBackend,
    resource_name,
)

log = logging.getLogger(__name__)

STATE_TO_STATUS = {
    "running": SessionStatus.RUNNING,
    "restarting": SessionStatus.STARTING,
    "created": SessionStatus.STOPPED,
    "exited": SessionStatus.STOPPED,
    "paused": SessionStatus.STOPPED,
    "dead": SessionStatus.ERROR,
    "removing": SessionStatus.STOPPED,
}


class DockerBackend(ContainerBackend):
    def __init__(self, settings):
        super().__init__(settings)
        self.client = docker.from_env()

    def _ensure_image(self, image: str) -> None:
        try:
            self.client.images.get(image)
        except ImageNotFound:
            log.info("Pulling %s", image)
            self.client.images.pull(image)

    def prepare(self) -> None:
        self._ensure_image(self.settings.session_image)

    def _egress_network(self):
        name = self.settings.docker_egress_network
        try:
            return self.client.networks.get(name)
        except NotFound:
            log.info("Creating egress network %s", name)
            return self.client.networks.create(name, driver="bridge", internal=False)

    def _create(self, record: SessionRecord, sandbox: Sandbox) -> None:
        self._ensure_image(sandbox.image)
        egress = self._egress_network() if sandbox.network == "internet" else None
        mounts = [
            Mount(target=target, source=self.settings.docker_shared_volume, subpath=subpath, read_only=read_only)
            for target, subpath, read_only in self.mounts(record.id)
        ]
        container = self.client.containers.create(
            image=sandbox.image,
            name=resource_name(record.id),
            hostname=resource_name(record.id),
            command=self.command(),
            working_dir=WORKSPACE_DIR,
            environment=self.environment(sandbox),
            labels={
                LABEL_SESSION: record.id,
                LABEL_SUB: record.sub,
                LABEL_TEAM: record.team_id or "",
                LABEL_NETWORK: sandbox.network,
            },
            network=self.settings.docker_sandbox_network,
            mounts=mounts,
            user=f"{SESSION_UID}:{SESSION_UID}",
            read_only=True,
            tmpfs={"/tmp": "rw,exec,nosuid,size=512m"},
            cap_drop=["ALL"],
            security_opt=["no-new-privileges"],
            pids_limit=sandbox.pids,
            mem_limit=f"{sandbox.memory_mb}m",
            nano_cpus=int(sandbox.cpus * 1e9),
        )
        if egress is not None:
            try:
                egress.connect(container)
            except Exception:
                container.remove(force=True)
                raise

    def start(self, record: SessionRecord, sandbox: Sandbox) -> None:
        try:
            container = self.client.containers.get(resource_name(record.id))
        except NotFound:
            self._create(record, sandbox)
            container = self.client.containers.get(resource_name(record.id))
        if container.status != "running":
            container.start()

    def stop(self, session_id: str) -> None:
        try:
            self.client.containers.get(resource_name(session_id)).stop(timeout=5)
        except NotFound:
            pass

    def delete(self, session_id: str) -> None:
        try:
            self.client.containers.get(resource_name(session_id)).remove(force=True)
        except NotFound:
            pass

    def statuses(self) -> dict[str, SessionStatus]:
        containers = self.client.containers.list(all=True, sparse=True, filters={"label": LABEL_SESSION})
        return {
            c.attrs["Labels"][LABEL_SESSION]: STATE_TO_STATUS.get(c.status, SessionStatus.ERROR) for c in containers
        }

    def service_url(self, session_id: str) -> str:
        return f"http://{resource_name(session_id)}:{self.settings.session_port}"
