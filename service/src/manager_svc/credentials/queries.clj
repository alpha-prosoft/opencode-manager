(ns manager-svc.credentials.queries
  "What people see of credentials: their own (with their teams' and
   tenant's shared values), a team's (its admins) and the tenant's (tenant
   admins). Values never leave; only hints do. Everything is of the
   caller's tenant."
  (:require [edd.core :as edd]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.mcp :as mcp]
            [manager-svc.credentials.required :as required]
            [manager-svc.credentials.spec :as spec]
            [manager-svc.credentials.values :as values]
            [manager-svc.errors :as errors]
            [manager-svc.platform.api :as platform-api]
            [manager-svc.roles :as roles]
            [manager-svc.system :as system]))

(defn relevant
  "Credentials that apply to one of the teams."
  [tenant-spec teams]
  (filterv #(spec/for-teams? (:teams %) teams)
           (spec/credentials tenant-spec)))

(defn- tenant-provides?
  "True when the tenant may provide credential and has a value for it."
  [ctx tenant credential]
  (and (spec/provides? credential :tenant)
       (values/tenant-value? ctx (system/settings ctx) tenant (:id credential))))

(defn- shared-state
  [team-values tenant-provides credential]
  (cond
    (and (spec/provides? credential :team) (contains? team-values (:id credential))) :team
    tenant-provides :tenant
    :else nil))

(defn- team-entries
  [system tenant team-ids]
  (into {} (for [team-id team-ids]
             [team-id (or (platform-api/team system tenant team-id) {:id team-id})])))

