(ns manager-svc.platform.reload
  "Configuration that changes while the manager runs. The claims parser
   file and the tenants file are read again when they change; this job
   syncs tenants that appear in the tenants file right away, and operators
   reload everything now with

     POST /api/admin/reload-config

   which answers what happened to each: {:claims-parser :tenants
   :identity-provider {:discovery :keys}} with {:status reloaded|failed
   :error} (:identity-provider is nil in login mode), and :new-tenants. A failed reload keeps the last good version.
   Tenants removed from the file are no longer synced; their data stays."
  (:require [clojure.tools.logging :as log]
            [manager-svc.auth :as auth]
            [manager-svc.claims :as claims]
            [manager-svc.errors :as errors]
            [manager-svc.http :as http]
            [manager-svc.platform.api :as api]
            [manager-svc.platform.tenants :as tenants]
            [manager-svc.roles :as roles]))

(defn- configured-names
  [system]
  (set (map :name (tenants/current (:settings system)))))

(defn sync-new-tenants!
  "Syncs the configured tenants that were not configured at the last call;
   the first call only remembers them (the startup sync covers those).
   Returns their names; one that fails to sync is logged and left to the
   next sync, the others still sync."
  [system]
  (let [seen
        (:platform/configured-tenants system)

        names
        (configured-names system)

        [before _]
        (swap-vals! seen (constantly names))

        added
        (when before (sort (remove before names)))]
    (doseq [tenant-name added]
      (try
        (api/sync-tenant! system tenant-name)
        (catch Exception e
          (log/warn e "Sync of new tenant failed" tenant-name))))
    (vec added)))

(defn reload-config!
  [{:keys [settings] :as system}]
  (let [outcomes
        {:claims-parser (claims/reload! settings)
         :tenants (tenants/reload! settings)
         :identity-provider (auth/reload-keys! settings)}]
    (assoc outcomes :new-tenants (sync-new-tenants! system))))

(defn handler
  [{:keys [settings] :as system} {:keys [cookies headers]}]
  (let [identity
        (auth/resolve-identity settings cookies (get headers "authorization"))]
    (cond
      (nil? identity) (errors/fail! 401 "Not authenticated")
      (not (roles/operator? identity)) (errors/forbidden! roles/operators-only)
      :else {:status 200
             :headers {"Content-Type" "application/json"}
             :body (http/write-json (reload-config! system))})))

(def routes
  [{:method :post :path "/api/admin/reload-config" :handler handler}])

(defn worker
  [system]
  {:id :config-reload
   :interval-seconds (max 1 (get-in system [:settings :config-reload-seconds] 10))
   :initial-delay-seconds 0
   :run sync-new-tenants!})
