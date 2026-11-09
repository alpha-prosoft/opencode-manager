(ns manager-svc.platform.core
  "Domain entry point, see manager-svc.system for the contract."
  (:require [clojure.tools.logging :as log]
            [manager-svc.client :as client]
            [manager-svc.platform.api :as api]
            [manager-svc.platform.state :as state]
            [manager-svc.platform.views :as views]))

(defn register
  [ctx]
  (views/register (state/register ctx)))

(defn edd-gateway
  "How the sync reads and records aggregates: edd over HTTP as the platform."
  [settings]
  {:query (fn [query] (client/query settings query))
   :command (fn [& commands] (apply client/command settings commands))})

(defn components
  [settings]
  {:platform/registry (atom {})
   :platform/edd (edd-gateway settings)})

(def routes
  [])

(def host-handlers
  [])

(defn startup-sync
  "Loads the cache from the aggregates once, then syncs; a failed load never
   skips the sync."
  [system]
  (when-not (:loaded (state/snapshot system))
    (try
      (api/load-cache! system)
      (catch Exception e
        (log/warn e "Could not load the registry cache; syncing anyway"))))
  (api/sync! system))

(defn workers
  [system]
  [{:id :platform-sync
    :interval-seconds (* 60 (max 1 (get-in system [:settings :sync-interval-minutes] 10)))
    :initial-delay-seconds 0
    :run startup-sync}
   {:id :platform-sync-requested
    :interval-seconds 60
    :initial-delay-seconds 30
    :reactive true
    :run api/sync-requested!}])
