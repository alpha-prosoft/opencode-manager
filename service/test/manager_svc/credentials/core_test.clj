(ns manager-svc.credentials.core-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.credentials.api :as api]
            [manager-svc.credentials.fixtures :as fixtures :refer [as execute platform-ctx with-platform]]
            [manager-svc.credentials.mcp :as mcp]
            [manager-svc.directory :as directory]
            [manager-svc.http :as http]
            [manager-svc.paths :as paths]
            [manager-svc.test-support :as support]))

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

(defn- system
  []
  (:manager/system (fixtures/ctx)))

(defn- my-credentials
  [username]
  (mock/query (as username) {:query-id :get-my-credentials}))

(defn- by-id
  [items]
  (into {} (map (juxt :id identity)) items))

(defn- resolved
  [owner-of credential-id]
  (with-redefs [api/stored-record fixtures/query-edd]
    (api/resolve-credential (system) owner-of credential-id)))

(deftest saved-values-are-encrypted-and-resolved
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (answering 200)]
        (is
         (true?
          (:success (execute (as "alice") :set-my-credential
                             {:credential-id "github" :secret " ghp_0123456789abcdef "})))))
      (testing "no plaintext in events or aggregates"
        (is
         (= [:credential-value-set :credentials-seen]
            (remove #{:directory-created :directory-row-upserted :directory-tenant-joined}
                    (mapv :event-id (mock/peek-state :event-store)))))
        (is
         (not (string/includes? (pr-str (mock/peek-state)) "ghp_0123456789"))))
      (is
       (= {:value {:secret "ghp_0123456789abcdef"} :source :user}
          (resolved {:tenant "acme" :team-id "alpha" :user "alice"} "github"))))))

(deftest resolution-goes-user-then-team-then-tenant
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (answering 200)]
        (execute (as "root") :set-tenant-credential {:credential-id "bitbucket" :secret "tenant-token"})
        (execute (as "dave") :set-team-credential {:credential-id "bitbucket" :team-id "alpha" :secret "alpha-token"})
        (execute (as "alice") :set-my-credential {:credential-id "bitbucket" :secret "alice-token"}))
      (is
       (= {:value {:secret "alice-token"} :source :user}
          (resolved {:tenant "acme" :team-id "alpha" :user "alice"} "bitbucket")))
      (is
       (= {:value {:secret "alpha-token"} :source :team}
          (resolved {:tenant "acme" :team-id "alpha" :user "bob"} "bitbucket")))
      (is
       (= {:value {:secret "alpha-token"} :source :team}
          (resolved {:tenant "acme" :team-id "alpha"} "bitbucket")))
      (is
       (= {:value {:secret "tenant-token"} :source :tenant}
          (resolved {:tenant "acme" :team-id "beta" :user "bob"} "bitbucket")))
      (testing "another tenant sees none of acme's values"
        (is
         (nil?
          (resolved {:tenant "globex" :team-id "alpha" :user "alice"} "bitbucket")))))))

