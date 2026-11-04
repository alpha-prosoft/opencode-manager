from ..config import Settings
from .base import ContainerBackend


def create_backend(settings: Settings) -> ContainerBackend:
    if settings.backend == "kubernetes":
        from .kubernetes import KubernetesBackend

        return KubernetesBackend(settings)
    from .docker import DockerBackend

    return DockerBackend(settings)
