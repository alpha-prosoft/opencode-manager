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

(defn- platform
  [roots]
  (assoc (f/platform-json roots :admins ["lead"] :members ["dev" "lead"])
         :admins ["root"]
         :credentials [{:id "TOKEN" :name "Tools token" :hosts ["tools.internal"] :provided_by "platform_or_user"}]
         :mcp_servers [{:id "tools" :name "Tools" :url "http://tools.internal/" :credential "TOKEN" :tools ["a"]}]
         :llm_connections [{:id "llm" :base_url "https://llm.example/v1/" :secret "LLM_KEY" :models ["model-a"]
                            :pricing {:model-a {:input 1.0 :output 2.0}}}]))

(defn- built
  []
  (let [roots
        (support/temp-dir)]
    (f/build! roots (platform roots)
              {"agents/a.json" f/agent-spec
               "harnesses/h.json" (assoc f/harness :mcp ["tools"] :skills ["skills/triage/"])
               "skills/triage/SKILL.md" "skill"
               "workflows/w.json" workflow})))

(defn- query
  ([ctx username q]
   (mock/query (support/as-user ctx username) q)))

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
             :platform-admin false
             :teams [{:id "team" :role :admin}]}
            (query ctx "lead" {:query-id :get-me})))
        (is
         (= {:platform-admin true :teams [{:id "team" :role :admin}]}
            (select-keys (query ctx "root" {:query-id :get-me}) [:platform-admin :teams])))
        (is
         (= []
            (:teams (query ctx "stranger" {:query-id :get-me}))))))))

(deftest personas-are-for-the-edge-only
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)]
      (is
       (= [{:username "dev" :roles ["Member · Team"]}
           {:username "lead" :roles ["Team admin · Team"]}
           {:username "root" :roles ["Platform admin"]}]
          (mock/query (support/as-platform ctx) {:query-id :get-personas})))
      (is
       (thrown? clojure.lang.ExceptionInfo
                (query ctx "dev" {:query-id :get-personas}))))))

(deftest platform-status-activities-and-schemas
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)

          status
          (query ctx "dev" {:query-id :get-platform})]
      (is
       (= {:configured true
           :error-message nil
           :teams 1
           :llm-connections [{:id "llm" :type :openai-compatible :models ["model-a"]}]
           :allowed-images ["ghcr.io/anomalyco/opencode:1.19.5"]}
          (dissoc status :commit :synced-at)))
      (is
       (string? (:commit status)))
      (is
       (= #{:id :name :category :description :icon :interactive :result-tool :options-schema :inputs-schema}
          (set (keys (first (query ctx "dev" {:query-id :list-activities}))))))
      (is
       (= #{:platform :agents :harnesses :workflows}
          (set (keys (query ctx "dev" {:query-id :get-schemas}))))))))

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
           :counts {:agents 1 :harnesses 1 :workflows 1}
           :invalid 0}
          (dissoc team :repository :commit :synced-at)))
      (is
       (= #{:type :url :branch :path}
          (set (keys (:repository team)))))
      (is
       (= []
          (query ctx "stranger" {:query-id :list-teams}))))))

(deftest team-detail
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)]
      (testing "with other domains unavailable"
        (with-facades-down
          (let [team
                (query ctx "dev" {:query-id :get-team :team-id "team"})]
            (is
             (= {:members ["dev" "lead"] :admins ["lead"] :secrets []}
                (select-keys team [:members :admins :secrets])))
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
             (= {:daily-tokens nil :used-today 0}
                (:budget team)))
            (is
             (= []
                (:usage-by-model team))))))
      (testing "with usage"
        (with-redefs [observability/team-usage (fn [_ team-id since]
                                                 (when (and (= "team" team-id) (string/ends-with? since "T00:00:00Z"))
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
      (testing "hidden from non-members"
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"Team not found"
                           (query ctx "stranger" {:query-id :get-team :team-id "team"})))
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"Team not found"
                           (query ctx "root" {:query-id :get-team :team-id "missing"})))))))

