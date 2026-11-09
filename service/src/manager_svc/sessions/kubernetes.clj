(ns manager-svc.sessions.kubernetes
  "Sandboxes as a Deployment and Service per session; the kubelet pulls
   images."
  (:require [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.kube :as kube]
            [manager-svc.sessions.links :as links]
            [manager-svc.opencode :as opencode]
            [manager-svc.sessions.storage :as storage]))

(defn- labels
  [{:keys [id team-id]} sandbox]
  (cond-> {backend/label-session (str id)
           backend/label-team (or team-id "none")}
    sandbox (assoc backend/label-network (name (or (:network sandbox) "none")))))

(defn- metadata
  [session sandbox]
  {"name" (backend/resource-name (:id session))
   "labels" (labels session sandbox)
   "annotations" {backend/label-sub (str (:sub session))}})

(defn service-spec
  [settings session]
  {"apiVersion" "v1"
   "kind" "Service"
   "metadata" (metadata session nil)
   "spec" {"selector" {backend/label-session (str (:id session))}
           "ports" [{"name" "http" "port" (:session-port settings)}]}})

(defn deployment-spec
  [settings {:keys [id] :as session} sandbox]
  (let [port
        (:session-port settings)

        container
        {"name" "opencode"
         "image" (:image sandbox)
         "args" (backend/command settings)
         "workingDir" links/workspace-dir
         "env" (mapv (fn [[k v]] {"name" k "value" v}) (backend/environment settings id sandbox))
         "ports" [{"containerPort" port}]
         "volumeMounts" (conj (mapv (fn [[target subpath read-only]]
                                      {"name" "shared" "mountPath" target "subPath" subpath "readOnly" read-only})
                                    (backend/mounts id))
                              {"name" "tmp" "mountPath" "/tmp"})
         "resources" {"limits" {"cpu" (str (or (:cpus sandbox) 1)) "memory" (str (or (:memory-mb sandbox) 2048) "Mi")}
                      "requests" {"cpu" "100m" "memory" "256Mi"}}
         "securityContext" {"runAsUser" storage/session-uid
                            "runAsGroup" storage/session-uid
                            "runAsNonRoot" true
                            "readOnlyRootFilesystem" true
                            "allowPrivilegeEscalation" false
                            "capabilities" {"drop" ["ALL"]}
                            "seccompProfile" {"type" "RuntimeDefault"}}
         "readinessProbe" {"httpGet" {"path" "/global/health"
                                      "port" port
                                      "httpHeaders" [{"name" "Authorization"
                                                      "value" (opencode/basic-auth (links/secret-key settings id))}]}
                           "periodSeconds" 5}}]
    {"apiVersion" "apps/v1"
     "kind" "Deployment"
     "metadata" (metadata session sandbox)
     "spec" {"replicas" 1
             "strategy" {"type" "Recreate"}
             "selector" {"matchLabels" {backend/label-session (str id)}}
             "template" {"metadata" {"labels" (labels session sandbox)}
                         "spec" {"containers" [container]
                                 "automountServiceAccountToken" false
                                 "enableServiceLinks" false
                                 "volumes" [{"name" "shared"
                                             "persistentVolumeClaim" {"claimName" (:k8s-shared-pvc settings)}}
                                            {"name" "tmp" "emptyDir" {"sizeLimit" "512Mi"}}]}}}}))

(defn deployment-status
  [deployment]
  (cond
    (not (pos? (or (get-in deployment ["spec" "replicas"]) 0))) :stopped
    (pos? (or (get-in deployment ["status" "readyReplicas"]) 0)) :running
    :else :starting))

(defn- deployments-path
  [namespace]
  (str "/apis/apps/v1/namespaces/" namespace "/deployments"))

(defn- services-path
  [namespace]
  (str "/api/v1/namespaces/" namespace "/services"))

(defn- scale!
  [connection namespace session-id replicas]
  (kube/request connection :patch
                (str (deployments-path namespace) "/" (backend/resource-name session-id) "/scale")
                {"spec" {"replicas" replicas}}
                "application/merge-patch+json"))

(defn- provision!
  [connection settings session]
  (kube/check! (kube/request connection :post (services-path (:k8s-namespace settings)) (service-spec settings session)) 409))

(defrecord KubernetesBackend [settings connection]
  backend/Backend
  (prepare! [_]
    nil)

  (image-ready? [_ _image]
    true)

  (pull-image! [_ _image]
    nil)

  (start! [_ session sandbox]
    (let [namespace
          (:k8s-namespace settings)

          response
          (scale! connection namespace (:id session) 1)]
      (when (= 404 (:status response))
        (provision! connection settings session)
        (kube/check! (kube/request connection :post (deployments-path namespace) (deployment-spec settings session sandbox))))
      (kube/check! response 404)))

  (stop! [_ session-id]
    (kube/check! (scale! connection (:k8s-namespace settings) session-id 0) 404))

  (delete! [_ session-id]
    (let [namespace
          (:k8s-namespace settings)

          name
          (backend/resource-name session-id)]
      (kube/check! (kube/request connection :delete (str (deployments-path namespace) "/" name)) 404)
      (kube/check! (kube/request connection :delete (str (services-path namespace) "/" name)) 404)))

  (statuses [_]
    (let [{:keys [body]}
          (kube/check! (kube/request connection :get (str (deployments-path (:k8s-namespace settings))
                                                          "?labelSelector=" (kube/encode backend/label-session))))]
      (into {}
            (keep (fn [deployment]
                    (when-let [id (links/parse-id (get-in deployment ["metadata" "labels" backend/label-session]))]
                      [id (deployment-status deployment)])))
            (get body "items"))))

  (service-url [_ session-id]
    (str "http://" (backend/resource-name session-id) "." (:k8s-namespace settings)
         ".svc.cluster.local:" (:session-port settings))))

(defn create
  [settings]
  (->KubernetesBackend settings (kube/in-cluster)))
