(ns manager-svc.platform.core
  "Domain entry point, see manager-svc.system for the contract."
  (:require [clojure.tools.logging :as log]
            [manager-svc.client :as client]
            [manager-svc.platform.api :as api]
            [manager-svc.platform.navigation :as navigation]
            [manager-svc.platform.reload :as reload]
            [manager-svc.platform.state :as state]
            [manager-svc.platform.views :as views]))

(defn register
  [ctx]
  (navigation/register (views/register (state/register ctx))))

(defn edd-gateway
  "How the sync reads and records aggregates: edd over HTTP as the platform.
   It reads the aggregates it wrote, not their directory rows, so it never
   waits for effects."
  [settings]
  {:query (fn [query] (client/query settings query))
   :command (fn [& commands] (apply client/command-no-settle settings commands))})

(defn components
  [settings]
  {:platform/registry (atom {})
   :platform/edd (edd-gateway settings)
   :platform/configured-tenants (atom nil)
   :platform/unknown-sync-requests (atom #{})})

(def routes
  reload/routes)

(def host-handlers
  [])

(defn startup-sync
  "Loads the cache from the aggregates once, then syncs every tenant; a
   failed load never skips the sync."
  [system]
  (when-not (:loaded (state/snapshot system))
    (try
      (api/load-cache! system)
      (catch Exception e
        (log/warn e "Could not load the registry cache; syncing anyway"))))
  (api/sync! system))

(defn workers
  [system]
  [{:id :tenant-sync
    :interval-seconds (* 60 (max 1 (get-in system [:settings :sync-interval-minutes] 10)))
    :initial-delay-seconds 0
    :run startup-sync}
   {:id :tenant-sync-requested
    :interval-seconds 60
    :initial-delay-seconds 30
    :reactive true
    :run api/sync-requested!}
   (reload/worker system)])
