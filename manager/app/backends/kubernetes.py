from kubernetes import client, config
from kubernetes.client.exceptions import ApiException

from ..models import SessionRecord, SessionStatus
from ..opencode import basic_auth
from .base import LABEL_SESSION, LABEL_SUB, WORKSPACE_DIR, ContainerBackend, resource_name


def load_kube_config() -> None:
    try:
        config.load_incluster_config()
    except config.ConfigException:
        config.load_kube_config()


def ignore_not_found(call, *args, **kwargs):
    try:
        return call(*args, **kwargs)
    except ApiException as error:
        if error.status != 404:
            raise


class KubernetesBackend(ContainerBackend):
    def __init__(self, settings):
        super().__init__(settings)
        load_kube_config()
        self.core = client.CoreV1Api()
        self.apps = client.AppsV1Api()
        self.namespace = settings.k8s_namespace

    def _metadata(self, record: SessionRecord) -> client.V1ObjectMeta:
        return client.V1ObjectMeta(
            name=resource_name(record.id),
            labels={LABEL_SESSION: record.id},
            annotations={LABEL_SUB: record.sub},
        )

    def _service(self, record: SessionRecord) -> client.V1Service:
        return client.V1Service(
            metadata=self._metadata(record),
            spec=client.V1ServiceSpec(
                selector={LABEL_SESSION: record.id},
                ports=[client.V1ServicePort(name="http", port=self.settings.session_port)],
            ),
        )

    def _deployment(self, record: SessionRecord) -> client.V1Deployment:
        port = self.settings.session_port
        target, subpath = self.mount(record.id)
        container = client.V1Container(
            name="opencode",
            image=record.image,
            args=self.command(),
            working_dir=WORKSPACE_DIR,
            env=[client.V1EnvVar(name=k, value=v) for k, v in self.instance_env(record).items()],
            ports=[client.V1ContainerPort(container_port=port)],
            volume_mounts=[client.V1VolumeMount(name="shared", mount_path=target, sub_path=subpath)],
            readiness_probe=client.V1Probe(
                http_get=client.V1HTTPGetAction(
                    path="/global/health",
                    port=port,
                    http_headers=[client.V1HTTPHeader(name="Authorization", value=basic_auth(record.secret_key))],
                ),
                period_seconds=5,
            ),
        )
        pod = client.V1PodTemplateSpec(
            metadata=client.V1ObjectMeta(labels={LABEL_SESSION: record.id}),
            spec=client.V1PodSpec(
                containers=[container],
                volumes=[
                    client.V1Volume(
                        name="shared",
                        persistent_volume_claim=client.V1PersistentVolumeClaimVolumeSource(
                            claim_name=self.settings.k8s_shared_pvc
                        ),
                    )
                ],
            ),
        )
        return client.V1Deployment(
            metadata=self._metadata(record),
            spec=client.V1DeploymentSpec(
                replicas=1,
                strategy=client.V1DeploymentStrategy(type="Recreate"),
                selector=client.V1LabelSelector(match_labels={LABEL_SESSION: record.id}),
                template=pod,
            ),
        )

    def _scale(self, session_id: str, replicas: int) -> None:
        self.apps.patch_namespaced_deployment_scale(
            resource_name(session_id), self.namespace, {"spec": {"replicas": replicas}}
        )

    def provision(self, record: SessionRecord) -> None:
        try:
            self.core.create_namespaced_service(self.namespace, self._service(record))
        except ApiException as error:
            if error.status != 409:
                raise

    def start(self, record: SessionRecord) -> None:
        try:
            self._scale(record.id, 1)
        except ApiException as error:
            if error.status != 404:
                raise
            self.provision(record)
            self.apps.create_namespaced_deployment(self.namespace, self._deployment(record))

    def stop(self, session_id: str) -> None:
        ignore_not_found(self._scale, session_id, 0)

    def delete(self, session_id: str) -> None:
        name = resource_name(session_id)
        ignore_not_found(self.apps.delete_namespaced_deployment, name, self.namespace)
        ignore_not_found(self.core.delete_namespaced_service, name, self.namespace)

    def statuses(self) -> dict[str, SessionStatus]:
        deployments = self.apps.list_namespaced_deployment(self.namespace, label_selector=LABEL_SESSION)
        return {d.metadata.labels[LABEL_SESSION]: self._status(d) for d in deployments.items}

    @staticmethod
    def _status(deployment: client.V1Deployment) -> SessionStatus:
        if not deployment.spec.replicas:
            return SessionStatus.STOPPED
        if deployment.status.ready_replicas:
            return SessionStatus.RUNNING
        return SessionStatus.STARTING

    def service_url(self, session_id: str) -> str:
        name = resource_name(session_id)
        return f"http://{name}.{self.namespace}.svc.cluster.local:{self.settings.session_port}"
