(ns manager-svc.webhooks.core-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [edd.common :as common]
            [edd.test.fixture.dal :as mock]
            [manager-svc.directory :as directory]
            [manager-svc.test-support :as support]
            [manager-svc.webhooks.edge :as edge]
            [manager-svc.webhooks.fixtures :as fixtures :refer [as attempt config save! with-platform]]
            [manager-svc.webhooks.secrets :as secrets]))

(def hook-id
  #uuid "6f1e0a52-8b7c-4c3e-9a51-0d2f6c1b7e10")

(def other-id
  #uuid "0b6f4f0e-3c1d-4f0e-8d44-5a2b1e9c7f21")

(defn- query
  [username q]
  (attempt #(mock/query (as username) q)))

(defn- stored
  [id]
  (common/get-by-id (fixtures/platform-ctx) {:id id}))

(deftest a-team-admin-saves-a-webhook-whose-secret-is-only-stored-sealed
  (mock/with-mock-dal
    (with-platform
      (is
       (= {:success true}
          (save! "dave" hook-id (config))))
      (testing "plaintext never reaches events, aggregates or the request log"
        (is
         (not (string/includes? (pr-str (mock/peek-state)) "s3cret-token-1"))))
      (testing "the sealed value opens only for this webhook and tenant"
        (is
         (= "s3cret-token-1"
            (:secret (secrets/open fixtures/settings "acme" hook-id (:sealed (stored hook-id)))))))
      (is
       (= {:error "The secret was not sealed for this webhook" :status 422}
          (attempt #(secrets/open fixtures/settings "globex" hook-id (:sealed (stored hook-id))))))
      (testing "the team directory lists it, without the secret"
        (let [[row]
              (directory/rows (fixtures/platform-ctx) (directory/team "acme" "alpha") :webhooks)]
          (is
           (= ["Ticket intake" :header]
              [(:name row) (:auth-type row)]))
          (is
           (nil? (:sealed row))))))))

(deftest who-may-manage-which-scope
  (mock/with-mock-dal
    (with-platform
      (testing "members and admins of other teams can't add a team's webhook"
        (is
         (= 403
            (:status (save! "alice" hook-id (config)))))
        (is
         (= 403
            (:status (save! "erin" hook-id (config))))))
      (testing "team admins can't add tenant webhooks; tenant admins can"
        (is
         (= 403
            (:status (save! "dave" hook-id (config {:scope {:kind "tenant"}})))))
        (is
         (= {:success true}
            (save! "root" hook-id (config {:scope {:kind "tenant"}})))))
      (testing "a project webhook belongs to its team's admins"
        (is
         (= {:success true}
            (save! "dave" other-id (config {:scope {:kind "project" :team-id "alpha" :project-id "app"}})))))
      (testing "the scope of a saved webhook can't change"
        (is
         (= 422
            (:status (save! "dave" other-id (config))))))
      (testing "a team's webhook starts that team's workflows"
        (is
         (= 422
            (:status (save! "root" (random-uuid) (config {:target {:team-id "beta" :workflow-id "analyse"}})))))))))

(deftest invalid-configurations-are-refused-with-reasons
  (mock/with-mock-dal
    (with-platform
      (is
       (= {:error "The workflow has no input title" :status 422}
          (save! "dave" hook-id (config {:mapping [{:input "title" :source "constant" :value "x"}]}))))
      (is
       (= {:error "Workflow alpha/nope does not exist or is invalid" :status 422}
          (save! "dave" hook-id (config {:target {:team-id "alpha" :workflow-id "nope"}}))))
      (is
       (= {:error "A secret is required for this auth type" :status 422}
          (save! "dave" hook-id (config {:auth {:type "bearer"}}))))
      (is
       (= {:error "nonsense is not an IP address or CIDR range" :status 422}
          (save! "dave" hook-id (config {:allowed-ips ["10.0.0.0/8" "nonsense"]}))))
      (is
       (= {:error "The header name can't be Authorization" :status 422}
          (save! "dave" hook-id (config {:auth {:type "hmac" :header-name "Authorization" :secret "long-enough"}}))))
      (testing "auth none needs no secret"
        (is
         (= {:success true}
            (save! "dave" hook-id (config {:auth {:type "none"}}))))))))

(deftest people-see-the-webhooks-of-their-teams-without-secrets
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config))
      (save! "root" other-id (config {:name "Tenant hook" :scope {:kind "tenant"}
                                      :target {:team-id "beta" :workflow-id "analyse"}}))
      (testing "the team admin sees the hint and may manage it"
        (let [[hook]
              (query "dave" {:query-id :list-webhooks})]
          (is
           (= [hook-id true]
              [(:id hook) (:can-manage hook)]))
          (is
           (= "…en-1"
              (get-in (query "dave" {:query-id :get-webhook :webhook-id hook-id}) [:auth :hint])))))
      (testing "a member sees it, never a hint, and can't manage it"
        (let [hook
              (query "alice" {:query-id :get-webhook :webhook-id hook-id})]
          (is
           (= [false nil "/hooks/acme/6f1e0a52-8b7c-4c3e-9a51-0d2f6c1b7e10"]
              [(:can-manage hook) (get-in hook [:auth :hint]) (:endpoint-path hook)]))
          (is
           (nil? (:sealed hook)))))
      (testing "the tenant admin sees every webhook of the tenant"
        (is
         (= #{"Ticket intake" "Tenant hook"}
            (set (map :name (query "root" {:query-id :list-webhooks}))))))
      (testing "a tenant webhook shows to the members of the team it targets"
        (is
         (= ["Tenant hook"]
            (mapv :name (query "erin" {:query-id :list-webhooks})))))
      (testing "filters by team and project"
        (is
         (= ["Ticket intake"]
            (mapv :name (query "root" {:query-id :list-webhooks :team-id "alpha"}))))
        (is
         (= []
            (query "root" {:query-id :list-webhooks :project-id "app"}))))
      (testing "another team's admin gets 403, another tenant 404"
        (is
         (= 403
            (:status (query "erin" {:query-id :get-webhook :webhook-id hook-id}))))
        (is
         (= 404
            (:status (query "gina" {:query-id :get-webhook :webhook-id hook-id}))))
        (is
         (= []
            (query "gina" {:query-id :list-webhooks})))))))

(deftest rotating-and-deleting
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config))
      (let [sealed
            (secrets/seal fixtures/settings "acme" hook-id {:secret "rotated-secret-1234"})]
        (testing "only those who manage it re-key it"
          (is
           (= 403
              (:status (attempt #(support/apply! (as "alice") {:cmd-id :set-webhook-secret :id hook-id
                                                               :attrs {:sealed sealed}}))))))
        (support/apply! (as "dave") {:cmd-id :set-webhook-secret :id hook-id :attrs {:sealed sealed}})
        (is
         (= "…1234"
            (get-in (query "dave" {:query-id :get-webhook :webhook-id hook-id}) [:auth :hint]))))
      (is
       (= 403
          (:status (attempt #(support/apply! (as "bob") {:cmd-id :delete-webhook :id hook-id})))))
      (support/apply! (as "dave") {:cmd-id :delete-webhook :id hook-id})
      (is
       (= 404
          (:status (query "dave" {:query-id :get-webhook :webhook-id hook-id}))))
      (is
       (= []
          (query "dave" {:query-id :list-webhooks})))
      (is
       (nil? (:sealed (stored hook-id)))))))

(deftest targets-and-dry-runs
  (mock/with-mock-dal
    (with-platform
      (let [{:keys [tenant-scope teams]}
            (query "dave" {:query-id :list-webhook-targets})]
        (is
         (false? tenant-scope))
        (is
         (= [["alpha" true ["analyse" "review" "triage"]]]
            (mapv (juxt :id :manage #(sort (map :id (:workflows %)))) teams)))
        (is
         (= [:brief]
            (keys (get-in (first (filter #(= "analyse" (:id %)) (:workflows (first teams)))) [:inputs-schema :properties])))))
      (testing "a preview maps a sample request and validates it, starting nothing"
        (is
         (= {:inputs {"brief" "Login fails on Safari"} :missing [] :errors [] :valid true}
            (query "alice" {:query-id :preview-webhook
                            :target {:team-id "alpha" :workflow-id "analyse"}
                            :mapping [{:input "brief" :source "body" :value "$.issue.summary"}]
                            :request {:body "{\"issue\": {\"summary\": \"Login fails on Safari\"}}"}})))
        (is
         (= {:inputs {"brief" "short"} :missing [] :errors [{:field "brief" :message "should be at least 10 characters"}]
             :valid false}
            (query "alice" {:query-id :preview-webhook
                            :target {:team-id "alpha" :workflow-id "analyse"}
                            :mapping [{:input "brief" :source "header" :value "X-Brief"}]
                            :request {:headers {"x-brief" "short"}}})))
        (is
         (= 404
            (:status (query "gina" {:query-id :preview-webhook
                                    :target {:team-id "beta" :workflow-id "analyse"}
                                    :mapping []
                                    :request {}}))))))))

(def hmac-auth
  {:type "hmac" :header-name "X-Hub-Signature-256" :secret "hmac-secret-99"})

(deftest a-signed-webhook-takes-only-what-its-signature-covers
  (mock/with-mock-dal
    (with-platform
      (doseq [source ["query" "header"]]
        (is
         (= {:error "A signed (HMAC) webhook maps inputs from the body only: the signature doesn't cover the query string or headers"
             :status 422}
            (save! "dave" hook-id (config {:auth hmac-auth
                                           :mapping [{:input "brief" :source source :value "brief"}]})))
         source))
      (is
       (= {:error "A signed (HMAC) webhook can't allow GET: a GET request has no body to sign" :status 422}
          (save! "dave" hook-id (config {:auth hmac-auth :allow-get true}))))
      (testing "the body, the raw body and constants are fine"
        (doseq [row [{:input "brief" :source "body" :value "$.issue.summary"}
                     {:input "brief" :source "raw-body"}
                     {:input "brief" :source "constant" :value "Weekly report of the team"}]]
          (is
           (= {:success true}
              (save! "dave" hook-id (config {:auth hmac-auth :mapping [row]})))
           (:source row))))
      (testing "other auth types still map the query and headers and may allow GET"
        (is
         (= {:success true}
            (save! "dave" other-id (config {:allow-get true
                                            :mapping [{:input "brief" :source "query" :value "brief"}]}))))))))

(deftest a-projects-webhook-never-starts-another-projects-workflow
  (mock/with-mock-dal
    (with-platform
      (is
       (= {:error "Workflow triage belongs to project shop, not app" :status 422}
          (save! "dave" hook-id (config {:scope {:kind "project" :team-id "alpha" :project-id "app"}
                                         :target {:team-id "alpha" :workflow-id "triage"}}))))
      (is
       (= {:success true}
          (save! "dave" hook-id (config {:scope {:kind "project" :team-id "alpha" :project-id "shop"}
                                         :target {:team-id "alpha" :workflow-id "triage"}}))))
      (testing "the team's own workflows serve every project, and the team's webhooks every workflow"
        (is
         (= [{:success true} {:success true}]
            [(save! "dave" other-id (config {:scope {:kind "project" :team-id "alpha" :project-id "app"}}))
             (save! "dave" (random-uuid) (config {:target {:team-id "alpha" :workflow-id "triage"}}))]))))))

(defn- command-status
  [username command]
  (let [outcome
        (attempt #(support/apply! (as username) command))]
    (or (:status outcome) (:error outcome) :ok)))

(deftest another-tenant-changes-nothing-of-a-webhook
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config))
      (let [before
            (stored hook-id)

            sealed
            (secrets/seal fixtures/settings "globex" hook-id {:secret "globex-secret-1234"})]
        (is
         (= {:save 404 :re-key 404 :delete 404}
            {:save (:status (save! "gina" hook-id (config)))
             :re-key (command-status "gina" {:cmd-id :set-webhook-secret :id hook-id :attrs {:sealed sealed}})
             :delete (command-status "gina" {:cmd-id :delete-webhook :id hook-id})}))
        (is
         (= before
            (stored hook-id)))
        (testing "nor does the platform find it under the other tenant"
          (is
           (= {:webhook nil}
              (mock/query (fixtures/platform-ctx) {:query-id :get-webhook-for-delivery
                                                   :tenant "globex"
                                                   :webhook-id hook-id}))))))))

(deftest only-the-documented-fields-are-saved
  (mock/with-mock-dal
    (with-platform
      (testing "the command refuses anything else"
        (doseq [attrs [(assoc (dissoc (config) :auth) :auth {:type "none"} :note "x")
                       (assoc (dissoc (config) :auth) :auth {:type "none" :secret "plain-text-secret"})
                       (assoc (dissoc (config) :auth) :auth {:type "none"} :mapping 5)
                       (assoc (dissoc (config) :auth) :auth {:type "none"} :scope {:kind 5})]]
          (is
           (not= :ok
                 (command-status "dave" {:cmd-id :save-webhook :id (random-uuid) :attrs attrs})))))
      (testing "and the edge sends nothing else: no secret, no stray keys, no sealed value of the caller's making"
        (is
         (= {:name "Ticket intake"
             :scope {:kind "team" :team-id "alpha"}
             :target {:team-id "alpha" :workflow-id "analyse"}
             :auth {:type "none"}
             :mapping [{:input "brief" :source "body" :value "$.issue.summary"} {}]}
            (:attrs (edge/save-command fixtures/settings (fixtures/identity-of "dave") hook-id
                                       (assoc (config)
                                              :auth {:type "none" :hint "…1234" :password "x"}
                                              :mapping [{:input "brief" :source "body" :value "$.issue.summary" :label "Brief"} 7]
                                              :sealed "made-up"
                                              :can-manage true
                                              :deliveries [{:id 1}]))))))
      (testing "a body of the wrong shape is refused with a reason, not an exception"
        (is
         (= [422 422]
            [(:status (save! "dave" hook-id (config {:auth "none"})))
             (:status (save! "dave" hook-id (config {:scope "team"})))]))))))

(deftest re-keying-shows-in-the-list-and-the-hint-follows-the-username
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config {:auth {:type "basic" :username "ci" :secret "password-123456"}}))
      (support/apply! (as "root") {:cmd-id :set-webhook-secret :id hook-id
                                   :attrs {:sealed (secrets/seal fixtures/settings "acme" hook-id {:secret "rotated-secret-1234"})}})
      (is
       (= "root"
          (:updated-by (first (query "dave" {:query-id :list-webhooks})))))
      (is
       (= "ci · …1234"
          (get-in (query "dave" {:query-id :get-webhook :webhook-id hook-id}) [:auth :hint])))
      (testing "a new username without a new secret"
        (save! "dave" hook-id (config {:auth {:type "basic" :username "jenkins"}}))
        (is
         (= "jenkins · …1234"
            (get-in (query "dave" {:query-id :get-webhook :webhook-id hook-id}) [:auth :hint])))))))

(deftest previews-are-for-the-target-teams-members
  (mock/with-mock-dal
    (with-platform
      (let [preview
            (fn [username mapping]
              (query username {:query-id :preview-webhook
                               :target {:team-id "alpha" :workflow-id "analyse"}
                               :mapping mapping
                               :request {:body "{}"}}))]
        (testing "someone of the tenant outside the team is told why"
          (is
           (= {:error "Only members of team alpha try its webhooks" :status 403}
              (preview "erin" []))))
        (testing "a mapping that couldn't be saved answers its problems"
          (is
           (= {:inputs {}
               :missing []
               :errors [{:field nil :message "Every mapped row names an input"}
                        {:field nil :message "Unknown source for a row without an input"}]
               :valid false}
              (preview "alice" [{:value "x"}])))
          (is
           (= [{:field nil :message "Input brief is mapped twice"}]
              (:errors (preview "alice" [{:input "brief" :source "constant" :value "a"}
                                         {:input "brief" :source "raw-body"}])))))))))