(deftest a-scope-provides-a-value-only-where-provided-by-allows
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (answering 200)]
        (execute (as "root") :set-tenant-credential {:credential-id "github" :secret "tenant-token"})
        (execute (as "alice") :set-my-credential {:credential-id "github" :secret "alice-token"})
        (execute (as "alice") :set-my-credential {:credential-id "jira" :secret "jira-token" :username "alice"})
        (execute (as "dave") :set-team-credential {:credential-id "nexus" :team-id "alpha" :secret "nexus-token"})
        (execute (as "root") :set-tenant-credential {:credential-id "llm-key" :secret "llm-secret"}))
      (testing "a team value can't be stored for a credential teams don't provide"
        (is
         (= {:error "GitHub has no team values (see its provided_by)" :status 422}
            (execute (as "dave") :set-team-credential {:credential-id "github" :team-id "alpha" :secret "x"}))))
      (testing "a user value is ignored once the credential no longer lets people provide it"
        (with-redefs [fixtures/tenant-spec (assoc-in fixtures/tenant-spec [:credentials 0 :provided-by] [:tenant])]
          (is
           (= {:value {:secret "tenant-token"} :source :tenant}
              (resolved {:tenant "acme" :team-id "alpha" :user "alice"} "github")))))
      (testing "a team value is ignored once teams no longer provide it"
        (with-redefs [fixtures/tenant-spec (assoc-in fixtures/tenant-spec [:credentials 4 :provided-by] [:tenant])]
          (is
           (nil?
            (resolved {:tenant "acme" :team-id "alpha"} "nexus")))))
      (is
       (= {:value {:secret "nexus-token"} :source :team}
          (resolved {:tenant "acme" :team-id "alpha" :user "alice"} "nexus")))
      (testing "a credential only people provide has no shared fallback"
        (is
         (= {:value {:secret "jira-token" :username "alice"} :source :user}
            (resolved {:tenant "acme" :team-id "alpha" :user "alice"} "jira")))
        (is
         (nil?
          (resolved {:tenant "acme" :team-id "alpha" :user "bob"} "jira"))))
      (with-redefs [api/stored-record fixtures/query-edd]
        (is
         (= "llm-secret"
            (api/secret (system) "acme" "llm-key")))
        (is
         (nil?
          (api/secret (system) "globex" "llm-key")))
        (is
         (nil?
          (api/secret (system) "acme" "unknown")))
        (is
         (true?
          (api/uses-personal? (system) {:tenant "acme" :user "alice"} ["github" "jira"])))
        (is
         (false?
          (api/uses-personal? (system) {:tenant "acme" :user "bob"} ["jira"])))
        (is
         (false?
          (api/uses-personal? (system) {:tenant "globex" :user "alice"} ["jira"])))))))

(deftest operator-secret-files-are-per-tenant-first
  (let [dir
        (support/temp-dir)]
    (try
      (spit (io/file dir "SHARED") "shared-value\n")
      (io/make-parents (io/file dir "acme" "SHARED"))
      (spit (io/file dir "acme" "SHARED") "acme-value")
      (mock/with-mock-dal
        (with-platform
          (let [system
                (assoc-in (system) [:settings :secrets-dir] (.getPath dir))]
            (with-redefs [api/stored-record fixtures/query-edd]
              (is
               (= ["acme-value" "shared-value"]
                  [(api/secret system "acme" "SHARED") (api/secret system "globex" "SHARED")]))))))
      (finally
        (paths/delete-tree! dir)))))

(deftest status-and-pending
  (mock/with-mock-dal
    (with-platform
      (let [{:keys [credentials pending]}
            (my-credentials "alice")

            github
            (get (by-id credentials) "github")]
        (is
         (= ["github" "jira" "bitbucket" "nexus" "llm-key"]
            (mapv :id credentials)))
        (is
         (= ["github" "jira" "bitbucket"]
            pending)
         "only credentials the person may provide are pending")
        (is
         (= {"github" true "jira" true "bitbucket" true "nexus" false "llm-key" false}
            (into {} (map (juxt :id :may-provide)) credentials)))
        (is
         (= {:state :missing :required false :kind :token :provided-by [:tenant :user]
             :hint nil :verified-at nil :hosts ["api.github.com"]}
            (select-keys github [:state :required :kind :provided-by :hint :verified-at :hosts])))
        (is
         (= [{:team-id "alpha" :state :missing :required false}]
            (:teams github))
         "where the value comes from in each of the person's teams"))
      (execute (as "alice") :mark-credentials-seen {:credentials ["github" "jira" "bitbucket"]})
      (testing "a personal-only credential stays pending until it is set"
        (is
         (= ["jira"]
            (:pending (my-credentials "alice")))))
      (with-redefs [http/request! (answering 200)]
        (execute (as "root") :set-tenant-credential {:credential-id "github" :secret "tenant-token"})
        (execute (as "dave") :set-team-credential {:credential-id "bitbucket" :team-id "alpha" :secret "team-token"})
        (execute (as "dave") :set-team-credential {:credential-id "nexus" :team-id "alpha" :secret "nexus-token"})
        (execute (as "alice") :set-my-credential {:credential-id "jira" :secret "0123456789abcdef" :username "alice"}))
      (let [{:keys [credentials pending]}
            (my-credentials "alice")

            items
            (by-id credentials)]
        (is
         (= []
            pending))
        (is
         (= [:tenant :team]
            [(get-in items ["github" :state]) (get-in items ["bitbucket" :state])]))
        (testing "a credential only admins provide shows its resolved state"
          (is
           (= [:team :missing]
              [(get-in items ["nexus" :state]) (get-in items ["llm-key" :state])])))
        (is
         (= {:state :own :required false :personal-only true :hint "alice · …cdef" :verified-at nil}
            (select-keys (get items "jira") [:state :required :personal-only :hint :verified-at]))))
      (execute (as "alice") :delete-my-credential {:credential-id "jira"})
      (is
       (= :missing
          (get-in (by-id (:credentials (my-credentials "alice"))) ["jira" :state])))
      (is
       (= ["jira"]
          (:pending (my-credentials "alice")))))))