(deftest harness-cards
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)]
      (with-redefs [credentials/resolve-credential (fn [_ credential-id user]
                                                     (when (and (= "TOKEN" credential-id) (= "dev" user))
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
              :credentials [{:id "TOKEN" :name "Tools token" :provided-by :platform-or-user :source :user}]
              :ready true
              :workflows [{:id "w" :name "Analyse"}]}]
            (query ctx "dev" {:query-id :list-harnesses})))
        (is
         (= [false]
            (map :ready (query ctx "lead" {:query-id :list-harnesses}))))
        (is
         (= []
            (query ctx "stranger" {:query-id :list-harnesses})))))))

(deftest admin-views-are-for-platform-admins
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)]
      (doseq [q [{:query-id :get-admin-overview}
                 {:query-id :list-admin-users}
                 {:query-id :test-llm-connection :connection-id "llm"}]]
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"Platform administrators only"
                           (query ctx "lead" q)))))))

(deftest admin-overview
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)]
      (with-facades-down
        (is
         (= {:teams 1
             :users 3
             :sessions {:total 0 :running 0}
             :runs-today {}
             :tokens-today 0
             :cost-today nil}
            (dissoc (query ctx "root" {:query-id :get-admin-overview}) :status))))
      (with-redefs [observability/usage-rows (fn [_ _ _] [{:team-id "team" :connection "llm" :model "model-a"
                                                           :prompt-tokens 1000000 :completion-tokens 500000
                                                           :cached-tokens 0 :cost 2.0}])
                    sessions/running-session-ids (fn [_] ["s1" "s2"])
                    sessions/session-count (fn [_] 5)
                    runs/run-counts-by-status (fn [_ since]
                                                (when (string/ends-with? since "T00:00:00Z")
                                                  {:completed 2 :running 1}))]
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
              (get-in overview [:sessions :running])))
          (is
           (= #{:commit :synced-at :error-message}
              (set (keys (:status overview))))))))))

(deftest admin-users
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)]
      (with-facades-down
        (is
         (= [{:username "dev" :platform-admin false :teams {:team :member} :runs-30d 0 :tokens-30d 0}
             {:username "lead" :platform-admin false :teams {:team :admin} :runs-30d 0 :tokens-30d 0}
             {:username "root" :platform-admin true :teams {} :runs-30d 0 :tokens-30d 0}]
            (query ctx "root" {:query-id :list-admin-users}))))
      (with-redefs [runs/run-counts-by-user (fn [_ _] {"dev" 3})
                    observability/tokens-by-user (fn [_ _] {"dev" 1200 "lead" 40})]
        (is
         (= [["dev" 3 1200] ["lead" 0 40] ["root" 0 0]]
            (mapv (juxt :username :runs-30d :tokens-30d)
                  (query ctx "root" {:query-id :list-admin-users}))))))))

(deftest llm-connection-test
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)

          calls
          (atom [])]
      (with-redefs [credentials/secret (fn [_ secret-name] (when (= "LLM_KEY" secret-name) "key"))
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
                           (query ctx "root" {:query-id :test-llm-connection :connection-id "other"}))))
      (with-redefs [credentials/secret (fn [_ _] nil)]
        (is
         (= {:ok false :message "secret LLM_KEY is not configured"}
            (query ctx "root" {:query-id :test-llm-connection :connection-id "llm"})))))))

(deftest sync-is-requested-by-admins-and-run-by-the-worker
  (mock/with-mock-dal
    (let [{:keys [ctx system]}
          (built)]
      (is
       (thrown-with-msg? clojure.lang.ExceptionInfo #"Platform administrators only"
                         (mock/handle-cmd (support/as-user ctx "lead") {:commands [{:cmd-id :sync-platform}]})))
      (is
       (nil?
        (api/sync-requested! system)))
      (is
       (true?
        (:success (mock/handle-cmd (support/as-user ctx "root") {:commands [{:cmd-id :sync-platform}]}))))
      (is
       (= {"team" []}
          (update-vals (:teams (api/sync-requested! system)) :invalid)))
      (is
       (nil?
        (api/sync-requested! system))))))
