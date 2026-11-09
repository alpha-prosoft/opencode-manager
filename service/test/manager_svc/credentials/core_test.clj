(ns manager-svc.credentials.core-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.api :as api]
            [manager-svc.credentials.edge :as edge]
            [manager-svc.credentials.fixtures :as fixtures :refer [with-platform]]
            [manager-svc.http :as http]
            [manager-svc.credentials.mcp :as mcp]
            [manager-svc.directory :as directory]
            [manager-svc.test-support :as support]))

(def ^:private value-scopes
  {:set-my-credential :user
   :set-platform-credential :platform})

(defn- as-sent
  "What the edge sends: a value is sealed for the caller before it reaches edd."
  [ctx cmd-id attrs]
  (if-let [scope
           (value-scopes cmd-id)]
    (:attrs (edge/sealed-command fixtures/settings scope (:credential-id attrs) (auth/user-id ctx)
                                 (select-keys attrs [:secret :username])))
    attrs))

(defn- execute
  [ctx cmd-id attrs]
  (try
    (let [response
          (support/apply! ctx {:cmd-id cmd-id :id (random-uuid) :attrs (as-sent ctx cmd-id attrs)})]
      (if (:error response)
        {:error (:error response)}
        {:success true}))
    (catch clojure.lang.ExceptionInfo e
      {:error (ex-message e) :status (:status (ex-data e))})))

(defn- attempt
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      {:error (ex-message e) :status (:status (ex-data e))})))

(defn- answering
  [status]
  (fn [_request]
    {:status status :headers {} :body ""}))

(defn- as
  [username]
  (support/as-user (fixtures/ctx) username))

(defn- platform-ctx
  []
  (support/as-platform (fixtures/ctx)))

(defn- system
  []
  (assoc (:manager/system (fixtures/ctx))
         ::ctx (platform-ctx)))

(defn- query-edd
  [_system credential-id owner]
  (mock/query (platform-ctx) {:query-id :get-stored-credential
                              :credential-id credential-id
                              :owner owner}))

(defn- my-credentials
  [username]
  (mock/query (as username) {:query-id :get-my-credentials}))

(defn- by-id
  [items]
  (into {} (map (juxt :id identity)) items))

(deftest saved-values-are-encrypted-and-resolved
  (mock/with-mock-dal
    (with-platform {}
      (with-redefs [http/request! (answering 200)]
        (is
         (true?
          (:success (execute (as "alice") :set-my-credential
                             {:credential-id "github" :secret " ghp_0123456789abcdef "})))))
      (testing "no plaintext in events or aggregates"
        (is
         (= [:credential-value-set :credentials-seen]
            (remove #{:directory-created :directory-row-upserted}
                    (mapv :event-id (mock/peek-state :event-store)))))
        (is
         (not (string/includes? (pr-str (mock/peek-state)) "ghp_0123456789"))))
      (with-redefs [api/stored-record query-edd]
        (is
         (= {:value {:secret "ghp_0123456789abcdef"} :source :user}
            (api/resolve-credential (system) "github" "alice")))))))