(deftest test-failure-blocks-save
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (answering 401)]
        (is
         (= {:error "Not saved. The service rejected it (HTTP 401)." :status 422}
            (execute (as "alice") :set-my-credential {:credential-id "github" :secret "ghp_0123456789abcdef"})))
        (is
         (empty?
          (mock/peek-state :event-store)))))))

(deftest test-through-mcp-tool
  (mock/with-mock-dal
    (with-redefs [fixtures/tenant-spec (assoc-in fixtures/tenant-spec [:credentials 1 :test] {:mcp-tool "whoami"})]
      (with-platform
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
    (with-platform
      (with-redefs [http/request! (fn [_] (throw (AssertionError. "must not be called")))]
        (is
         (= {:error "Jira needs a username as well" :status 422}
            (execute (as "alice") :set-my-credential {:credential-id "jira" :secret "x"})))
        (is
         (= {:error "LLM key is managed by your tenant or team admins" :status 403}
            (execute (as "alice") :set-my-credential {:credential-id "llm-key" :secret "x"})))
        (is
         (= {:error "Sonar is not used by your teams" :status 403}
            (execute (as "alice") :set-my-credential {:credential-id "sonar" :secret "x"})))
        (is
         (= {:error "Unknown credential" :status 404}
            (execute (as "alice") :set-my-credential {:credential-id "nope" :secret "x"})))
        (is
         (= {:error "Jira is personal only: every user sets their own value" :status 422}
            (execute (as "root") :set-tenant-credential {:credential-id "jira" :secret "x"})))
        (is
         (= {:error "Nexus has no tenant value (see its provided_by)" :status 422}
            (execute (as "root") :set-tenant-credential {:credential-id "nexus" :secret "x"})))
        (is
         (= {:error "Nexus is not used by team beta" :status 403}
            (execute (as "erin") :set-team-credential {:credential-id "nexus" :team-id "beta" :secret "x"})))))))

(deftest tenant-admin-actions-need-a-tenant-admin
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (fn [_] (throw (AssertionError. "must not be called")))]
        (is
         (= {:error "Tenant administrators only" :status 403}
            (execute (as "dave") :set-tenant-credential {:credential-id "llm-key" :secret "x"})))
        (is
         (= {:error "Tenant administrators only" :status 403}
            (execute (as "alice") :delete-tenant-credential {:credential-id "llm-key"})))
        (is
         (= {:error "Tenant administrators only" :status 403}
            (attempt #(mock/query (as "alice") {:query-id :list-admin-credentials}))))
        (is
         (= {:error "Tenant administrators only" :status 403}
            (attempt #(mock/query (as "dave") {:query-id :list-mcp-servers})))))
      (is
       (thrown? Exception
                (mock/query (as "alice") {:query-id :get-stored-credential :credential-id "llm-key" :owner "tenant:acme"}))))))

(deftest team-values-are-for-the-team-s-admins-members-see-what-is-set
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (answering 200)]
        (testing "a member, another team's admin and another tenant's admin may not"
          (is
           (= {:error "Only admins of team alpha can do this" :status 403}
              (execute (as "alice") :set-team-credential {:credential-id "bitbucket" :team-id "alpha" :secret "x"})))
          (is
           (= {:error "Only admins of team alpha can do this" :status 403}
              (execute (as "erin") :set-team-credential {:credential-id "bitbucket" :team-id "alpha" :secret "x"})))
          (is
           (= 403
              (:status (attempt #(mock/query (as "erin") {:query-id :list-team-credentials :team-id "alpha"}))))))
        (testing "the team's admin and the tenant admin may"
          (is
           (true?
            (:success (execute (as "dave") :set-team-credential {:credential-id "bitbucket" :team-id "alpha" :secret "0123456789dave"}))))
          (is
           (true?
            (:success (execute (as "root") :set-team-credential {:credential-id "nexus" :team-id "alpha" :secret "nexus-value"}))))))
      (let [items
            (by-id (mock/query (as "dave") {:query-id :list-team-credentials :team-id "alpha"}))]
        (is
         (= ["bitbucket" "nexus"]
            (sort (keys items))))
        (is
         (= {:id "bitbucket" :provided-by [:tenant :team :user] :value {:hint "…dave" :updated-by "dave"} :tenant-set false}
            (-> (select-keys (get items "bitbucket") [:id :provided-by :value :tenant-set])
                (update :value dissoc :updated-at)))))
      (testing "members see which credentials the team set, never hints"
        (is
         (= [{:id "bitbucket" :name "Bitbucket" :description "" :provided-by [:tenant :team :user] :set true}
             {:id "nexus" :name "Nexus" :description "" :provided-by [:team] :set true}]
            (sort-by :id (mock/query (as "alice") {:query-id :list-team-credentials :team-id "alpha"})))))
      (is
       (= [["bitbucket" "team:acme/alpha"] ["nexus" "team:acme/alpha"]]
          (sort (mapv (juxt :credential-id :owner) (directory/rows (platform-ctx) (directory/team "acme" "alpha") :credentials)))))
      (testing "erin of beta can't delete alpha's value"
        (is
         (= {:error "Only admins of team alpha can do this" :status 403}
            (execute (as "erin") :delete-team-credential {:credential-id "bitbucket" :team-id "alpha"}))))
      (execute (as "dave") :delete-team-credential {:credential-id "bitbucket" :team-id "alpha"})
      (is
       (nil?
        (:value (get (by-id (mock/query (as "dave") {:query-id :list-team-credentials :team-id "alpha"})) "bitbucket")))))))

(deftest admin-credentials
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (answering 200)]
        (execute (as "root") :set-tenant-credential {:credential-id "llm-key" :secret "0123456789abcdef"})
        (execute (as "alice") :set-my-credential {:credential-id "github" :secret "mine"})
        (execute (as "dave") :set-team-credential {:credential-id "bitbucket" :team-id "alpha" :secret "team"}))
      (let [items
            (by-id (mock/query (as "root") {:query-id :list-admin-credentials}))]
        (is
         (= ["bitbucket" "deploy-token" "github" "jira" "llm-key" "nexus" "sonar"]
            (sort (keys items))))
        (is
         (= {:id "llm-key" :name "LLM key" :description "" :kind :token :provided-by [:tenant]
             :declared true :hosts [] :tenant-value :admin :hint "…cdef" :updated-by "root"
             :team-values 0 :personal-values 0 :required false :required-by-teams []
             :used-by ["LLM connection main"]}
            (dissoc (get items "llm-key") :updated-at)))
        (is
         (= {:tenant-value nil :personal-values 1 :used-by ["MCP server GitHub MCP"]}
            (select-keys (get items "github") [:tenant-value :personal-values :used-by])))
        (is
         (= 1
            (:team-values (get items "bitbucket"))))
        (is
         (= {:declared false :name "deploy-token" :used-by ["granted to alpha"]}
            (select-keys (get items "deploy-token") [:declared :name :used-by]))))
      (testing "globex's admin sees none of acme's values"
        (let [items
              (by-id (mock/query (as "gina") {:query-id :list-admin-credentials}))]
          (is
           (= [nil 0 0]
              [(:tenant-value (get items "llm-key"))
               (:personal-values (get items "github"))
               (:team-values (get items "bitbucket"))]))))
      (execute (as "root") :delete-tenant-credential {:credential-id "llm-key"})
      (is
       (nil?
        (:tenant-value (get (by-id (mock/query (as "root") {:query-id :list-admin-credentials})) "llm-key")))))))

(deftest admin-mcp-servers
  (mock/with-mock-dal
    (with-platform
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
              (select-keys (get items "github") [:reachable :harnesses :credential-ready]))))
        (testing "harnesses of another tenant's team with the same id are not listed"
          (is
           (= []
              (:harnesses (get (by-id (mock/query (as "gina") {:query-id :list-mcp-servers})) "jira")))))))))

