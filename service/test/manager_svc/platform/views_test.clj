(ns manager-svc.platform.views-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.observability.api :as observability]
            [manager-svc.platform.api :as api]
            [manager-svc.platform.fixtures :as f]
            [manager-svc.platform.views :as views]
            [manager-svc.runs.api :as runs]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.test-support :as support]))

(defn- not-implemented
  [& _]
  (throw (ex-info "not implemented" {})))

(def workflow
  {:name "Analyse" :activity "analysis" :agent "a" :harness "h"})

(def app-project
  {:id "app"
   :name "app"
   :team-id "team"
   :team-name "Team"
   :branch "main"
   :scm {:provider :github :repository "acme/app"}
   :repository-url "https://github.com/acme/app"})

(def people
  {"dev" {:teams {"team" :member}}
   "lead" {:teams {"team" :admin}}
   "root" {:tenant-admin true}
   "stranger" {}
   "gina" {:tenant "globex" :tenant-admin true :teams {"team" :admin}}
   "oscar" {:tenant nil :operator true}})

(defn- tenant-json
  [roots]
  (assoc (f/tenant-json roots)
         :credentials [{:id "TOKEN" :name "Tools token" :hosts ["tools.internal"] :provided_by ["tenant" "user"]}]
         :mcp_servers [{:id "tools" :name "Tools" :url "http://tools.internal/" :credential "TOKEN" :tools ["a"]}]
         :llm_connections [{:id "llm" :base_url "https://llm.example/v1/" :secret "LLM_KEY" :models ["model-a"]
                            :pricing {:model-a {:input 1.0 :output 2.0}}}]))

(defn- built
  []
  (let [roots
        (support/temp-dir)]
    (f/build! roots (tenant-json roots)
              {"team.json" {:budget {:daily_tokens 50000 :max_running_sessions 3}
                            :projects [{:id "app" :scm {:provider "github" :repository "acme/app"}}]}
               "agents/a.json" f/agent-spec
               "harnesses/h.json" (assoc f/harness :mcp ["tools"] :skills ["skills/triage/"])
               "skills/triage/SKILL.md" "skill"
               "workflows/w.json" workflow})))

(defn- as
  [ctx username]
  (support/as-user ctx username (get people username {})))

(defn- query
  [ctx username q]
  (mock/query (as ctx username) q))

