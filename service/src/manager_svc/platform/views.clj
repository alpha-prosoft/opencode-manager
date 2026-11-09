(ns manager-svc.platform.views
  "Queries the UI calls for the registry, teams, harnesses and the admin
   console. Shapes follow manager/ui/src/api.ts with kebab-case keys.
   Numbers owned by other domains come from their facades; when a facade
   fails the views show zeros instead of failing."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [edd.core :as edd]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.errors :as errors]
            [manager-svc.observability.api :as observability]
            [manager-svc.platform.api :as api]
            [manager-svc.platform.catalog :as catalog]
            [manager-svc.platform.spec :as spec]
            [manager-svc.runs.api :as runs]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.system :as system]
            [manager-svc.time :as time]))

(def usage-window-days 7)

(def user-activity-days 30)

(def empty-usage
  {:calls 0
   :prompt-tokens 0
   :completion-tokens 0
   :cached-tokens 0
   :errors 0
   :avg-latency-ms 0})

(defn- guarded
  [default f & args]
  (try
    (let [value (apply f args)]
      (if (nil? value) default value))
    (catch Exception error
      (log/debug "Facade call failed, using a default" (ex-message error))
      default)))

(defn- window-start
  "Start of the window of days UTC days that ends today."
  [days]
  (time/start-of-day (dec days)))

(defn- sync-status
  "api/status with the sync failure as :error-message: edd answers a query
   result holding :error as an error."
  [system]
  (let [status
        (api/status system)]
    (assoc (dissoc status :error) :error-message (:error status))))

(defn get-me
  [ctx _query]
  (let [system
        (system/of ctx)

        username
        (auth/user-id ctx)]
    {:sub username
     :email (get-in ctx [:meta :user :email])
     :name (get-in ctx [:meta :user :name])
     :platform-admin (api/platform-admin? system username)
     :teams (mapv (fn [team-id] {:id team-id :role (api/role system username team-id)})
                  (sort (api/teams-of system username)))}))

(defn get-personas
  [ctx _query]
  (let [platform
        (api/platform-spec (system/of ctx))

        admins
        (into {} (map (fn [username] [username ["Platform admin"]])) (:admins platform))

        people
        (reduce (fn [acc team]
                  (let [with-admins
                        (reduce #(update %1 %2 (fnil conj []) (str "Team admin · " (:name team)))
                                acc
                                (:admins team))]
                    (reduce #(update %1 %2 (fnil conj []) (str "Member · " (:name team)))
                            with-admins
                            (remove (set (:admins team)) (:members team)))))
                admins
                (:teams platform))]
    (mapv (fn [[username roles]] {:username username :roles roles})
          (sort-by key people))))

