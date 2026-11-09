(ns manager-svc.sessions.docker
  "Sandboxes as Docker containers, through the Engine API on the Docker socket
   (manager/app/backends/docker.py)."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.links :as links]
            [manager-svc.sessions.storage :as storage]
            [manager-svc.sessions.unix-http :as http])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]))

(def state->status
  {"running" :running
   "restarting" :starting
   "created" :stopped
   "exited" :stopped
   "paused" :stopped
   "dead" :error
   "removing" :stopped})

(defn- encode
  [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(def pull-timeout-ms
  (* 10 60 1000))

(defn- call
  [socket method path & [data]]
  (http/request socket method path data))

(defn- check!
  [{:keys [status body] :as response} & allowed]
  (when-not (or (<= 200 status 299) (some #{status} allowed))
    (throw (ex-info (str "Docker API error " status ": " body) {:status status})))
  response)

(defn- image-ref
  [image]
  (let [slash
        (string/last-index-of image "/")

        colon
        (string/last-index-of image ":")]
    (if (and colon (or (nil? slash) (> colon slash)))
      [(subs image 0 colon) (subs image (inc colon))]
      [image "latest"])))

(defn- image-present?
  [socket image]
  (= 200 (:status (check! (call socket :get (str "/images/" (encode image) "/json")) 404))))

(defn- pull!
  [socket image]
  (log/info "Pulling" image)
  (let [[from tag]
        (image-ref image)]
    (check! (http/request socket :post (str "/images/create?fromImage=" (encode from) "&tag=" (encode tag))
                          nil {:timeout-ms pull-timeout-ms}))))

(defn- ensure-image!
  [socket image]
  (when-not (image-present? socket image)
    (pull! socket image)))

(defn- ensure-egress-network!
  [socket network]
  (when (= 404 (:status (call socket :get (str "/networks/" (encode network)))))
    (log/info "Creating egress network" network)
    (check! (call socket :post "/networks/create" {"Name" network "Driver" "bridge" "Internal" false}) 409)))

(defn container-spec
  [settings {:keys [id sub team-id]} sandbox]
  {"Image" (:image sandbox)
   "Hostname" (backend/resource-name id)
   "Cmd" (backend/command settings)
   "WorkingDir" links/workspace-dir
   "Env" (mapv (fn [[k v]] (str k "=" v)) (backend/environment settings id sandbox))
   "User" (str storage/session-uid ":" storage/session-uid)
   "Labels" {backend/label-session (str id)
             backend/label-sub (str sub)
             backend/label-team (str team-id)
             backend/label-network (str (:network sandbox))}
   "HostConfig" {"NetworkMode" (:docker-sandbox-network settings)
                 "Mounts" (mapv (fn [[target subpath read-only]]
                                  {"Type" "volume"
                                   "Source" (:docker-shared-volume settings)
                                   "Target" target
                                   "ReadOnly" read-only
                                   "VolumeOptions" {"Subpath" subpath}})
                                (backend/mounts id))
                 "ReadonlyRootfs" true
                 "Tmpfs" {"/tmp" "rw,exec,nosuid,size=512m"}
                 "CapDrop" ["ALL"]
                 "SecurityOpt" ["no-new-privileges"]
                 "PidsLimit" (long (or (:pids sandbox) 512))
                 "Memory" (* 1024 1024 (long (or (:memory-mb sandbox) 2048)))
                 "NanoCpus" (long (* 1e9 (double (or (:cpus sandbox) 1))))}})

(defn- create!
  [socket settings session sandbox]
  (let [internet?
        (= "internet" (name (or (:network sandbox) "none")))

        name
        (backend/resource-name (:id session))]
    (when internet?
      (ensure-egress-network! socket (:docker-egress-network settings)))
    (check! (call socket :post (str "/containers/create?name=" (encode name)) (container-spec settings session sandbox)))
    (when internet?
      (let [response
            (call socket :post (str "/networks/" (encode (:docker-egress-network settings)) "/connect") {"Container" name})]
        (when-not (<= 200 (:status response) 299)
          (call socket :delete (str "/containers/" (encode name) "?force=true"))
          (check! response))))))

(defrecord DockerBackend [settings socket]
  backend/Backend
  (prepare! [_]
    (ensure-image! socket (:session-image settings)))

  (image-ready? [_ image]
    (image-present? socket image))

  (pull-image! [_ image]
    (pull! socket image))

  (start! [_ session sandbox]
    (let [name
          (backend/resource-name (:id session))

          inspect
          (call socket :get (str "/containers/" (encode name) "/json"))]
      (when (= 404 (:status inspect))
        (create! socket settings session sandbox))
      (when-not (= "running" (get-in (http/read-json inspect) ["State" "Status"]))
        (check! (call socket :post (str "/containers/" (encode name) "/start")) 304))))

  (stop! [_ session-id]
    (check! (call socket :post (str "/containers/" (encode (backend/resource-name session-id)) "/stop?t=5")) 304 404))

  (delete! [_ session-id]
    (check! (call socket :delete (str "/containers/" (encode (backend/resource-name session-id)) "?force=true")) 404))

  (statuses [_]
    (let [filters
          (json/write-value-as-string {"label" [backend/label-session]})

          containers
          (http/read-json (check! (call socket :get (str "/containers/json?all=true&filters=" (encode filters)))))]
      (into {}
            (keep (fn [container]
                    (when-let [id (links/parse-id (get-in container ["Labels" backend/label-session]))]
                      [id (get state->status (get container "State") :error)])))
            containers)))

  (service-url [_ session-id]
    (str "http://" (backend/resource-name session-id) ":" (:session-port settings))))

(defn socket-path
  []
  (let [docker-host
        (System/getenv "DOCKER_HOST")]
    (if (and docker-host (string/starts-with? docker-host "unix://"))
      (subs docker-host (count "unix://"))
      "/var/run/docker.sock")))

(defn create
  [settings]
  (->DockerBackend settings (socket-path)))
