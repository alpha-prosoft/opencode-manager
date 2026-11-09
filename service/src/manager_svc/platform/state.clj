(ns manager-svc.platform.state
  "Registry state in aggregates: the platform singleton (onboarding spec,
   sync status, sync requests) and one aggregate per team (spec, members,
   definitions, commit, status). The in-memory cache (:platform/registry)
   mirrors them for the facade and is rebuilt from them."
  (:require [edd.common :as common]
            [edd.core :as edd]
            [lambda.uuid :as uuid]
            [manager-svc.auth :as auth]
            [manager-svc.errors :as errors]
            [manager-svc.platform.registry :as registry]
            [manager-svc.system :as system]
            [manager-svc.time :as time]))

(def platform-id
  (uuid/named "platform:registry"))

(defn team-aggregate-id
  [team-id]
  (uuid/named (str "team:" team-id)))

(defn cache
  [system]
  (:platform/registry system))

(defn snapshot
  "Current cache value: {:platform {...} :teams {team-id team}}."
  [system]
  (if-let [registry
           (cache system)]
    @registry
    {}))

(defn platform-admin?
  [system username]
  (boolean (some #{username} (get-in (snapshot system) [:platform :spec :admins]))))

(defn- require-admin!
  "Passes for the platform itself and platform administrators."
  [ctx]
  (when-not (or (auth/platform? ctx)
                (platform-admin? (system/of ctx) (auth/user-id ctx)))
    (errors/forbidden! "Platform administrators only")))

(defn sync-platform
  [ctx _cmd]
  (require-admin! ctx)
  {:event-id :platform-sync-requested
   :attrs {:requested-at (time/now)
           :requested-by (auth/user-id ctx)}})

(defn record-platform-sync
  [_ctx {:keys [attrs]}]
  (if (:error attrs)
    {:event-id :platform-sync-failed
     :attrs (select-keys attrs [:error :synced-at :handled-request])}
    {:event-id :platform-synced
     :attrs (select-keys attrs [:spec :commit :synced-at :team-ids :handled-request])}))

(defn record-team-sync
  [_ctx {:keys [attrs]}]
  {:event-id :team-synced
   :attrs (select-keys attrs [:team-id :spec :commit :synced-at :status :error :members :definitions])})

(defn- handled
  [aggregate attrs]
  (if (contains? attrs :handled-request)
    (assoc aggregate :handled-request (:handled-request attrs))
    aggregate))

(defn platform-synced
  [aggregate {:keys [attrs]}]
  (handled (assoc aggregate
                  :spec (:spec attrs)
                  :commit (:commit attrs)
                  :synced-at (:synced-at attrs)
                  :team-ids (vec (:team-ids attrs))
                  :error nil)
           attrs))

(defn platform-sync-failed
  [aggregate {:keys [attrs]}]
  (handled (assoc aggregate
                  :error (:error attrs)
                  :synced-at (:synced-at attrs))
           attrs))

(defn platform-sync-requested
  [aggregate {:keys [attrs]}]
  (assoc aggregate
         :requested-at (:requested-at attrs)
         :requested-by (:requested-by attrs)))

(defn team-synced
  "A failed sync keeps the last good commit and definitions."
  [aggregate {:keys [attrs]}]
  (let [updated
        (assoc aggregate
               :team-id (:team-id attrs)
               :spec (:spec attrs)
               :commit (or (:commit attrs) (:commit aggregate))
               :synced-at (:synced-at attrs)
               :status (:status attrs)
               :error (:error attrs)
               :members (vec (:members attrs)))]
    (if (:error attrs)
      (assoc updated :definitions (vec (:definitions aggregate)))
      (assoc updated :definitions (vec (:definitions attrs))))))

(defn- get-aggregate
  [ctx id]
  (common/get-by-id ctx {:id id}))

(defn get-registry
  "The platform aggregate and its teams' aggregates, for the cache."
  [ctx _query]
  (let [platform
        (get-aggregate ctx platform-id)]
    {:platform platform
     :teams (vec (keep #(get-aggregate ctx (team-aggregate-id %)) (:team-ids platform)))}))

(defn get-platform-state
  "When a sync was requested and handled. Never the aggregate itself: edd
   answers a query result holding :error (a failed sync) as an error."
  [ctx _query]
  (select-keys (get-aggregate ctx platform-id) [:requested-at :handled-request]))

(defn- id-of-team
  [_ctx cmd]
  (team-aggregate-id (get-in cmd [:attrs :team-id])))

(def ^:private platform-only
  {:role [:non-interactive]})

(defn register
  [ctx]
  (-> ctx
      (edd/reg-cmd :sync-platform sync-platform
                   :id-fn (constantly platform-id)
                   :auth {:role [:user :non-interactive]})
      (edd/reg-cmd :record-platform-sync record-platform-sync
                   :id-fn (constantly platform-id)
                   :auth platform-only)
      (edd/reg-cmd :record-team-sync record-team-sync
                   :id-fn id-of-team
                   :auth platform-only)
      (edd/reg-event :platform-synced platform-synced)
      (edd/reg-event :platform-sync-failed platform-sync-failed)
      (edd/reg-event :platform-sync-requested platform-sync-requested)
      (edd/reg-event :team-synced team-synced)
      (edd/reg-query :get-registry get-registry
                     :auth platform-only)
      (edd/reg-query :get-platform-state get-platform-state
                     :auth platform-only)))

(defn- normalize-member
  [{:keys [username role]}]
  {:username (if (keyword? username) (name username) (str username))
   :role (keyword role)})

(defn- with-validity
  "Definitions with :valid, sorted by kind and id once, when cached."
  [definitions]
  (vec (sort-by (juxt :kind :id)
                (map (fn [item]
                       (registry/with-validity (update item :kind keyword)))
                     definitions))))

(defn cache-value
  "Cache value from the get-registry result."
  [{:keys [platform teams]}]
  {:loaded true
   :platform (select-keys platform [:spec :commit :synced-at :error :team-ids :requested-at :handled-request])
   :teams (into {}
                (map (fn [team]
                       [(:team-id team)
                        (-> (select-keys team [:team-id :spec :commit :synced-at :status :error])
                            (update :status keyword)
                            (assoc :members (mapv normalize-member (:members team))
                                   :definitions (with-validity (:definitions team))))]))
                teams)})

(defn load!
  [system registry-result]
  (when-let [registry
             (cache system)]
    (reset! registry (cache-value registry-result))))