(deftest values-reach-edd-only-sealed-for-their-owner
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (answering 200)]
        (let [for-bob
              (fixtures/sealed (as "bob") :user {:credential-id "jira" :secret "bob-secret-value" :username "bob"})]
          (is
           (not (string/includes? (pr-str for-bob) "bob-secret-value")))
          (testing "a value sealed for bob can't be stored as alice's"
            (is
             (= {:error "The value was not sealed for this credential" :status 422}
                (attempt #(mock/apply-cmd (as "alice") {:cmd-id :set-my-credential :id (random-uuid) :attrs for-bob})))))
          (testing "a value sealed for a person can't become the tenant value"
            (is
             (= 422
                (:status (attempt #(mock/apply-cmd (as "root") {:cmd-id :set-tenant-credential :id (random-uuid)
                                                                :attrs (fixtures/sealed (as "root") :user {:credential-id "github" :secret "x"})}))))))
          (testing "a value sealed for one team can't be stored for another"
            (is
             (= 422
                (:status (attempt #(mock/apply-cmd (as "root") {:cmd-id :set-team-credential :id (random-uuid)
                                                                :attrs (assoc (fixtures/sealed (as "root") :team {:credential-id "bitbucket" :team-id "alpha" :secret "x"})
                                                                              :team-id "beta")}))))))
          (testing "a tenant value sealed in globex can't be stored in acme"
            (is
             (= 422
                (:status (attempt #(mock/apply-cmd (as "root") {:cmd-id :set-tenant-credential :id (random-uuid)
                                                                :attrs (fixtures/sealed (as "gina") :tenant {:credential-id "llm-key" :secret "x"})}))))))
          (is
           (true?
            (:success (execute (as "bob") :set-my-credential {:credential-id "jira" :secret "bob-secret-value" :username "bob"})))))
        (testing "plaintext attributes are refused"
          (is
           (some?
            (:error (mock/apply-cmd (as "alice") {:cmd-id :set-my-credential :id (random-uuid)
                                                  :attrs {:credential-id "github" :secret "plain"}})))))))))

