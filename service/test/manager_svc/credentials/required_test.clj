(ns manager-svc.credentials.required-test
  "Credentials people must provide themselves: required by tenant.json or by
   a team's team.json, satisfied only by the person's own value."
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.credentials.api :as api]
            [manager-svc.credentials.fixtures :as fixtures :refer [as execute platform-ctx with-platform]]
            [manager-svc.credentials.required :as required]
            [manager-svc.http :as http]
            [manager-svc.test-support :as support]))

(def required-spec
  "GitHub is required by the tenant; alpha's team.json requires Jira and
   Bitbucket, beta's Sonar."
  (assoc-in fixtures/tenant-spec [:credentials 0 :required] true))

(def team-requirements
  {"alpha" ["jira" "bitbucket"]
   "beta" ["sonar"]})

(defmacro with-requirements
  [& body]
  `(with-redefs [fixtures/tenant-spec required-spec]
     (binding [fixtures/*required-credentials* team-requirements]
       (with-platform ~@body))))

(defn- fred
  "In both acme teams."
  []
  (support/as-user (fixtures/ctx) "fred" {:teams {"alpha" :member "beta" :member}}))

(defn- mine
  [ctx]
  (mock/query ctx {:query-id :get-my-credentials}))

(defn- by-id
  [items]
  (into {} (map (juxt :id identity)) items))

(defn- required-by
  [ctx]
  (into {} (for [{:keys [id required-by]} (:credentials (mine ctx))]
             [id required-by])))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      {:status (:status (ex-data e)) :message (ex-message e)})))

(deftest who-requires-what
  (let [alpha
        {:id "alpha" :required-credentials ["jira"]}

        beta
        {:id "beta" :required-credentials []}

        github
        {:id "github" :teams "*" :required true}

        jira
        {:id "jira" :teams ["alpha"]}

        sonar
        {:id "sonar" :teams ["beta"] :required true}]
    (is
     (= {:tenant true :teams []}
        (required/required-by github [alpha beta])))
    (is
     (= {:tenant false :teams ["alpha"]}
        (required/required-by jira [alpha beta])))
    (testing "a tenant requirement applies only in the teams the credential is for"
      (is
       (= {:tenant false :teams []}
          (required/required-by sonar [alpha])))
      (is
       (= [false true]
          [(required/required-in? sonar alpha) (required/required-in? sonar beta)])))
    (is
     (= "Team Alpha requires your own Jira credential. Add it under Accounts, then start again; team and tenant values don't count."
        (required/missing-message "Alpha" ["Jira"])))
    (is
     (= "Team Alpha requires your own GitHub, Jira and Bitbucket credentials. Add them under Accounts, then start again; team and tenant values don't count."
        (required/missing-message "Alpha" ["GitHub" "Jira" "Bitbucket"])))))

(deftest required-for-me-is-the-tenant-s-and-every-one-of-my-teams
  (mock/with-mock-dal
    (with-requirements
      (testing "fred, in alpha and beta, owes what the tenant and both teams require"
        (let [{:keys [missing-required pending]}
              (mine (fred))]
          (is
           (= ["github" "jira" "sonar" "bitbucket"]
              missing-required))
          (is
           (= missing-required
              pending)
           "required ones lead the setup list")
          (is
           (= {"github" {:tenant true :teams []}
               "jira" {:tenant false :teams ["alpha"]}
               "sonar" {:tenant false :teams ["beta"]}
               "bitbucket" {:tenant false :teams ["alpha"]}
               "nexus" {:tenant false :teams []}
               "llm-key" {:tenant false :teams []}}
              (required-by (fred))))))
      (testing "alice, only in alpha, owes nothing to beta"
        (is
         (= ["github" "jira" "bitbucket"]
            (:missing-required (mine (as "alice"))))))
      (testing "erin, admin of beta, owes the tenant's and beta's"
        (is
         (= ["github" "sonar"]
            (:missing-required (mine (as "erin"))))))
      (testing "a tenant admin in no team owes nothing, in no team"
        (let [{:keys [credentials missing-required]}
              (mine (as "root"))]
          (is
           (= []
              missing-required))
          (is
           (= {:tenant false :teams []}
              (:required-by (get (by-id credentials) "github"))))
          (is
           (= [{:team-id "alpha" :state :missing :required false}
               {:team-id "beta" :state :missing :required false}]
              (:teams (get (by-id credentials) "github"))))
          (is
           (= [{:team-id "beta" :state :missing :required false}]
              (:teams (get (by-id credentials) "sonar"))))
          (is
           (nil? (api/required-credentials-dep (as "root") "acme" "alpha"))
           "and starting work there asks nothing of them either")))
      (testing "having seen them doesn't make them optional"
        (execute (fred) :mark-credentials-seen {:credentials ["github" "jira" "sonar" "bitbucket"]})
        (is
         (= ["github" "jira" "sonar" "bitbucket"]
            (:pending (mine (fred)))))))))

(deftest only-the-person-s-own-value-satisfies-a-requirement
  (mock/with-mock-dal
    (with-requirements
      (with-redefs [http/request! (fn [_] {:status 200 :headers {} :body ""})]
        (execute (as "root") :set-tenant-credential {:credential-id "github" :secret "tenant-token"})
        (execute (as "dave") :set-team-credential {:credential-id "bitbucket" :team-id "alpha" :secret "team-token"}))
      (let [{:keys [credentials missing-required]}
            (mine (as "alice"))

            items
            (by-id credentials)]
        (is
         (= [:tenant :team]
            [(get-in items ["github" :state]) (get-in items ["bitbucket" :state])])
         "the shared values are used for calls")
        (is
         (= ["github" "jira" "bitbucket"]
            missing-required)
         "but don't satisfy the requirement"))
      (with-redefs [http/request! (fn [_] {:status 200 :headers {} :body ""})]
        (execute (as "alice") :set-my-credential {:credential-id "github" :secret "alice-github"})
        (execute (as "alice") :set-my-credential {:credential-id "jira" :secret "alice-jira" :username "alice"})
        (execute (as "alice") :set-my-credential {:credential-id "bitbucket" :secret "alice-bitbucket"}))
      (is
       (= {:missing-required [] :pending []}
          (select-keys (mine (as "alice")) [:missing-required :pending])))
      (testing "removing one makes it owed again"
        (execute (as "alice") :delete-my-credential {:credential-id "jira"})
        (is
         (= ["jira"]
            (:missing-required (mine (as "alice"))))))
      (testing "someone else's own value doesn't count for alice"
        (is
         (= ["github" "jira" "bitbucket"]
            (:missing-required (mine (as "bob")))))))))

(deftest commands-that-start-work-read-what-the-person-lacks
  (mock/with-mock-dal
    (with-requirements
      (with-redefs [http/request! (fn [_] {:status 200 :headers {} :body ""})]
        (execute (as "alice") :set-my-credential {:credential-id "github" :secret "alice-github"}))
      (testing "the dependency query names the team and what is missing"
        (is
         (= {:team-id "alpha" :team-name "Alpha" :missing [{:id "jira" :name "Jira"} {:id "bitbucket" :name "Bitbucket"}]}
            (mock/query (platform-ctx) {:query-id :get-missing-required-credentials
                                        :tenant "acme" :team-id "alpha" :user "alice"})))
        (is
         (= {:team-id "nope" :team-name "nope" :missing []}
            (mock/query (platform-ctx) {:query-id :get-missing-required-credentials
                                        :tenant "acme" :team-id "nope" :user "alice"}))))
      (testing "people can't call it"
        (is
         (= "Forbidden"
            (:message (failure #(mock/query (as "alice") {:query-id :get-missing-required-credentials
                                                          :tenant "acme" :team-id "alpha" :user "alice"}))))))
      (testing "the dependency applies to people whose claims name the team"
        (is
         (= {:query-id :get-missing-required-credentials :tenant "acme" :team-id "alpha" :user "alice"}
            (api/required-credentials-dep (as "alice") "acme" "alpha")))
        (is
         (= {:query-id :get-missing-required-credentials :tenant "acme" :team-id "beta" :user "erin"}
            (api/required-credentials-dep (as "erin") "acme" "beta"))
         "its admins among them")
        (is
         (= {:query-id :get-missing-required-credentials :tenant "acme" :team-id "alpha" :user "rita"}
            (api/required-credentials-dep (support/as-user (fixtures/ctx) "rita" {:tenant-admin true :teams {"alpha" :member}})
                                          "acme" "alpha"))
         "and a tenant admin who is a member")
        (is
         (= [nil nil nil nil]
            [(api/required-credentials-dep (as "alice") "acme" "beta")
             (api/required-credentials-dep (as "root") "acme" "beta")
             (api/required-credentials-dep (as "hank") "acme" "alpha")
             (api/required-credentials-dep (platform-ctx) "acme" "alpha")])
         "not to someone forking from outside the team, a tenant admin who isn't a member, another tenant or the platform"))
      (testing "what starting work asks is what :get-my-credentials lists as missing"
        (doseq [[ctx team-ids] [[(as "alice") ["alpha" "beta"]]
                                [(as "erin") ["alpha" "beta"]]
                                [(as "root") ["alpha" "beta"]]
                                [(fred) ["alpha" "beta"]]]]
          (is
           (= (set (:missing-required (mine ctx)))
              (set (for [team-id team-ids
                         :let [dep (api/required-credentials-dep ctx "acme" team-id)]
                         :when dep
                         credential (:missing (mock/query (platform-ctx) dep))]
                     (:id credential)))))))
      (testing "a command fails with 422 naming the team and the credentials"
        (is
         (= {:status 422
             :message "Team Alpha requires your own Jira and Bitbucket credentials. Add them under Accounts, then start again; team and tenant values don't count."}
            (failure #(api/require-credentials!
                       (mock/query (platform-ctx) {:query-id :get-missing-required-credentials
                                                   :tenant "acme" :team-id "alpha" :user "alice"})))))
        (is
         (nil? (failure #(api/require-credentials! {:team-id "beta" :missing []}))))
        (is
         (nil? (failure #(api/require-credentials! nil))))))))

(deftest teams-show-what-they-require-of-their-members
  (mock/with-mock-dal
    (with-requirements
      (is
       (= [{:id "github" :name "GitHub" :description "" :kind :token :required-by {:tenant true :team false}}
           {:id "jira" :name "Jira" :description "" :kind :basic :required-by {:tenant false :team true}}
           {:id "bitbucket" :name "Bitbucket" :description "" :kind :token :required-by {:tenant false :team true}}]
          (mock/query (as "alice") {:query-id :list-required-credentials :team-id "alpha"})))
      (is
       (= ["github" "sonar"]
          (mapv :id (mock/query (as "erin") {:query-id :list-required-credentials :team-id "beta"}))))
      (testing "only the team's members and admins read it"
        (is
         (= 403
            (:status (failure #(mock/query (as "erin") {:query-id :list-required-credentials :team-id "alpha"}))))))
      (testing "another tenant's team of the same name is its own"
        (is
         (= []
            (mock/query (as "hank") {:query-id :list-required-credentials :team-id "alpha"}))))
      (testing "tenant admins see who requires each credential"
        (let [items
              (by-id (mock/query (as "root") {:query-id :list-admin-credentials}))]
          (is
           (= {"github" [true []] "jira" [false ["alpha"]] "sonar" [false ["beta"]] "bitbucket" [false ["alpha"]]
               "nexus" [false []]}
              (into {} (for [id ["github" "jira" "sonar" "bitbucket" "nexus"]]
                         [id ((juxt :required :required-by-teams) (get items id))])))))))))