(defn get-platform
  [ctx _query]
  (let [system
        (system/of ctx)

        platform
        (api/platform-spec system)]
    (merge (sync-status system)
           {:teams (count (:teams platform))
            :llm-connections (mapv #(select-keys % [:id :type :models]) (:llm-connections platform))
            :allowed-images (:allowed-images platform)})))

(defn list-activities
  [_ctx _query]
  @catalog/activities)

(defn get-schemas
  [_ctx _query]
  @catalog/json-schemas)

(defn- team-summary
  [system team username]
  (let [definitions
        (api/definitions system (:id team))]
    {:id (:id team)
     :name (:name team)
     :description (:description team)
     :repository (select-keys (:repository team) [:type :url :location :branch :path])
     :commit (:commit team)
     :synced-at (:synced-at team)
     :status (:status team)
     :error-message (:error team)
     :role (api/role system username (:id team))
     :counts (into {}
                   (map (fn [kind] [kind (count (filter #(= kind (:kind %)) definitions))]))
                   [:agents :harnesses :workflows])
     :invalid (count (remove :valid definitions))}))

(defn list-teams
  [ctx _query]
  (let [system
        (system/of ctx)

        username
        (auth/user-id ctx)

        mine
        (api/teams-of system username)]
    (vec (for [team (api/teams system)
               :when (contains? mine (:id team))]
           (team-summary system team username)))))

(defn- visible-team!
  [system username team-id]
  (let [team
        (api/team system team-id)]
    (when (or (nil? team) (nil? (api/role system username team-id)))
      (errors/not-found! "Team not found"))
    team))

(defn- definition-view
  "A team definition; a valid workflow also carries the JSON Schema of the inputs a run of it takes."
  [{:keys [kind spec valid] :as definition}]
  (cond-> (select-keys definition [:kind :id :source :spec :errors :valid])
    (and valid (= :workflows kind))
    (assoc :inputs-schema (catalog/inputs-schema (:activity spec) (:options spec)))))

(defn get-team
  [ctx {:keys [team-id]}]
  (let [system
        (system/of ctx)

        username
        (auth/user-id ctx)

        team
        (visible-team! system username team-id)

        usage
        (guarded {} observability/team-usage system team-id (window-start usage-window-days))]
    (merge (team-summary system team username)
           {:members (:members team)
            :admins (:admins team)
            :secrets (:secrets team)
            :definitions (mapv definition-view (api/definitions system team-id))
            :usage (merge empty-usage (:usage usage))
            :budget {:daily-tokens (get-in team [:budget :daily-tokens])
                     :used-today (or (:used-today usage) 0)}
            :usage-by-model (vec (:by-model usage))})))

(defn- readiness
  [system platform credential-id username]
  (let [declared
        (spec/credential platform credential-id)

        resolved
        (guarded nil credentials/resolve-credential system credential-id username)]
    {:id credential-id
     :name (or (:name declared) credential-id)
     :provided-by (or (:provided-by declared) :platform)
     :source (some-> (:source resolved) keyword)}))

(defn- skill-name
  [path]
  (last (string/split (string/replace path #"/+$" "") #"/")))

(defn- harness-card
  [system platform team-id team-name workflows username definition]
  (let [harness
        (or (:spec definition) {})

        servers
        (keep #(spec/mcp-server-of platform %) (:mcp harness))

        credential-ids
        (sort (set (keep :credential servers)))

        credential-states
        (mapv #(readiness system platform % username) credential-ids)]
    {:team-id team-id
     :team-name team-name
     :id (:id definition)
     :valid (:valid definition)
     :errors (:errors definition)
     :description (:description harness "")
     :network (:network harness :none)
     :models (get-in harness [:llm :models] [])
     :skills (mapv skill-name (:skills harness))
     :mcp-servers (mapv #(select-keys % [:id :name :description :tools]) servers)
     :credentials credential-states
     :ready (boolean (and (:valid definition) (every? :source credential-states)))
     :workflows (vec (for [workflow workflows
                           :when (= (:id definition) (get-in workflow [:spec :harness]))]
                       {:id (:id workflow) :name (get-in workflow [:spec :name])}))}))

(defn list-harnesses
  [ctx _query]
  (let [system
        (system/of ctx)

        username
        (auth/user-id ctx)

        platform
        (api/platform-spec system)]
    (vec (for [team-id (sort (api/teams-of system username))
               :let [team (api/team system team-id)
                     workflows (api/workflows system team-id)]
               definition (api/definitions system team-id :harnesses)]
           (harness-card system platform team-id (or (:name team) team-id) workflows username definition)))))

(defn- users-of
  [platform]
  (set (concat (:admins platform) (mapcat #(concat (:members %) (:admins %)) (:teams platform)))))

(defn get-admin-overview
  [ctx _query]
  (api/require-admin! ctx)
  (let [system
        (system/of ctx)

        platform
        (api/platform-spec system)

        team-ids
        (mapv :id (:teams platform))

        rows
        (if (empty? team-ids)
          []
          (guarded [] observability/usage-rows system team-ids (time/start-of-day)))

        costs
        (keep :cost rows)

        running
        (count (guarded [] sessions/running-session-ids system))]
    {:status (select-keys (sync-status system) [:commit :synced-at :error-message])
     :teams (count (:teams platform))
     :users (count (users-of platform))
     :sessions {:total (guarded running sessions/session-count system)
                :running running}
     :runs-today (guarded {} runs/run-counts-by-status system (time/start-of-day))
     :tokens-today (reduce + 0 (map #(+ (or (:prompt-tokens %) 0) (or (:completion-tokens %) 0)) rows))
     :cost-today (when (seq costs) (/ (Math/round (* (reduce + costs) 10000.0)) 10000.0))}))

(defn list-admin-users
  [ctx _query]
  (api/require-admin! ctx)
  (let [system
        (system/of ctx)

        platform
        (api/platform-spec system)

        since
        (window-start user-activity-days)

        runs
        (guarded {} runs/run-counts-by-user system since)

        tokens
        (guarded {} observability/tokens-by-user system since)

        admins
        (set (:admins platform))

        base
        (into {}
              (map (fn [username] [username {:username username :platform-admin true :teams {}}]))
              admins)

        people
        (reduce (fn [acc team]
                  (reduce (fn [acc username]
                            (-> acc
                                (update username #(or % {:username username
                                                         :platform-admin (contains? admins username)
                                                         :teams {}}))
                                (assoc-in [username :teams (keyword (:id team))]
                                          (if (some #{username} (:admins team)) :admin :member))))
                          acc
                          (concat (:members team) (:admins team))))
                base
                (:teams platform))]
    (mapv (fn [[username entry]]
            (assoc entry
                   :runs-30d (get runs username 0)
                   :tokens-30d (get tokens username 0)))
          (sort-by key people))))

(defn test-llm-connection
  "Calls the connection the way the LLM gateway does."
  [ctx {:keys [connection-id]}]
  (api/require-admin! ctx)
  (let [system
        (system/of ctx)

        connection
        (or (api/llm-connection system connection-id)
            (errors/not-found! "Unknown LLM connection"))

        secret
        (guarded nil credentials/secret system (:secret connection))]
    (if (string/blank? secret)
      {:ok false
       :message (str "secret " (:secret connection) " is not configured")}
      (observability/test-llm-connection connection secret))))

(def ^:private people
  {:role [:user]})

(defn register
  [ctx]
  (-> ctx
      (edd/reg-query :get-me get-me :auth people)
      (edd/reg-query :get-personas get-personas :auth {:role [:non-interactive]})
      (edd/reg-query :get-platform get-platform :auth people)
      (edd/reg-query :list-activities list-activities :auth people)
      (edd/reg-query :get-schemas get-schemas :auth people)
      (edd/reg-query :list-teams list-teams :auth people)
      (edd/reg-query :get-team get-team
                     :auth people
                     :consumes [:map [:team-id string?]])
      (edd/reg-query :list-harnesses list-harnesses :auth people)
      (edd/reg-query :get-admin-overview get-admin-overview :auth people)
      (edd/reg-query :list-admin-users list-admin-users :auth people)
      (edd/reg-query :test-llm-connection test-llm-connection
                     :auth people
                     :consumes [:map [:connection-id string?]])))