(defn status
  "CredentialStatus of every credential relevant to the caller, also those
   only admins provide (:may-provide false). :teams says where the value
   comes from in each of the caller's teams the credential applies to, and
   whether the person must provide their own there. :required-by names who
   requires it of the caller (tenant.json, their teams' team.json). Only
   the teams the caller's claims name require anything of them: a tenant
   admin who isn't a member of a team owes it nothing."
  [ctx caller]
  (let [system
        (system/of ctx)

        tenant
        (:tenant caller)

        teams
        (sort (platform-api/teams-of system caller))

        entries
        (team-entries system tenant teams)

        claimed
        (roles/claimed-team-ids caller tenant)

        member-teams
        (keep entries (filter claimed teams))

        own
        (into {} (map (juxt :credential-id identity)) (values/of-user ctx tenant (:id caller)))

        values-of-team
        (into {} (for [team-id teams]
                   [team-id (set (map :credential-id (values/of-team ctx tenant team-id)))]))

        team-values
        (reduce into #{} (vals values-of-team))]
    (mapv (fn [credential]
            (let [may-provide
                  (spec/personal? credential)

                  mine
                  (when may-provide (get own (:id credential)))

                  tenant-provides
                  (tenant-provides? ctx tenant credential)

                  shared
                  (shared-state team-values tenant-provides credential)

                  required-by
                  (required/required-by credential member-teams)]
              (merge (dissoc credential :teams :required)
                     {:teams (vec (for [team-id teams
                                        :when (spec/for-teams? (:teams credential) [team-id])
                                        :let [state (shared-state (values-of-team team-id) tenant-provides credential)]]
                                    {:team-id team-id
                                     :state (cond mine :own state state :else :missing)
                                     :required (and (contains? claimed team-id)
                                                    (required/required-in? credential (entries team-id)))}))
                      :state (cond mine :own shared shared :else :missing)
                      :may-provide may-provide
                      :required (required/required? required-by)
                      :required-by required-by
                      :personal-only (spec/personal-only? credential)
                      :hint (:hint mine)
                      :updated-at (:updated-at mine)
                      :verified-at (:verified-at mine)})))
          (relevant (spec/spec system tenant) teams))))

(defn missing-required
  "Ids of the credentials required of the person that lack their own value."
  [statuses]
  (vec (for [{:keys [id required state]} statuses
             :when (and required (not= :own state))]
         id)))

(defn pending
  "Credentials to offer at login, the required ones without the person's own
   value first: then those the person may provide and has yet to look at,
   and personal-only ones still missing."
  [statuses seen]
  (let [missing
        (missing-required statuses)

        offered
        (for [{:keys [id state may-provide personal-only]} statuses
              :when (and may-provide
                         (not (some #{id} missing))
                         (or (not (contains? seen id))
                             (and personal-only (= :missing state))))]
          id)]
    (vec (concat missing offered))))

(defn get-my-credentials
  [ctx _query]
  (let [caller
        (roles/caller ctx)

        statuses
        (status ctx caller)]
    {:credentials statuses
     :pending (pending statuses (values/seen ctx (:tenant caller) (auth/user-id ctx)))
     :missing-required (missing-required statuses)}))

(defn get-missing-required-credentials
  "{:team-id :team-name :missing [{:id :name}]}: the credentials team-id
   requires that user has no own value of; the dependency of commands that
   start work in a team."
  [ctx {:keys [tenant team-id user]}]
  (let [system
        (system/of ctx)

        team
        (platform-api/team system tenant team-id)

        own
        (set (map :credential-id (values/of-user ctx tenant user)))]
    {:team-id team-id
     :team-name (or (:name team) team-id)
     :missing (if team
                (mapv #(select-keys % [:id :name]) (required/missing-in (spec/spec system tenant) team own))
                [])}))

(defn used-by
  [tenant-spec credential-id onboarding-secret]
  (let [named?
        #(and (some? %) (= credential-id (name %)))]
    (vec (concat
          (for [connection (:llm-connections tenant-spec)
                :when (named? (:secret connection))]
            (str "LLM connection " (:id connection)))
          (for [server (spec/mcp-servers tenant-spec)
                :when (named? (:credential server))]
            (str "MCP server " (:name server)))
          (for [team (:teams tenant-spec)
                [label used?] [["repository of " (named? (get-in team [:repository :credentials]))]
                               ["webhook of " (named? (get-in team [:notifications :webhook]))]
                               ["granted to " (some named? (:secrets team))]]
                :when used?]
            (str label (:id team)))
          (when (named? onboarding-secret)
            ["onboarding repository"])))))

(defn- credential-names
  [tenant-spec onboarding-secret]
  (let [team-names
        (mapcat (fn [team]
                  (concat (:secrets team)
                          [(get-in team [:repository :credentials])
                           (get-in team [:notifications :webhook])]))
                (:teams tenant-spec))]
    (->> (concat (map :id (spec/credentials tenant-spec))
                 (map :secret (:llm-connections tenant-spec))
                 (map :credential (spec/mcp-servers tenant-spec))
                 team-names
                 [onboarding-secret])
         (remove nil?)
         (map name)
         (into (sorted-set)))))

(defn- onboarding-secret
  [system tenant]
  (some #(when (= tenant (:name %)) (get-in % [:source :credentials]))
        (platform-api/tenants system)))

(defn list-admin-credentials
  [ctx _query]
  (roles/require-tenant-admin! ctx)
  (let [settings
        (system/settings ctx)

        system
        (system/of ctx)

        tenant
        (roles/tenant-of ctx)

        tenant-spec
        (spec/spec system tenant)

        onboarding
        (onboarding-secret system tenant)

        stored
        (into {} (map (juxt :credential-id identity)) (values/of-tenant ctx tenant))

        team-values
        (frequencies (map :credential-id (values/all-teams ctx tenant)))

        personal
        (frequencies (map :credential-id (values/all-people ctx tenant)))

        teams
        (platform-api/teams system tenant)]
    (mapv (fn [credential-id]
            (let [declared
                  (spec/credential tenant-spec credential-id)

                  tenant-row
                  (get stored credential-id)]
              {:id credential-id
               :name (or (:name declared) credential-id)
               :description (or (:description declared) "")
               :kind (or (:kind declared) :token)
               :provided-by (or (:provided-by declared) [:tenant])
               :declared (some? declared)
               :hosts (spec/credential-hosts tenant-spec credential-id)
               :tenant-value (cond
                               tenant-row :admin
                               (values/file-value? settings tenant credential-id) :file
                               :else nil)
               :hint (:hint tenant-row)
               :updated-at (:updated-at tenant-row)
               :updated-by (:updated-by tenant-row)
               :team-values (get team-values credential-id 0)
               :personal-values (get personal credential-id 0)
               :required (true? (:required declared))
               :required-by-teams (vec (sort (for [team teams
                                                   :when (required/by-team? team credential-id)]
                                               (:id team))))
               :used-by (used-by tenant-spec credential-id onboarding)}))
          (credential-names tenant-spec onboarding))))

(defn- team-credential-view
  [ctx tenant credential row]
  {:id (:id credential)
   :name (:name credential)
   :description (:description credential)
   :kind (:kind credential)
   :provided-by (:provided-by credential)
   :hosts (:hosts credential)
   :set (some? row)
   :value (when row (select-keys row [:hint :updated-at :updated-by]))
   :tenant-set (tenant-provides? ctx tenant credential)})

(defn- member-view
  "What a member reads: which credentials the team provides, never hints."
  [credential row]
  (assoc (select-keys credential [:id :name :description :provided-by])
         :set (some? row)))

(defn- team-visible!
  "The caller's tenant, team entry and whether they administer the team;
   fails unless they are its member or admin."
  [ctx team-id]
  (let [system
        (system/of ctx)

        tenant
        (roles/tenant-of ctx)

        caller
        (roles/caller ctx)

        admin?
        (or (auth/platform? ctx) (roles/team-admin? caller tenant team-id))

        team
        (or (platform-api/team system tenant team-id)
            (errors/not-found! "Team not found"))]
    (when-not (or admin? (roles/member? caller tenant team-id))
      (errors/forbidden! (str "Only members of team " team-id " can see its credentials")))
    {:system system :tenant tenant :team team :admin? admin?}))

(defn list-team-credentials
  "Credentials the team may provide and whether it has: team admins see the
   team's values' hints, members only which are set."
  [ctx {:keys [team-id]}]
  (let [{:keys [system tenant admin?]}
        (team-visible! ctx team-id)

        stored
        (into {} (map (juxt :credential-id identity)) (values/of-team ctx tenant team-id))]
    (vec (for [credential (spec/credentials (spec/spec system tenant))
               :when (and (spec/provides? credential :team)
                          (spec/for-teams? (:teams credential) [team-id]))
               :let [row (get stored (:id credential))]]
           (if admin?
             (team-credential-view ctx tenant credential row)
             (member-view credential row))))))

(defn list-required-credentials
  "Credentials each member of the team must provide themselves, and whether
   tenant.json (:tenant) or the team's team.json (:team) requires them."
  [ctx {:keys [team-id]}]
  (let [{:keys [system tenant team]}
        (team-visible! ctx team-id)]
    (vec (for [credential (spec/credentials (spec/spec system tenant))
               :when (required/required-in? credential team)]
           (assoc (select-keys credential [:id :name :description :kind])
                  :required-by {:tenant (true? (:required credential))
                                :team (required/by-team? team (:id credential))})))))

(defn- harnesses-by-server
  [system tenant]
  (reduce (fn [acc [team-id definition]]
            (reduce (fn [acc server-id]
                      (update acc (name server-id) (fnil conj []) (str team-id "/" (:id definition))))
                    acc
                    (get-in definition [:spec :mcp])))
          {}
          (for [team (platform-api/teams system tenant)
                definition (platform-api/definitions system tenant (:id team) :harnesses)]
            [(:id team) definition])))

(defn- credential-ready?
  [ctx tenant tenant-spec credential-id]
  (or (nil? credential-id)
      (spec/personal-only? (spec/credential tenant-spec credential-id))
      (values/tenant-value? ctx (system/settings ctx) tenant credential-id)))

(defn list-mcp-servers
  [ctx _query]
  (roles/require-tenant-admin! ctx)
  (let [system
        (system/of ctx)

        tenant
        (roles/tenant-of ctx)

        tenant-spec
        (spec/spec system tenant)

        used
        (harnesses-by-server system tenant)

        servers
        (spec/mcp-servers tenant-spec)

        reachability
        (doall (pmap mcp/ping servers))]
    (mapv (fn [server {:keys [reachable detail]}]
            (assoc server
                   :reachable reachable
                   :detail detail
                   :harnesses (get used (:id server) [])
                   :credential-ready (credential-ready? ctx tenant tenant-spec (:credential server))))
          servers
          reachability)))

(defn get-stored-credential
  "The sealed value record, for the facade."
  [ctx {:keys [credential-id owner]}]
  (some-> (values/stored ctx credential-id owner)
          (select-keys [:credential-id :owner :sealed])))

(def ^:private users
  {:role [:user]})

(defn register
  [ctx]
  (-> ctx
      (edd/reg-query :get-my-credentials get-my-credentials :auth users)
      (edd/reg-query :list-admin-credentials list-admin-credentials :auth users)
      (edd/reg-query :list-team-credentials list-team-credentials
                     :consumes [:map [:team-id :string]]
                     :auth users)
      (edd/reg-query :list-required-credentials list-required-credentials
                     :consumes [:map [:team-id :string]]
                     :auth users)
      (edd/reg-query :list-mcp-servers list-mcp-servers :auth users)
      (edd/reg-query :get-missing-required-credentials get-missing-required-credentials
                     :consumes [:map [:tenant :string] [:team-id :string] [:user :string]]
                     :auth {:role [:non-interactive]})
      (edd/reg-query :get-stored-credential get-stored-credential
                     :consumes [:map [:credential-id :string] [:owner :string]]
                     :auth {:role [:non-interactive]})))
