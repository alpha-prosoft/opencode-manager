(ns manager-web.components.forms-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.credential-form :as credential-form]
            [manager-web.components.schema-form :as schema-form]
            [manager-web.components.workflows :as workflows]))

(deftest set-input-drops-empty-values
  (is
   (= {:a 1}
      (schema-form/set-input {:a 1 :b "x"} :b "")))
  (is
   (= {:a 1 :b 2}
      (schema-form/set-input {:a 1} :b 2))))

(deftest nullable-any-of-collapses
  (is
   (= {:type "string" :description "Branch" :default "main"}
      (schema-form/resolve-field {:anyOf [{:type "string"} {:type "null"}] :description "Branch" :default "main"}))))

(deftest start-run-spec
  (is
   (= {:id ::workflows/start-run
       :command {:cmd-id :start-run :id 7 :attrs {:team-id "demo" :workflow-id "review" :inputs {:pull-request 3}}}
       :navigate [:run {:run-id "7"}]
       :refetch-on-failure [:my-credentials]
       :on-success [::workflows/close]}
      (workflows/start-run-spec {:team-id "demo" :workflow {:id "review"} :inputs {:pull-request 3}} 7))))

(deftest credential-save-spec
  (is
   (= {:id [::credential-form/save :mine "git/hub"]
       :method "PUT"
       :path "/api/credentials/git%2Fhub"
       :body {:secret "s"}
       :edd? true
       :refetch [:my-credentials]
       :on-success [:saved]}
      (credential-form/save-spec {:scope :mine :credential-id "git/hub" :value {:secret "s"}
                                  :refetch [:my-credentials] :on-saved [:saved]}))))

(deftest credential-save-paths-per-scope
  (is
   (= "/api/admin/credentials/JIRA"
      (credential-form/save-path :tenant "JIRA")))
  (is
   (= "/api/teams/demo/credentials/JIRA"
      (credential-form/save-path [:team "demo"] "JIRA")))
  (is
   (= [::credential-form/save [:team "demo"] "JIRA"]
      (credential-form/run-id [:team "demo"] "JIRA"))))

(deftest a-schema-with-a-pull-request-offers-the-open-ones
  (is
   (true? (schema-form/takes-pull-request? {:properties {:pull_request {:type "integer"}}})))
  (is
   (false? (schema-form/takes-pull-request? {:properties {:branch {:type "string"}}}))))

(deftest the-picker-names-changes-as-their-provider-does
  (is
   (= ["Change 12 Fix login (erin)" "PR #12 Fix login (erin)"]
      (mapv #(schema-form/pull-request-label % {:id 12 :title "Fix login" :author "erin"}) ["gerrit" :github]))))

(deftest credential-values-are-cleared-per-scope
  (is
   (= [{:cmd-id :delete-my-credential :attrs {:credential-id "JIRA"}}
       {:cmd-id :delete-team-credential :attrs {:team-id "demo" :credential-id "JIRA"}}
       {:cmd-id :delete-tenant-credential :attrs {:credential-id "JIRA"}}]
      (mapv #(credential-form/clear-command % "JIRA") [:mine [:team "demo"] :tenant])))
  (let [request
        (credential-form/clear-request [:team "demo"] {:id "JIRA" :name "Jira"}
                                       {:error :shown-here :refetch [[:team-credentials "demo"]]})]
    (is
     (= ["Clear the team value of Jira?" "Clear" :shown-here [[:team-credentials "demo"]]]
        [(:title request) (:confirm request) (get-in request [:run :error]) (get-in request [:run :refetch])])))
  (is
   (= "Clear the tenant value of Jira?"
      (:title (credential-form/clear-request :tenant {:id "JIRA" :name "Jira"} {})))))

(deftest the-dialog-describes-the-value-of-its-scope
  (let [credential
        {:id "JIRA" :name "Jira" :help "An API token" :test {:url "https://jira"} :provided-by ["tenant" "user"]}]
    (is
     (= {:title "Your Jira credential" :help "An API token" :checks? true}
        (credential-form/describe :mine credential)))
    (is
     (= ["Team value of Jira" "An API token" true]
        ((juxt :title :help :checks?) (credential-form/describe [:team "demo"] credential))))
    (is
     (= {:title "Tenant value of Jira"
         :help "Used by everyone who hasn't added their own, and by automated runs."}
        (credential-form/describe :tenant credential)))))
