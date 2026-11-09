(ns manager-svc.platform.views
  "Queries the UI calls for the registry, teams, harnesses, the tenant's
   admin console and the operator's tenants. Everything a person reads is
   of their own tenant. Shapes follow manager/ui/src/api.ts with kebab-case
   keys.
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
            [manager-svc.platform.projects :as projects]
            [manager-svc.platform.spec :as spec]
            [manager-svc.platform.state :as state]
            [manager-svc.roles :as roles]
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

(defn guarded
  "(apply f args), or default when it fails or answers nil: a page shows
   less instead of failing with another domain."
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
  [system tenant]
  (let [status
        (api/status system tenant)]
    (assoc (dissoc status :error) :error-message (:error status))))

(defn get-me
  [ctx _query]
  (let [system
        (system/of ctx)

        caller
        (roles/caller ctx)]
    {:sub (:id caller)
     :email (:email caller)
     :name (:name caller)
     :tenant (:tenant caller)
     :tenant-admin (roles/tenant-admin? caller (:tenant caller))
     :operator (roles/operator? caller)
     :teams (mapv (fn [team-id]
                    {:id team-id
                     :name (or (:name (api/team system (:tenant caller) team-id)) team-id)
                     :role (api/role system caller team-id)
                     :claimed-role (roles/claimed-team-role caller (:tenant caller) team-id)})
                  (sort (api/teams-of system caller)))
     :tenant-teams (if (:tenant caller)
                     (vec (sort-by :id (for [team (api/teams system (:tenant caller))]
                                         {:id (:id team) :name (api/team-name team)})))
                     [])}))

(defn get-tenant
  [ctx _query]
  (let [system
        (system/of ctx)

        tenant
        (roles/tenant-of ctx)

        spec
        (api/tenant-spec system tenant)]
    (merge (sync-status system tenant)
           {:tenant tenant
            :teams (count (:teams spec))
            :llm-connections (mapv #(select-keys % [:id :type :models]) (:llm-connections spec))
            :allowed-images (:allowed-images spec)})))

(defn- source-label
  [{:keys [type url location]}]
  (str (name (or type :git)) " " (or url location)))

(defn list-tenants
  "Every tenant and its sync state, for operators."
  [ctx _query]
  (roles/require-operator! ctx)
  (let [system
        (system/of ctx)]
    (vec (for [{:keys [name source origin]} (state/current-tenants ctx)
               :let [status (sync-status system name)]]
           {:name name
            :source (source-label source)
            :registered (= :registered origin)
            :commit (:commit status)
            :synced-at (:synced-at status)
            :error-message (:error-message status)
            :teams (count (api/teams system name))}))))

(defn list-activities
  [_ctx _query]
  @catalog/activities)

(defn get-schemas
  [_ctx _query]
  @catalog/json-schemas)

(defn- team-summary
  [system team caller]
  (let [definitions
        (api/definitions system (:tenant team) (:id team))]
    {:id (:id team)
     :name (:name team)
     :description (:description team)
     :repository (select-keys (:repository team) [:type :url :location :branch :path])
     :commit (:commit team)
     :synced-at (:synced-at team)
     :status (:status team)
     :error-message (:error team)
     :role (api/role system caller (:id team))
     :projects (mapv #(projects/view team %) (:projects team))
     :counts (into {}
                   (map (fn [kind] [kind (count (filter #(= kind (:kind %)) definitions))]))
                   [:agents :harnesses :workflows])
     :invalid (count (remove :valid definitions))}))

(defn list-teams
  [ctx _query]
  (let [system
        (system/of ctx)

        caller
        (roles/caller ctx)

        mine
        (api/teams-of system caller)]
    (vec (for [team (api/teams system (:tenant caller))
               :when (contains? mine (:id team))]
           (team-summary system team caller)))))

(defn visible-team!
  [system caller team-id]
  (let [team
        (api/team system (:tenant caller) team-id)]
    (cond
      (nil? team) (errors/not-found! "Team not found")
      (nil? (api/role system caller team-id))
      (errors/forbidden! (str "You're not in " (api/team-name team) ". Only its members see its workflows, runs and"
                              " sessions; ask an admin of " team-id " or your tenant admin to add you.")))
    team))

(defn definition-view
  "A team definition; a valid workflow also carries the JSON Schema of the inputs a run of it takes."
  [{:keys [kind spec valid] :as definition}]
  (cond-> (select-keys definition [:kind :id :source :spec :errors :valid])
    (and valid (= :workflows kind))
    (assoc :inputs-schema (catalog/inputs-schema (:activity spec) (:options spec)))))

(defn get-team
  [ctx {:keys [team-id]}]
  (let [system
        (system/of ctx)

        caller
        (roles/caller ctx)

        tenant
        (:tenant caller)

        team
        (visible-team! system caller team-id)

        usage
        (guarded {} observability/team-usage system tenant team-id (window-start usage-window-days))]
    (merge (team-summary system team caller)
           {:tenant tenant
            :secrets (:secrets team)
            :definitions (mapv definition-view (api/definitions system tenant team-id))
            :usage (merge empty-usage (:usage usage))
            :budget (merge (select-keys (:budget team) [:daily-tokens :max-running-sessions :idle-minutes])
                           {:used-today (or (:used-today usage) 0)})
            :llm-connections (:llm-connections team)
            :mcp-servers (:mcp-servers team)
            :usage-by-model (vec (:by-model usage))})))

(defn- readiness
  [system tenant-spec scope credential-id]
  (let [declared
        (spec/credential tenant-spec credential-id)

        resolved
        (guarded nil credentials/resolve-credential system scope credential-id)]
    {:id credential-id
     :name (or (:name declared) credential-id)
     :provided-by (or (:provided-by declared) [:tenant])
     :source (some-> (:source resolved) keyword)}))

(defn- skill-name
  [path]
  (last (string/split (string/replace path #"/+$" "") #"/")))

(defn- harness-card
  [system tenant-spec scope team-name workflows definition]
  (let [harness
        (or (:spec definition) {})

        servers
        (keep #(spec/mcp-server-of tenant-spec %) (:mcp harness))

        credential-ids
        (sort (set (keep :credential servers)))

        credential-states
        (mapv #(readiness system tenant-spec scope %) credential-ids)]
    {:team-id (:team-id scope)
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

        caller
        (roles/caller ctx)

        tenant
        (:tenant caller)

        tenant-spec
        (api/tenant-spec system tenant)]
    (vec (for [team-id (sort (api/teams-of system caller))
               :let [team (api/team system tenant team-id)
                     workflows (api/workflows system tenant team-id)
                     scope {:tenant tenant :team-id team-id :user (:id caller)}]
               definition (api/definitions system tenant team-id :harnesses)]
           (harness-card system tenant-spec scope (api/team-name team) workflows definition)))))

(defn get-admin-overview
  [ctx _query]
  (roles/require-tenant-admin! ctx)
  (let [system
        (system/of ctx)

        tenant
        (roles/tenant-of ctx)

        team-ids
        (mapv :id (api/teams system tenant))

        rows
        (if (empty? team-ids)
          []
          (guarded [] observability/usage-rows system tenant team-ids (time/start-of-day)))

        costs
        (keep :cost rows)

        running
        (count (guarded [] sessions/running-session-ids system tenant))

        since
        (window-start user-activity-days)]
    {:tenant tenant
     :status (select-keys (sync-status system tenant) [:commit :synced-at :error-message])
     :teams (count team-ids)
     :users (count (set (concat (keys (guarded {} runs/run-counts-by-user system tenant since))
                                (keys (guarded {} observability/tokens-by-user system tenant since)))))
     :sessions {:total (guarded running sessions/session-count system tenant)
                :running running}
     :runs-today (guarded {} runs/run-counts-by-status system tenant (time/start-of-day))
     :tokens-today (reduce + 0 (map #(+ (or (:prompt-tokens %) 0) (or (:completion-tokens %) 0)) rows))
     :cost-today (when (seq costs) (/ (Math/round (* (reduce + costs) 10000.0)) 10000.0))}))

(defn list-admin-users
  "People of the tenant active in the last 30 days. Roles come from the
   identity provider's claims, so only activity is known here."
  [ctx _query]
  (roles/require-tenant-admin! ctx)
  (let [system
        (system/of ctx)

        tenant
        (roles/tenant-of ctx)

        since
        (window-start user-activity-days)

        runs
        (guarded {} runs/run-counts-by-user system tenant since)

        tokens
        (guarded {} observability/tokens-by-user system tenant since)]
    (mapv (fn [username]
            {:username username
             :runs-30d (get runs username 0)
             :tokens-30d (get tokens username 0)})
          (sort (set (remove auth/platform-user? (concat (keys runs) (keys tokens))))))))

(defn test-llm-connection
  "Calls the connection the way the LLM gateway does."
  [ctx {:keys [connection-id]}]
  (roles/require-tenant-admin! ctx)
  (let [system
        (system/of ctx)

        tenant
        (roles/tenant-of ctx)

        connection
        (or (api/llm-connection system tenant connection-id)
            (errors/not-found! "Unknown LLM connection"))

        secret
        (guarded nil credentials/secret system tenant (:secret connection))]
    (if (string/blank? secret)
      {:ok false
       :message (str "secret " (:secret connection) " is not configured")}
      (observability/test-llm-connection connection secret))))

(defn register
  [ctx]
  (-> ctx
      (edd/reg-query :get-me get-me :auth roles/people)
      (edd/reg-query :get-tenant get-tenant :auth roles/people)
      (edd/reg-query :list-tenants list-tenants :auth roles/people)
      (edd/reg-query :list-activities list-activities :auth roles/people)
      (edd/reg-query :get-schemas get-schemas :auth roles/people)
      (edd/reg-query :list-teams list-teams :auth roles/people)
      (edd/reg-query :get-team get-team
                     :auth roles/people
                     :consumes [:map [:team-id string?]])
      (edd/reg-query :list-harnesses list-harnesses :auth roles/people)
      (edd/reg-query :get-admin-overview get-admin-overview :auth roles/people)
      (edd/reg-query :list-admin-users list-admin-users :auth roles/people)
      (edd/reg-query :test-llm-connection test-llm-connection
                     :auth roles/people
                     :consumes [:map [:connection-id string?]])))
