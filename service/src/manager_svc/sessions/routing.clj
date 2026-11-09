(ns manager-svc.sessions.routing
  "How the reverse proxy reaches sessions: a Traefik dynamic file, or
   Gateway API HTTPRoutes. Every request to a session host goes through the
   manager's forward-auth endpoint, which answers with the OpenCode
   password (Authorization) and may refresh the session host's cookie;
   then the Cookie header is removed, so the sandbox never sees a cookie."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [manager-svc.auth :as auth]
            [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.kube :as kube]
            [yaml.core :as yaml])
  (:import [java.nio.file Files StandardCopyOption CopyOption]
           [java.nio.file.attribute PosixFilePermissions FileAttribute]))

(def forward-auth-path "/api/gateway/auth")
(def forward-auth-headers ["Authorization"])

(defn strip-cookie-middleware
  [settings]
  (get settings :strip-cookie-middleware "opencode-strip-cookie"))

(defn forward-auth-config
  "The forwardAuth middleware: the address, the header passed to the
   sandbox and the session cookie passed back to the browser."
  [settings]
  {"address" (str (:manager-service-url settings) forward-auth-path)
   "authResponseHeaders" forward-auth-headers
   "addAuthCookiesToResponse" [(auth/session-cookie settings)]})

(def strip-cookie-config
  {"headers" {"customRequestHeaders" {"Cookie" ""}}})

(defprotocol RouteProvider
  (sync-routes! [this sessions] "Make the proxy route exactly these sessions."))

(def manager-priority 1000)
(def session-priority 500)
(def wake-priority 1)

(defn- regex-escape
  [text]
  (string/replace text #"[.\\+*?\[^\]$(){}=!<>|:\-]" #(str "\\" %)))

(defn traefik-config
  [settings backend sessions]
  (let [domain
        (:domain settings)

        entrypoints
        [(:traefik-entrypoint settings)]

        manager-url
        (:manager-service-url settings)

        middleware
        (:forward-auth-middleware settings)

        strip
        (strip-cookie-middleware settings)

        session-routers
        (for [{:keys [id]} sessions]
          [(str "session-" id)
           {"rule" (str "Host(`" id "." domain "`)")
            "entryPoints" entrypoints
            "priority" session-priority
            "middlewares" [middleware strip]
            "service" (str "session-" id)}])

        session-services
        (for [{:keys [id]} sessions]
          [(str "session-" id)
           {"loadBalancer" {"servers" [{"url" (backend/service-url backend id)}]}}])]
    {"http" {"routers" (into (array-map
                              "manager" {"rule" (str "Host(`" domain "`)")
                                         "entryPoints" entrypoints
                                         "priority" manager-priority
                                         "service" "manager"}
                              "wake" {"rule" (str "HostRegexp(`^[a-z0-9-]+\\." (regex-escape domain) "$`)")
                                      "entryPoints" entrypoints
                                      "priority" wake-priority
                                      "service" "manager"})
                             session-routers)
             "services" (into (array-map "manager" {"loadBalancer" {"servers" [{"url" manager-url}]}})
                              session-services)
             "middlewares" {middleware {"forwardAuth" (forward-auth-config settings)}
                            strip strip-cookie-config}}}))

(defn render-traefik
  [settings backend sessions]
  (yaml/generate-string (traefik-config settings backend sessions)
                        :dumper-options {:flow-style :block}))

(defn- write-atomically!
  [target content]
  (let [file
        (io/file target)

        dir
        (Files/createDirectories (.toPath (.getParentFile (.getAbsoluteFile file))) (make-array FileAttribute 0))

        tmp
        (Files/createTempFile dir ".sessions-" ".tmp" (make-array FileAttribute 0))]
    (spit (.toFile tmp) content)
    (Files/setPosixFilePermissions tmp (PosixFilePermissions/fromString "rw-r--r--"))
    (Files/move tmp (.toPath file) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))

(defrecord TraefikFileRoutes [settings backend last-written]
  RouteProvider
  (sync-routes! [_ sessions]
    (locking last-written
      (let [content
            (render-traefik settings backend sessions)]
        (when (or (not= content @last-written)
                  (not (.exists (io/file (:traefik-dynamic-file settings)))))
          (write-atomically! (:traefik-dynamic-file settings) content)
          (reset! last-written content))))))

(def route-group "gateway.networking.k8s.io")
(def route-version "v1")

(defn- parent-ref
  [{:keys [gateway-name gateway-namespace gateway-section-name]}]
  (cond-> {"name" gateway-name}
    gateway-namespace (assoc "namespace" gateway-namespace)
    gateway-section-name (assoc "sectionName" gateway-section-name)))

(defn http-route
  [settings {:keys [id]}]
  {"apiVersion" (str route-group "/" route-version)
   "kind" "HTTPRoute"
   "metadata" {"name" (backend/resource-name id)
               "labels" {backend/label-session (str id)}}
   "spec" {"parentRefs" [(parent-ref settings)]
           "hostnames" [(str id "." (:domain settings))]
           "rules" [{"filters" [{"type" "ExtensionRef"
                                 "extensionRef" {"group" "traefik.io"
                                                 "kind" "Middleware"
                                                 "name" (:forward-auth-middleware settings)}}
                                {"type" "RequestHeaderModifier"
                                 "requestHeaderModifier" {"remove" ["Cookie"]}}]
                     "backendRefs" [{"name" (backend/resource-name id)
                                     "port" (:session-port settings)}]}]}})

(defrecord GatewayApiRoutes [settings connection]
  RouteProvider
  (sync-routes! [_ sessions]
    (locking connection
      (let [path
            (str "/apis/" route-group "/" route-version "/namespaces/" (:k8s-namespace settings) "/httproutes")

            {:keys [body]}
            (kube/check! (kube/request connection :get (str path "?labelSelector=" (kube/encode backend/label-session))))

            current
            (set (keep #(get-in % ["metadata" "labels" backend/label-session]) (get body "items")))

            wanted
            (into {} (map (juxt (comp str :id) identity)) sessions)]
        (doseq [[session-id session] wanted
                :when (not (contains? current session-id))]
          (kube/check! (kube/request connection :post path (http-route settings session)) 409))
        (doseq [session-id current
                :when (not (contains? wanted session-id))]
          (kube/check! (kube/request connection :delete (str path "/" (backend/resource-name session-id))) 404))))))

(defn create
  [settings backend]
  (if (= "gateway-api" (:router settings))
    (->GatewayApiRoutes settings (kube/in-cluster))
    (->TraefikFileRoutes settings backend (atom nil))))
