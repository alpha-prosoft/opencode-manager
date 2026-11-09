(ns manager-svc.sessions.backend
  "Where sandboxes run (manager/app/backends). Statuses are keyed by session id
   (UUID) with values :running, :starting, :stopped or :error."
  (:require [manager-svc.sessions.links :as links]
            [manager-svc.sessions.storage :as storage]))

(defprotocol Backend
  (prepare! [this] "One-time setup at startup, e.g. pulling the session image.")
  (image-ready? [this image] "True when a sandbox with image can start without pulling it.")
  (pull-image! [this image] "Pulls image; slow, never called under the sessions lock.")
  (start! [this session sandbox])
  (stop! [this session-id])
  (delete! [this session-id])
  (statuses [this])
  (service-url [this session-id] "Internal URL of the session's OpenCode server."))

(def session-dir "/session")
(def home-dir "/session/home")
(def config-mount "/etc/opencode")
(def label-session "opencode-manager/session")
(def label-sub "opencode-manager/sub")
(def label-team "opencode-manager/team")
(def label-network "opencode-manager/network")
(def active #{:running :starting})

(defn resource-name
  [session-id]
  (str "opencode-" session-id))

(defn command
  [settings]
  ["web" "--hostname" "0.0.0.0" "--port" (str (:session-port settings))])

(defn environment
  [settings session-id sandbox]
  (assoc (into {} (map (fn [[k v]] [(name k) (str v)])) (:env sandbox))
         "HOME" home-dir
         "OPENCODE_SERVER_PASSWORD" (links/secret-key settings session-id)))

(defn mounts
  "[target subpath read-only?] on the shared volume."
  [session-id]
  [[session-dir (storage/session-subpath session-id) false]
   [config-mount (storage/config-subpath session-id) true]])

(defn status
  [backend session-id]
  (get (statuses backend) session-id :stopped))

(defn active?
  [backend session-id]
  (contains? active (status backend session-id)))