(deftest values-are-listed-in-their-owners-aggregates
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (answering 200)]
        (execute (as "root") :set-tenant-credential {:credential-id "llm-key" :secret "0123456789abcdef"})
        (execute (as "alice") :set-my-credential {:credential-id "github" :secret "alice-token"})
        (execute (as "bob") :set-my-credential {:credential-id "github" :secret "bob-token"}))
      (let [ctx
            (platform-ctx)

            listed
            (fn [identity]
              (mapv (juxt :credential-id :owner :tenant) (directory/rows ctx identity :credentials)))]
        (is
         (= [["llm-key" "tenant:acme" "acme"]]
            (listed (directory/tenant "acme"))))
        (is
         (= [["github" "user:acme/alice" "acme"]]
            (listed (directory/user "alice"))))
        (is
         (not (string/includes? (pr-str (directory/rows ctx (directory/user "alice") :credentials)) "sealed")))
        (is
         (= 2
            (:personal-values (get (by-id (mock/query (as "root") {:query-id :list-admin-credentials})) "github"))))
        (execute (as "bob") :delete-my-credential {:credential-id "github"})
        (is
         (= []
            (listed (directory/user "bob"))))
        (is
         (= 1
            (:personal-values (get (by-id (mock/query (as "root") {:query-id :list-admin-credentials})) "github"))))))))