(defmacro with-facades-down
  [& body]
  `(with-redefs [observability/team-usage not-implemented
                 observability/usage-rows not-implemented
                 credentials/resolve-credential not-implemented
                 observability/tokens-by-user not-implemented
                 credentials/secret not-implemented
                 runs/run-counts-by-status not-implemented
                 runs/run-counts-by-user not-implemented
                 sessions/session-count not-implemented
                 sessions/running-session-ids not-implemented]
     ~@body))

(deftest get-me-describes-the-caller
  (mock/with-mock-dal
    (with-facades-down
      (let [{:keys [ctx]}
            (built)]
        (is
         (= {:sub "lead"
             :email "lead@users.local"
             :name "lead"
             :tenant "acme"
             :tenant-admin false
             :operator false
             :teams [{:id "team" :name "Team" :role :admin :claimed-role :admin}]}
            (dissoc (query ctx "lead" {:query-id :get-me}) :tenant-teams)))
        (testing "every team of the tenant by name, for people outside them too"
          (is
           (= [{:id "team" :name "Team"}]
              (:tenant-teams (query ctx "stranger" {:query-id :get-me})))))
        (is
         (= {:tenant-admin true :teams [{:id "team" :name "Team" :role :admin :claimed-role nil}]}
            (select-keys (query ctx "root" {:query-id :get-me}) [:tenant-admin :teams])))
        (is
         (= []
            (:teams (query ctx "stranger" {:query-id :get-me}))))
        (testing "claims of a team of another tenant name no team here"
          (is
           (= {:tenant "globex" :teams []}
              (select-keys (query ctx "gina" {:query-id :get-me}) [:tenant :teams]))))
        (is
         (= {:operator true :tenant nil}
            (select-keys (query ctx "oscar" {:query-id :get-me}) [:operator :tenant])))))))

(deftest tenant-status-activities-and-schemas
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)

          status
          (query ctx "dev" {:query-id :get-tenant})]
      (is
       (= {:tenant "acme"
           :configured true
           :error-message nil
           :teams 1
           :llm-connections [{:id "llm" :type :openai-compatible :models ["model-a"]}]
           :allowed-images ["ghcr.io/anomalyco/opencode:1.19.5"]}
          (dissoc status :commit :synced-at)))
      (is
       (string? (:commit status)))
      (is
       (= {:tenant "globex" :configured false :teams 0}
          (select-keys (query ctx "gina" {:query-id :get-tenant}) [:tenant :configured :teams])))
      (is
       (= #{:id :name :category :description :icon :interactive :result-tool :options-schema :inputs-schema}
          (set (keys (first (query ctx "dev" {:query-id :list-activities}))))))
      (is
       (= #{:tenant :team :agents :harnesses :workflows}
          (set (keys (query ctx "dev" {:query-id :get-schemas}))))))))

(deftest operators-list-tenants
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)

          [acme]
          (query ctx "oscar" {:query-id :list-tenants})]
      (is
       (= {:name "acme" :registered false :error-message nil :teams 1}
          (select-keys acme [:name :registered :error-message :teams])))
      (is
       (string/starts-with? (:source acme) "git file://"))
      (doseq [username ["root" "gina"]]
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"Platform operators only"
                           (query ctx username {:query-id :list-tenants})))))))

(deftest teams-are-listed-for-members-only
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)

          [team]
          (query ctx "dev" {:query-id :list-teams})]
      (is
       (= {:id "team"
           :name "Team"
           :description ""
           :status :ok
           :error-message nil
           :role :member
           :projects [app-project]
           :counts {:agents 1 :harnesses 1 :workflows 1}
           :invalid 0}
          (dissoc team :repository :commit :synced-at)))
      (is
       (= #{:type :url :branch :path}
          (set (keys (:repository team)))))
      (is
       (= [:admin]
          (mapv :role (query ctx "root" {:query-id :list-teams}))))
      (doseq [username ["stranger" "gina" "oscar"]]
        (is
         (= []
            (query ctx username {:query-id :list-teams})))))))

(deftest team-detail
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)]
      (testing "with other domains unavailable"
        (with-facades-down
          (let [team
                (query ctx "dev" {:query-id :get-team :team-id "team"})]
            (is
             (= {:tenant "acme"
                 :secrets []
                 :budget {:daily-tokens 50000 :max-running-sessions 3 :used-today 0}
                 :llm-connections [{:id "llm" :models ["model-a"]}]
                 :mcp-servers ["tools"]
                 :projects [app-project]}
                (select-keys team [:tenant :secrets :budget :llm-connections :mcp-servers :projects])))
            (is
             (not-any? #(contains? team %) [:members :admins]))
            (is
             (= [[:agents "a" true] [:harnesses "h" true] [:workflows "w" true]]
                (map (juxt :kind :id :valid) (:definitions team))))
            (is
             (= [nil nil ["brief"]]
                (map #(get-in % [:inputs-schema :required]) (:definitions team))))
            (is
             (= views/empty-usage
                (:usage team)))
            (is
             (= []
                (:usage-by-model team))))))
      (testing "with usage"
        (with-redefs [observability/team-usage (fn [_ tenant team-id since]
                                                 (when (and (= "acme" tenant) (= "team" team-id)
                                                            (string/ends-with? since "T00:00:00Z"))
                                                   {:usage {:calls 2 :prompt-tokens 10}
                                                    :used-today 42
                                                    :by-model [{:model "m" :calls 2 :prompt-tokens 10 :completion-tokens 5}]}))]
          (let [team
                (query ctx "dev" {:query-id :get-team :team-id "team"})]
            (is
             (= 2
                (get-in team [:usage :calls])))
            (is
             (= 42
                (get-in team [:budget :used-today])))
            (is
             (= [{:model "m" :calls 2 :prompt-tokens 10 :completion-tokens 5}]
                (:usage-by-model team))))))
      (testing "closed to non-members of the tenant, unknown to other tenants"
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"You're not in Team"
                           (query ctx "stranger" {:query-id :get-team :team-id "team"})))
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"Team not found"
                           (query ctx "gina" {:query-id :get-team :team-id "team"})))
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"Team not found"
                           (query ctx "root" {:query-id :get-team :team-id "missing"})))))))

(deftest harness-cards
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)

          scopes
          (atom [])]
      (with-redefs [credentials/resolve-credential (fn [_ scope credential-id]
                                                     (swap! scopes conj scope)
                                                     (when (and (= "TOKEN" credential-id) (= "dev" (:user scope)))
                                                       {:value {:secret "x"} :source :user}))]
        (is
         (= [{:team-id "team"
              :team-name "Team"
              :id "h"
              :valid true
              :errors []
              :description ""
              :network :none
              :models ["model-a"]
              :skills ["triage"]
              :mcp-servers [{:id "tools" :name "Tools" :description "" :tools ["a"]}]
              :credentials [{:id "TOKEN" :name "Tools token" :provided-by [:tenant :user] :source :user}]
              :ready true
              :workflows [{:id "w" :name "Analyse"}]}]
            (query ctx "dev" {:query-id :list-harnesses})))
        (is
         (= {:tenant "acme" :team-id "team" :user "dev"}
            (first @scopes)))
        (is
         (= [false]
            (map :ready (query ctx "lead" {:query-id :list-harnesses}))))
        (doseq [username ["stranger" "gina"]]
          (is
           (= []
              (query ctx username {:query-id :list-harnesses}))))))))

(deftest admin-views-are-for-tenant-admins
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)]
      (doseq [q [{:query-id :get-admin-overview}
                 {:query-id :list-admin-users}
                 {:query-id :test-llm-connection :connection-id "llm"}]
              username ["lead" "oscar"]]
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"Tenant administrators only"
                           (query ctx username q)))))))

(deftest admin-overview
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)]
      (with-facades-down
        (is
         (= {:tenant "acme"
             :teams 1
             :users 0
             :sessions {:total 0 :running 0}
             :runs-today {}
             :tokens-today 0
             :cost-today nil}
            (dissoc (query ctx "root" {:query-id :get-admin-overview}) :status))))
      (with-redefs [observability/usage-rows (fn [_ tenant _ _]
                                               (when (= "acme" tenant)
                                                 [{:team-id "team" :connection "llm" :model "model-a"
                                                   :prompt-tokens 1000000 :completion-tokens 500000
                                                   :cached-tokens 0 :cost 2.0}]))
                    sessions/running-session-ids (fn [_ tenant] (when (= "acme" tenant) ["s1" "s2"]))
                    sessions/session-count (fn [_ tenant] (if (= "acme" tenant) 5 0))
                    runs/run-counts-by-status (fn [_ tenant since]
                                                (when (and (= "acme" tenant) (string/ends-with? since "T00:00:00Z"))
                                                  {:completed 2 :running 1}))
                    runs/run-counts-by-user (fn [_ tenant _] (when (= "acme" tenant) {"dev" 2}))
                    observability/tokens-by-user (fn [_ tenant _] (when (= "acme" tenant) {"dev" 10 "lead" 4}))]
        (let [overview
              (query ctx "root" {:query-id :get-admin-overview})]
          (is
           (= {:total 5 :running 2}
              (:sessions overview)))
          (is
           (= {:completed 2 :running 1}
              (:runs-today overview)))
          (is
           (= 1500000
              (:tokens-today overview)))
          (is
           (= 2.0
              (:cost-today overview)))
          (is
           (= 2
              (:users overview)))
          (is
           (= #{:commit :synced-at :error-message}
              (set (keys (:status overview))))))
        (testing "another tenant's admin sees only their tenant"
          (is
           (= {:tenant "globex" :teams 0 :users 0 :sessions {:total 0 :running 0} :runs-today {} :tokens-today 0}
              (select-keys (query ctx "gina" {:query-id :get-admin-overview})
                           [:tenant :teams :users :sessions :runs-today :tokens-today]))))))))

(deftest admin-users
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)]
      (with-facades-down
        (is
         (= []
            (query ctx "root" {:query-id :list-admin-users}))))
      (with-redefs [runs/run-counts-by-user (fn [_ tenant _] (when (= "acme" tenant) {"dev" 3 "platform" 9}))
                    observability/tokens-by-user (fn [_ tenant _] (when (= "acme" tenant) {"dev" 1200 "lead" 40}))]
        (is
         (= [{:username "dev" :runs-30d 3 :tokens-30d 1200}
             {:username "lead" :runs-30d 0 :tokens-30d 40}]
            (query ctx "root" {:query-id :list-admin-users})))
        (is
         (= []
            (query ctx "gina" {:query-id :list-admin-users})))))))

(deftest llm-connection-test
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)

          calls
          (atom [])]
      (with-redefs [credentials/secret (fn [_ tenant secret-name] (when (= ["acme" "LLM_KEY"] [tenant secret-name]) "key"))
                    observability/test-llm-connection (fn [connection secret]
                                                        (swap! calls conj [(:id connection) secret])
                                                        {:ok true :model "model-a" :latency-ms 3})]
        (is
         (= {:ok true :model "model-a" :latency-ms 3}
            (query ctx "root" {:query-id :test-llm-connection :connection-id "llm"})))
        (is
         (= [["llm" "key"]]
            @calls))
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown LLM connection"
                           (query ctx "root" {:query-id :test-llm-connection :connection-id "other"})))
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown LLM connection"
                           (query ctx "gina" {:query-id :test-llm-connection :connection-id "llm"}))))
      (with-redefs [credentials/secret (fn [_ _ _] nil)]
        (is
         (= {:ok false :message "secret LLM_KEY is not configured"}
            (query ctx "root" {:query-id :test-llm-connection :connection-id "llm"})))))))

(deftest sync-is-requested-by-tenant-admins-and-run-by-the-worker
  (mock/with-mock-dal
    (let [{:keys [ctx system]}
          (built)]
      (is
       (thrown-with-msg? clojure.lang.ExceptionInfo #"Tenant administrators only"
                         (mock/handle-cmd (as ctx "lead") {:commands [{:cmd-id :sync-tenant}]})))
      (is
       (= {}
          (api/sync-requested! system)))
      (is
       (true?
        (:success (mock/handle-cmd (as ctx "root") {:commands [{:cmd-id :sync-tenant}]}))))
      (is
       (= {"team" []}
          (update-vals (get-in (api/sync-requested! system) ["acme" :teams]) :invalid)))
      (is
       (= {}
          (api/sync-requested! system))))))
