(ns manager-svc.credentials.edge
  "PUT /api/credentials/{id} (the caller's own value),
   PUT /api/teams/{team-id}/credentials/{id} (a team's value) and
   PUT /api/admin/credentials/{id} (the tenant's value). The plaintext is
   sealed here, bound to the credential and its owner in the caller's
   tenant, so edd and its request log only ever see the sealed value. edd
   opens it to check it against its service and to authorize the change,
   as for any command. A refused change answers its HTTP status with
   {:detail message}."
  (:require [manager-svc.credentials.values :as values]
            [manager-svc.credentials.vault :as vault]
            [manager-svc.edge.api :as edge-api]))

(def ^:private scopes
  {:user {:pattern #"^/api/credentials/([A-Za-z0-9._-]{1,128})$"
          :cmd-id :set-my-credential}
   :team {:pattern #"^/api/teams/([a-z0-9][a-z0-9-]{0,62})/credentials/([A-Za-z0-9._-]{1,128})$"
          :cmd-id :set-team-credential}
   :tenant {:pattern #"^/api/admin/credentials/([A-Za-z0-9._-]{1,128})$"
            :cmd-id :set-tenant-credential}})

(defn- path-parts
  "{:credential-id :team-id} of a path Undertow already decoded, or nil."
  [scope path]
  (when-let [[_ & groups]
             (re-find (get-in scopes [scope :pattern]) path)]
    (if (= :team scope)
      {:team-id (first groups) :credential-id (second groups)}
      {:credential-id (first groups)})))

(defn- read-value
  [request]
  (let [{:keys [secret username]}
        (edge-api/json-object! request "Send {\"secret\": ..., \"username\": ...}")]
    {:secret (str secret)
     :username (some-> username str)}))

(defn sealed-command
  "The command that stores value in scope for identity ({:sub :tenant}),
   carrying only the sealed value. team-id names the team of a :team value."
  ([settings scope credential-id identity value]
   (sealed-command settings scope credential-id identity value nil))
  ([settings scope credential-id identity value team-id]
   (let [owner
         (values/owner scope {:tenant (:tenant identity) :team-id team-id :user (:sub identity)})]
     {:cmd-id (get-in scopes [scope :cmd-id])
      :attrs (cond-> {:credential-id credential-id
                      :sealed (vault/seal settings credential-id owner value)}
               team-id (assoc :team-id team-id))})))

(defn- set-value
  [scope]
  (fn [{:keys [settings]} request]
    (let [[token identity]
          (edge-api/person! settings request)

          {:keys [credential-id team-id]}
          (path-parts scope (:path request))]
      (edge-api/command! settings token
                         [(sealed-command settings scope credential-id identity (read-value request) team-id)]))))

(def routes
  [{:method :put :prefix "/api/credentials/" :match #(path-parts :user (:path %)) :handler (set-value :user)}
   {:method :put :prefix "/api/teams/" :match #(path-parts :team (:path %)) :handler (set-value :team)}
   {:method :put :prefix "/api/admin/credentials/" :match #(path-parts :tenant (:path %)) :handler (set-value :tenant)}])
