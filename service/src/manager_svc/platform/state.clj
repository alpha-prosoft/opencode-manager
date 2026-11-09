(ns manager-svc.platform.state
  "Registry state in the entity aggregates (manager-svc.directory): the
   platform's (registered tenants), one per tenant (\"/tenant/<tenant>\":
   tenant.json, sync status, sync requests) and one per team
   (\"/team/<tenant>/<team>\": entry, team.json, definitions, commit,
   status). The in-memory cache (:platform/registry) mirrors them for the
   facade and is rebuilt from them:

     {:loaded true
      :registered [{:name :source}]
      :tenants {\"acme\" {:name :spec :commit :synced-at :error :team-ids
                         :requested-at :handled-request
                         :teams {\"demo\" {:team-id :tenant :spec :config ...}}}}}"
  (:require [edd.core :as edd]
            [edd.el.ctx :as el-ctx]
            [manager-svc.auth :as auth]
            [manager-svc.client :as client]
            [manager-svc.directory :as directory]
            [manager-svc.errors :as errors]
            [manager-svc.platform.registry :as registry]
            [manager-svc.platform.tenants :as tenants]
            [manager-svc.roles :as roles]
            [manager-svc.system :as system]
            [manager-svc.time :as time]))

(defn cache
  [system]
  (:platform/registry system))

(defn snapshot
  [system]
  (if-let [registry
           (cache system)]
    @registry
    {}))

(defn known-tenants
  "[{:name :source :origin :config|:registered}]: the operator's
   configuration, then registered tenants (by default those in the cache)."
  ([system]
   (known-tenants system (:registered (snapshot system))))
  ([system registered]
   (let [configured
         (mapv #(assoc % :origin :config) (tenants/current (:settings system)))]
     (reduce (fn [known tenant]
               (if (some #(= (:name tenant) (:name %)) known)
                 known
                 (conj known (assoc (select-keys tenant [:name :source]) :origin :registered))))
             configured
             registered))))

(defn known-tenant
  [system tenant-name]
  (some #(when (= tenant-name (:name %)) %) (known-tenants system)))

(defn current-tenants
  "Known tenants including those registered since the cache was loaded."
  [ctx]
  (let [system
        (system/of ctx)]
    (known-tenants system (concat (:registered (snapshot system))
                                  (:registered (directory/entity ctx directory/platform))))))

(defn- current-tenant?
  [ctx tenant-name]
  (boolean (some #(= tenant-name (:name %)) (current-tenants ctx))))

(defn- current
  [ctx]
  (el-ctx/get-aggregate ctx))

(defn register-tenant
  [ctx {:keys [attrs]}]
  (roles/require-operator! ctx)
  (let [{:keys [value errors]}
        (tenants/tenant {:name (:name attrs) :onboarding (:onboarding attrs)})]
    (when (seq errors)
      (errors/invalid! (first errors)))
    (when (or (known-tenant (system/of ctx) (:name value))
              (some #(= (:name value) (:name %)) (:registered (current ctx))))
      (errors/fail! 409 (str "Tenant " (:name value) " already exists")))
    (directory/with-creation (current ctx) directory/platform
      [{:event-id :tenant-registered
        :attrs {:name (:name value)
                :source (:source value)
                :registered-by (auth/user-id ctx)
                :registered-at (time/now)}}])))

(defn- requested-tenant
  [ctx cmd]
  (or (get-in cmd [:attrs :tenant]) (roles/tenant-of ctx)))

(def ^:private unknown-tenant
  "Unknown tenant")

(defn sync-tenant
  "A tenant admin asks to sync their tenant; an operator any tenant.
   Another tenant does not exist for a tenant admin."
  [ctx cmd]
  (let [tenant-name
        (requested-tenant ctx cmd)]
    (when-not (roles/operator? (roles/caller ctx))
      (roles/require-tenant! ctx tenant-name unknown-tenant)
      (roles/require-tenant-admin! ctx tenant-name))
    (when-not (current-tenant? ctx tenant-name)
      (errors/not-found! unknown-tenant))
    (directory/with-creation (current ctx) (directory/tenant tenant-name)
      [{:event-id :tenant-sync-requested
        :attrs {:tenant tenant-name
                :requested-at (time/now)
                :requested-by (auth/user-id ctx)}}])))

(defn record-tenant-sync
  [ctx {:keys [attrs]}]
  (directory/with-creation
    (current ctx) (:identity attrs)
    [(if (:error attrs)
       {:event-id :tenant-sync-failed
        :attrs (select-keys attrs [:tenant :error :synced-at :handled-request])}
       {:event-id :tenant-synced
        :attrs (select-keys attrs [:tenant :spec :commit :synced-at :team-ids :handled-request])})]))

(defn record-team-sync
  [ctx {:keys [attrs]}]
  (directory/with-creation
    (current ctx) (:identity attrs)
    [{:event-id :team-synced
      :attrs (select-keys attrs [:tenant :team-id :spec :config :commit :synced-at :status :error :definitions])}]))

(defn- first-sync-requested
  "A registered tenant is synced right away."
  [_ctx {:keys [id attrs]}]
  {:commands [{:cmd-id :sync-tenant :id id :attrs {:tenant (:name attrs)}}]
   :meta client/platform-meta})

(defn- handled
  [aggregate attrs]
  (if (contains? attrs :handled-request)
    (assoc aggregate :handled-request (:handled-request attrs))
    aggregate))

(defn tenant-registered
  [aggregate {:keys [attrs]}]
  (update aggregate :registered (fnil conj []) (select-keys attrs [:name :source :registered-by :registered-at])))

(defn tenant-synced
  [aggregate {:keys [attrs]}]
  (handled (assoc aggregate
                  :tenant (:tenant attrs)
                  :spec (:spec attrs)
                  :commit (:commit attrs)
                  :synced-at (:synced-at attrs)
                  :team-ids (vec (:team-ids attrs))
                  :error nil)
           attrs))

(defn tenant-sync-failed
  [aggregate {:keys [attrs]}]
  (handled (assoc aggregate
                  :tenant (:tenant attrs)
                  :error (:error attrs)
                  :synced-at (:synced-at attrs))
           attrs))

(defn tenant-sync-requested
  [aggregate {:keys [attrs]}]
  (assoc aggregate
         :tenant (:tenant attrs)
         :requested-at (:requested-at attrs)
         :requested-by (:requested-by attrs)))

(defn team-synced
  "A failed sync keeps the last good commit, team.json and definitions."
  [aggregate {:keys [attrs]}]
  (let [updated
        (assoc aggregate
               :tenant (:tenant attrs)
               :team-id (:team-id attrs)
               :spec (:spec attrs)
               :commit (or (:commit attrs) (:commit aggregate))
               :synced-at (:synced-at attrs)
               :status (:status attrs)
               :error (:error attrs))]
    (if (:error attrs)
      (assoc updated
             :definitions (vec (:definitions aggregate))
             :config (:config aggregate))
      (assoc updated
             :definitions (vec (:definitions attrs))
             :config (:config attrs)))))

(def ^:private tenant-keys
  [:tenant :spec :commit :synced-at :error :team-ids :requested-at :handled-request])

(def ^:private team-keys
  [:tenant :team-id :spec :config :commit :synced-at :status :error :definitions])

(defn- tenant-names
  [ctx platform]
  (vec (distinct (concat (directory/tenants ctx)
                         (map :name (:registered platform))
                         (map :name (known-tenants (system/of ctx)))))))

(defn get-registry
  "Registered tenants and every tenant's and team's aggregate, for the cache."
  [ctx _query]
  (let [platform
        (directory/entity ctx directory/platform)]
    {:registered (mapv #(select-keys % [:name :source]) (:registered platform))
     :tenants (vec (for [tenant-name (tenant-names ctx platform)
                         :let [tenant (directory/entity ctx (directory/tenant tenant-name))]
                         :when tenant]
                     (assoc (select-keys tenant tenant-keys)
                            :name tenant-name
                            :teams (vec (keep #(some-> (directory/entity ctx (directory/team tenant-name %))
                                                       (select-keys team-keys))
                                              (:team-ids tenant))))))}))

(defn get-sync-requests
  "When each tenant's sync was requested and handled. Never the aggregate
   itself: edd answers a query result holding :error (a failed sync) as an
   error."
  [ctx _query]
  (vec (for [tenant-name (tenant-names ctx (directory/entity ctx directory/platform))
             :let [tenant (directory/entity ctx (directory/tenant tenant-name))]
             :when (:requested-at tenant)]
         {:tenant tenant-name
          :requested-at (:requested-at tenant)
          :handled-request (:handled-request tenant)})))

(defn- platform-id
  [ctx _cmd]
  (directory/entity-id ctx {:attrs {:identity directory/platform}}))

(defn- requested-tenant-id
  [ctx cmd]
  (directory/entity-id ctx {:attrs {:identity (some-> (requested-tenant ctx cmd) directory/tenant)}}))

(defn register
  [ctx]
  (-> ctx
      (edd/reg-cmd :register-tenant register-tenant
                   :id-fn platform-id
                   :consumes [:map [:attrs [:map [:name string?] [:onboarding [:or string? map?]]]]]
                   :auth roles/people)
      (edd/reg-cmd :sync-tenant sync-tenant
                   :id-fn requested-tenant-id
                   :consumes [:map [:attrs {:optional true} [:map [:tenant {:optional true} string?]]]]
                   :auth roles/people-and-platform)
      (edd/reg-cmd :record-tenant-sync record-tenant-sync
                   :id-fn directory/entity-id
                   :consumes [:map [:attrs [:map [:identity string?] [:tenant string?]]]]
                   :auth roles/platform-only)
      (edd/reg-cmd :record-team-sync record-team-sync
                   :id-fn directory/entity-id
                   :consumes [:map [:attrs [:map [:identity string?] [:tenant string?] [:team-id string?]]]]
                   :auth roles/platform-only)
      (edd/reg-event :tenant-registered tenant-registered)
      (edd/reg-event :tenant-synced tenant-synced)
      (edd/reg-event :tenant-sync-failed tenant-sync-failed)
      (edd/reg-event :tenant-sync-requested tenant-sync-requested)
      (edd/reg-event :team-synced team-synced)
      (edd/reg-event-fx :tenant-registered first-sync-requested)
      (edd/reg-query :get-registry get-registry
                     :auth roles/platform-only)
      (edd/reg-query :get-sync-requests get-sync-requests
                     :auth roles/platform-only)))

(defn- with-validity
  "Definitions with :valid, sorted by kind and id once, when cached."
  [definitions]
  (vec (sort-by (juxt :kind :id)
                (map (fn [item]
                       (registry/with-validity (update item :kind keyword)))
                     definitions))))

(defn- team-value
  [team]
  (-> (select-keys team [:tenant :team-id :spec :config :commit :synced-at :status :error])
      (update :status keyword)
      (assoc :definitions (with-validity (:definitions team)))))

(defn cache-value
  "Cache value from the get-registry result."
  [{:keys [registered tenants]}]
  {:loaded true
   :registered (vec registered)
   :tenants (into {}
                  (map (fn [tenant]
                         [(:name tenant)
                          (assoc (select-keys tenant (conj tenant-keys :name))
                                 :teams (into {} (map (juxt :team-id team-value)) (:teams tenant)))]))
                  tenants)})

(defn load!
  [system registry-result]
  (when-let [registry
             (cache system)]
    (reset! registry (cache-value registry-result))))
