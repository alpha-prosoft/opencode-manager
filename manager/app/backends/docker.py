import logging

import docker
from docker.errors import ImageNotFound, NotFound
from docker.types import Mount

from ..models import SessionRecord, SessionStatus
from .base import LABEL_SESSION, LABEL_SUB, WORKSPACE_DIR, ContainerBackend, resource_name

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

    def _create(self, record: SessionRecord) -> None:
        self._ensure_image(record.image)
        target, subpath = self.mount(record.id)
        self.client.containers.create(
            image=record.image,
            name=resource_name(record.id),
            hostname=resource_name(record.id),
            command=self.command(),
            working_dir=WORKSPACE_DIR,
            environment=self.instance_env(record),
            labels={LABEL_SESSION: record.id, LABEL_SUB: record.sub},
            network=self.settings.docker_network,
            mounts=[Mount(target=target, source=self.settings.docker_shared_volume, subpath=subpath)],
        )

    def start(self, record: SessionRecord) -> None:
        try:
            container = self.client.containers.get(resource_name(record.id))
        except NotFound:
            self._create(record)
            container = self.client.containers.get(resource_name(record.id))
        if container.status != "running":
            container.start()

    def stop(self, session_id: str) -> None:
        try:
            self.client.containers.get(resource_name(session_id)).stop(timeout=30)
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
            c.attrs["Labels"][LABEL_SESSION]: STATE_TO_STATUS.get(c.status, SessionStatus.ERROR)
            for c in containers
        }

    def service_url(self, session_id: str) -> str:
        return f"http://{resource_name(session_id)}:{self.settings.session_port}"