(deftest resolve-prefers-own-value-then-platform-value
  (mock/with-mock-dal
    (with-platform {:teams {"alice" #{"alpha"} "bob" #{"alpha"}}}
      (with-redefs [http/request! (answering 200)]
        (execute (as "root") :set-platform-credential {:credential-id "github" :secret "platform-token"})
        (execute (as "alice") :set-my-credential {:credential-id "github" :secret "alice-token"})
        (execute (as "alice") :set-my-credential {:credential-id "jira" :secret "jira-token" :username "alice"})
        (execute (as "root") :set-platform-credential {:credential-id "llm-key" :secret "llm-secret"}))
      (with-redefs [api/stored-record query-edd]
        (is
         (= {:value {:secret "alice-token"} :source :user}
            (api/resolve-credential (system) "github" "alice")))
        (is
         (= {:value {:secret "platform-token"} :source :platform}
            (api/resolve-credential (system) "github" "bob")))
        (is
         (= {:value {:secret "platform-token"} :source :platform}
            (api/resolve-credential (system) "github" nil)))
        (testing "a credential only people provide has no platform fallback"
          (is
           (= {:value {:secret "jira-token" :username "alice"} :source :user}
              (api/resolve-credential (system) "jira" "alice")))
          (is
           (nil?
            (api/resolve-credential (system) "jira" "bob"))))
        (is
         (= "llm-secret"
            (api/secret (system) "llm-key")))
        (is
         (nil?
          (api/secret (system) "unknown")))
        (is
         (true?
          (api/uses-personal? (system) "alice" ["github" "jira"])))
        (is
         (false?
          (api/uses-personal? (system) "bob" ["jira"])))))))

(deftest status-and-pending
  (mock/with-mock-dal
    (with-platform {}
      (let [{:keys [credentials pending]}
            (my-credentials "alice")

            github
            (get (by-id credentials) "github")]
        (is
         (= ["github" "jira"]
            (mapv :id credentials)))
        (is
         (= ["github" "jira"]
            pending))
        (is
         (= {:state :missing :required false :kind :token :provided-by :platform-or-user
             :hint nil :verified-at nil :hosts ["api.github.com"]}
            (select-keys github [:state :required :kind :provided-by :hint :verified-at :hosts])))
        (is
         (not (contains? github :teams))))
      (execute (as "alice") :mark-credentials-seen {:credentials ["github" "jira"]})
      (testing "a required credential stays pending until it is set"
        (is
         (= ["jira"]
            (:pending (my-credentials "alice")))))
      (with-redefs [http/request! (answering 200)]
        (execute (as "root") :set-platform-credential {:credential-id "github" :secret "platform-token"})
        (execute (as "alice") :set-my-credential {:credential-id "jira" :secret "0123456789abcdef" :username "alice"}))
      (let [{:keys [credentials pending]}
            (my-credentials "alice")

            items
            (by-id credentials)]
        (is
         (= []
            pending))
        (is
         (= :platform
            (get-in items ["github" :state])))
        (is
         (= {:state :own :required true :hint "alice · …cdef" :verified-at nil}
            (select-keys (get items "jira") [:state :required :hint :verified-at]))))
      (execute (as "alice") :delete-my-credential {:credential-id "jira"})
      (is
       (= :missing
          (get-in (by-id (:credentials (my-credentials "alice"))) ["jira" :state])))
      (is
       (= ["jira"]
          (:pending (my-credentials "alice")))))))

(deftest test-failure-blocks-save
  (mock/with-mock-dal
    (with-platform {}
      (with-redefs [http/request! (answering 401)]
        (is
         (= {:error "Not saved. The service rejected it (HTTP 401)." :status 422}
            (execute (as "alice") :set-my-credential {:credential-id "github" :secret "ghp_0123456789abcdef"})))
        (is
         (empty?
          (mock/peek-state :event-store)))))))

(deftest test-through-mcp-tool
  (mock/with-mock-dal
    (with-redefs [fixtures/platform-spec (assoc-in fixtures/platform-spec [:credentials 1 :test] {:mcp-tool "whoami"})]
      (with-platform {}
        (with-redefs [mcp/call-tool (fn [_ _ _ _ _] (throw (ex-info "token expired" {:mcp/tool-failed true})))]
          (is
           (= {:error "Not saved. token expired" :status 422}
              (execute (as "alice") :set-my-credential {:credential-id "jira" :secret "x" :username "alice"}))))
        (with-redefs [mcp/call-tool (fn [server credential tool _ _]
                                      (when (and (= "jira" (:id server)) (= "alice" (:username credential)) (= "whoami" tool))
                                        {:user "alice"}))]
          (is
           (true?
            (:success (execute (as "alice") :set-my-credential {:credential-id "jira" :secret "x" :username "alice"}))))
          (is
           (some?
            (get-in (by-id (:credentials (my-credentials "alice"))) ["jira" :verified-at]))))))))

(deftest values-are-validated-before-the-check
  (mock/with-mock-dal
    (with-platform {}
      (with-redefs [http/request! (fn [_] (throw (AssertionError. "must not be called")))]
        (is
         (= {:error "Jira needs a username as well" :status 422}
            (execute (as "alice") :set-my-credential {:credential-id "jira" :secret "x"})))
        (is
         (= {:error "LLM key is managed by platform admins" :status 403}
            (execute (as "alice") :set-my-credential {:credential-id "llm-key" :secret "x"})))
        (is
         (= {:error "Sonar is not used by your teams" :status 403}
            (execute (as "alice") :set-my-credential {:credential-id "sonar" :secret "x"})))
        (is
         (= {:error "Unknown credential" :status 404}
            (execute (as "alice") :set-my-credential {:credential-id "nope" :secret "x"})))
        (is
         (= {:error "Jira is personal only: every user sets their own value" :status 422}
            (execute (as "root") :set-platform-credential {:credential-id "jira" :secret "x"})))))))

(deftest admin-views-need-a-platform-admin
  (mock/with-mock-dal
    (with-platform {}
      (with-redefs [http/request! (fn [_] (throw (AssertionError. "must not be called")))]
        (is
         (= {:error "Platform administrators only" :status 403}
            (execute (as "alice") :set-platform-credential {:credential-id "llm-key" :secret "x"})))
        (is
         (= {:error "Platform administrators only" :status 403}
            (execute (as "alice") :delete-platform-credential {:credential-id "llm-key"})))
        (is
         (= {:error "Platform administrators only" :status 403}
            (attempt #(mock/query (as "alice") {:query-id :list-admin-credentials}))))
        (is
         (= {:error "Platform administrators only" :status 403}
            (attempt #(mock/query (as "alice") {:query-id :list-mcp-servers})))))
      (is
       (thrown? Exception
                (mock/query (as "alice") {:query-id :get-stored-credential :credential-id "llm-key" :owner ""}))))))

(deftest admin-credentials
  (mock/with-mock-dal
    (with-platform {}
      (with-redefs [http/request! (answering 200)]
        (execute (as "root") :set-platform-credential {:credential-id "llm-key" :secret "0123456789abcdef"})
        (execute (as "alice") :set-my-credential {:credential-id "github" :secret "mine"}))
      (let [items
            (by-id (mock/query (as "root") {:query-id :list-admin-credentials}))]
        (is
         (= ["deploy-token" "github" "jira" "llm-key" "sonar"]
            (sort (keys items))))
        (is
         (= {:id "llm-key" :name "LLM key" :description "" :kind :token :provided-by :platform
             :declared true :hosts [] :platform-value :admin :hint "…cdef" :updated-by "root"
             :personal-values 0 :used-by ["LLM connection main"]}
            (dissoc (get items "llm-key") :updated-at)))
        (is
         (= {:platform-value nil :personal-values 1 :used-by ["MCP server GitHub MCP"]}
            (select-keys (get items "github") [:platform-value :personal-values :used-by])))
        (is
         (= {:declared false :name "deploy-token" :used-by ["granted to alpha"]}
            (select-keys (get items "deploy-token") [:declared :name :used-by]))))
      (execute (as "root") :delete-platform-credential {:credential-id "llm-key"})
      (is
       (nil?
        (:platform-value (get (by-id (mock/query (as "root") {:query-id :list-admin-credentials})) "llm-key")))))))

(deftest admin-mcp-servers
  (mock/with-mock-dal
    (with-platform {}
      (with-redefs [mcp/ping (fn [server]
                               (if (= "jira" (:id server))
                                 {:reachable true :detail "HTTP 200"}
                                 {:reachable false :detail "ConnectException"}))]
        (let [items
              (by-id (mock/query (as "root") {:query-id :list-mcp-servers}))]
          (is
           (= {:id "jira" :name "Jira MCP" :credential "jira" :tools ["search" "get_issue"]
               :reachable true :detail "HTTP 200" :harnesses ["alpha/coder"] :credential-ready true}
              (select-keys (get items "jira") [:id :name :credential :tools :reachable :detail :harnesses :credential-ready])))
          (is
           (= {:reachable false :harnesses [] :credential-ready false}
              (select-keys (get items "github") [:reachable :harnesses :credential-ready]))))))))

(deftest values-reach-edd-only-sealed-for-their-owner
  (mock/with-mock-dal
    (with-platform {:teams {"alice" #{"alpha"} "bob" #{"alpha"}}}
      (with-redefs [http/request! (answering 200)]
        (let [for-bob
              (:attrs (edge/sealed-command fixtures/settings :user "jira" "bob" {:secret "bob-secret-value" :username "bob"}))]
          (is
           (not (string/includes? (pr-str for-bob) "bob-secret-value")))
          (testing "a value sealed for bob can't be stored as alice's"
            (is
             (= {:error "The value was not sealed for this credential" :status 422}
                (attempt #(mock/apply-cmd (as "alice") {:cmd-id :set-my-credential :id (random-uuid) :attrs for-bob})))))
          (testing "a value sealed for a person can't become the platform value"
            (is
             (= 422
                (:status (attempt #(mock/apply-cmd (as "root") {:cmd-id :set-platform-credential :id (random-uuid)
                                                                :attrs (:attrs (edge/sealed-command fixtures/settings :user "github" "root" {:secret "x"}))}))))))
          (is
           (true?
            (:success (execute (as "bob") :set-my-credential {:credential-id "jira" :secret "bob-secret-value" :username "bob"})))))
        (testing "plaintext attributes are refused"
          (is
           (some?
            (:error (mock/apply-cmd (as "alice") {:cmd-id :set-my-credential :id (random-uuid)
                                                  :attrs {:credential-id "github" :secret "plain"}})))))))))

(deftest values-are-listed-in-their-owners-directories
  (mock/with-mock-dal
    (with-platform {:teams {"alice" #{"alpha"} "bob" #{"alpha"}}}
      (with-redefs [http/request! (answering 200)]
        (execute (as "root") :set-platform-credential {:credential-id "llm-key" :secret "0123456789abcdef"})
        (execute (as "alice") :set-my-credential {:credential-id "github" :secret "alice-token"})
        (execute (as "bob") :set-my-credential {:credential-id "github" :secret "bob-token"}))
      (let [ctx
            (platform-ctx)

            listed
            (fn [identity]
              (mapv (juxt :credential-id :owner) (directory/rows ctx identity :credentials)))]
        (is
         (= [["llm-key" ""]]
            (listed "platform")))
        (is
         (= [["github" "alice"]]
            (listed "user:alice")))
        (is
         (not (string/includes? (pr-str (directory/rows ctx "user:alice" :credentials)) "sealed")))
        (is
         (= 2
            (:personal-values (get (by-id (mock/query (as "root") {:query-id :list-admin-credentials})) "github"))))
        (execute (as "bob") :delete-my-credential {:credential-id "github"})
        (is
         (= []
            (listed "user:bob")))
        (is
         (= 1
            (:personal-values (get (by-id (mock/query (as "root") {:query-id :list-admin-credentials})) "github"))))))))
