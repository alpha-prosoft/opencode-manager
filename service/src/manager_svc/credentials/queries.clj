(ns manager-svc.credentials.queries
  (:require [edd.core :as edd]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.mcp :as mcp]
            [manager-svc.credentials.spec :as spec]
            [manager-svc.credentials.values :as values]
            [manager-svc.credentials.vault :as vault]
            [manager-svc.platform.api :as platform-api]
            [manager-svc.system :as system]))

(defn relevant
  "Personal credentials that apply to one of the teams."
  [platform-spec teams]
  (filterv #(and (spec/personal? %) (spec/for-teams? (:teams %) teams))
           (spec/credentials platform-spec)))

(defn- has-platform-value?
  [ctx credential-id]
  (some? (values/platform-value (system/settings ctx) (values/ctx-lookup ctx (system/settings ctx)) credential-id)))

(defn status
  "CredentialStatus of every credential relevant to the user."
  [ctx user]
  (let [system
        (system/of ctx)

        platform-spec
        (spec/spec system)

        own
        (into {} (map (juxt :credential-id identity)) (values/of-owner ctx user))]
    (mapv (fn [credential]
            (let [mine
                  (get own (:id credential))

                  shared
                  (and (= :platform-or-user (:provided-by credential))
                       (has-platform-value? ctx (:id credential)))]
              (merge (dissoc credential :teams)
                     {:state (cond mine :own shared :platform :else :missing)
                      :required (= :user (:provided-by credential))
                      :hint (:hint mine)
                      :updated-at (:updated-at mine)
                      :verified-at (:verified-at mine)})))
          (relevant platform-spec (platform-api/teams-of system user)))))

(defn pending
  [statuses seen]
  (vec (for [{:keys [id required state]} statuses
             :when (or (not (contains? seen id))
                       (and required (= :missing state)))]
         id)))

(defn get-my-credentials
  [ctx _query]
  (let [user
        (auth/user-id ctx)

        statuses
        (status ctx user)]
    {:credentials statuses
     :pending (pending statuses (values/seen ctx user))}))

(defn used-by
  [platform-spec credential-id onboarding-secret]
  (let [named?
        #(and (some? %) (= credential-id (name %)))]
    (vec (concat
          (for [connection (:llm-connections platform-spec)
                :when (named? (:secret connection))]
            (str "LLM connection " (:id connection)))
          (for [server (spec/mcp-servers platform-spec)
                :when (named? (:credential server))]
            (str "MCP server " (:name server)))
          (for [team (:teams platform-spec)
                [label used?] [["repository of " (named? (get-in team [:repository :credentials]))]
                               ["webhook of " (named? (get-in team [:notifications :webhook]))]
                               ["granted to " (some named? (:secrets team))]]
                :when used?]
            (str label (:id team)))
          (when (named? onboarding-secret)
            ["onboarding repository"])))))

(defn- credential-names
  [platform-spec onboarding-secret]
  (let [team-names
        (mapcat (fn [team]
                  (concat (:secrets team)
                          [(get-in team [:repository :credentials])
                           (get-in team [:notifications :webhook])]))
                (:teams platform-spec))]
    (->> (concat (map :id (spec/credentials platform-spec))
                 (map :secret (:llm-connections platform-spec))
                 (map :credential (spec/mcp-servers platform-spec))
                 team-names
                 [onboarding-secret])
         (remove nil?)
         (map name)
         (into (sorted-set)))))

(defn list-admin-credentials
  [ctx _query]
  (platform-api/require-admin! ctx)
  (let [settings
        (system/settings ctx)

        platform-spec
        (spec/spec (system/of ctx))

        onboarding-secret
        (:onboarding-credentials settings)

        rows
        (values/all-stored ctx)

        stored
        (into {}
              (comp (filter #(= vault/platform-owner (:owner %)))
                    (map (juxt :credential-id identity)))
              rows)

        personal
        (frequencies (map :credential-id (remove #(= vault/platform-owner (:owner %)) rows)))]
    (mapv (fn [credential-id]
            (let [declared
                  (spec/credential platform-spec credential-id)

                  platform-row
                  (get stored credential-id)]
              {:id credential-id
               :name (or (:name declared) credential-id)
               :description (or (:description declared) "")
               :kind (or (:kind declared) :token)
               :provided-by (or (:provided-by declared) :platform)
               :declared (some? declared)
               :hosts (spec/credential-hosts platform-spec credential-id)
               :platform-value (cond
                                 platform-row :admin
                                 (values/file-value settings credential-id) :file
                                 :else nil)
               :hint (:hint platform-row)
               :updated-at (:updated-at platform-row)
               :updated-by (:updated-by platform-row)
               :personal-values (get personal credential-id 0)
               :used-by (used-by platform-spec credential-id onboarding-secret)}))
          (credential-names platform-spec onboarding-secret))))

(defn- harnesses-by-server
  [system platform-spec]
  (reduce (fn [acc [team-id definition]]
            (reduce (fn [acc server-id]
                      (update acc (name server-id) (fnil conj []) (str team-id "/" (:id definition))))
                    acc
                    (get-in definition [:spec :mcp])))
          {}
          (for [team (:teams platform-spec)
                definition (platform-api/definitions system (:id team) :harnesses)]
            [(:id team) definition])))

(defn- credential-ready?
  [ctx platform-spec credential-id]
  (or (nil? credential-id)
      (= :user (:provided-by (spec/credential platform-spec credential-id)))
      (has-platform-value? ctx credential-id)))

(defn list-mcp-servers
  [ctx _query]
  (platform-api/require-admin! ctx)
  (let [system
        (system/of ctx)

        platform-spec
        (spec/spec system)

        used
        (harnesses-by-server system platform-spec)

        servers
        (spec/mcp-servers platform-spec)

        reachability
        (doall (pmap mcp/ping servers))]
    (mapv (fn [server {:keys [reachable detail]}]
            (assoc server
                   :reachable reachable
                   :detail detail
                   :harnesses (get used (:id server) [])
                   :credential-ready (credential-ready? ctx platform-spec (:credential server))))
          servers
          reachability)))

(defn get-stored-credential
  "The sealed value record, for the facade."
  [ctx {:keys [credential-id owner]}]
  (some-> (values/stored ctx credential-id owner)
          (select-keys [:credential-id :owner :sealed])))

(defn register
  [ctx]
  (let [users
        {:role [:user]}

        with-mine
        (edd/reg-query ctx :get-my-credentials get-my-credentials
                       :auth users)

        with-admin
        (edd/reg-query with-mine :list-admin-credentials list-admin-credentials
                       :auth users)

        with-servers
        (edd/reg-query with-admin :list-mcp-servers list-mcp-servers
                       :auth users)]
    (edd/reg-query with-servers :get-stored-credential get-stored-credential
                   :consumes [:map [:credential-id :string] [:owner :string]]
                   :auth {:role [:non-interactive]})))
