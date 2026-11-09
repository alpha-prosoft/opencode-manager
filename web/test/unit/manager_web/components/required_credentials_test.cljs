(ns manager-web.components.required-credentials-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-web.components.required-credentials :as required]))

(def me
  {:sub "henry"
   :tenant "acme"
   :teams [{:id "ops" :name "Operations" :role "member"}]
   :tenant-teams [{:id "demo" :name "Demo team"} {:id "ops" :name "Operations"}]})

(def my-credentials
  {:credentials [{:id "PAGERDUTY_TOKEN" :name "PagerDuty" :state "missing" :required true
                  :required-by {:tenant true :teams []}
                  :teams [{:team-id "ops" :state "missing" :required true}]}
                 {:id "JIRA_TOKEN" :name "Jira" :state "tenant" :required true
                  :required-by {:tenant false :teams ["ops"]}
                  :teams [{:team-id "demo" :state "tenant" :required false}
                          {:team-id "ops" :state "tenant" :required true}]}
                 {:id "BITBUCKET_TOKEN" :name "Bitbucket" :state "own" :required true
                  :required-by {:tenant false :teams ["ops"]}
                  :teams [{:team-id "ops" :state "own" :required true}]}
                 {:id "NEXUS_TOKEN" :name "Nexus" :state "team" :required false
                  :required-by {:tenant false :teams []}
                  :teams [{:team-id "ops" :state "team" :required false}]}]
   :missing-required ["PAGERDUTY_TOKEN" "JIRA_TOKEN"]})

(deftest what-the-person-still-owes
  (is
   (= ["PAGERDUTY_TOKEN" "JIRA_TOKEN"]
      (mapv :id (required/missing-required my-credentials))))
  (is
   (= []
      (required/missing-required nil))))

(deftest it-says-who-requires-it
  (is
   (= "Required by acme. Only your own value counts, not a team or tenant value."
      (required/required-by-text me (first (:credentials my-credentials)))))
  (is
   (= "Required by Operations. Only your own value counts, not a team or tenant value."
      (required/required-by-text me (second (:credentials my-credentials)))))
  (is
   (= "Required by acme and Operations. Only your own value counts, not a team or tenant value."
      (required/required-by-text me {:required-by {:tenant true :teams ["ops"]}})))
  (is
   (nil? (required/required-by-text me {:required-by {:tenant false :teams []}})))
  (is
   (= [[:tenant "Required by acme (tenant.json)"] [:team "Required by Operations (team.json)"]]
      (required/source-labels me "Operations" {:tenant true :team true}))))

(deftest the-app-is-closed-while-a-required-credential-is-missing
  (let [settled
        (assoc my-credentials :missing-required [])]
    (testing "people with everything their teams require"
      (is
       (= :open
          (required/gate me :home {:data settled}))))
    (testing "until it is known the app waits"
      (is
       (= :loading
          (required/gate me :home {:loading? true})))
      (is
       (= :loading
          (required/gate me :home {}))))
    (testing "a missing one holds the person on setup"
      (is
       (= :setup
          (required/gate me :home {:data my-credentials}))))
    (testing "a refresh on its way decides: setup has just been left"
      (is
       (= :loading
          (required/gate me :home {:data my-credentials :loading? true})))
      (is
       (= :open
          (required/gate me :home {:data settled :loading? true}))))
    (testing "a load that failed says so instead of opening the app"
      (is
       (= :failed
          (required/gate me :home {:error "Service unavailable"})))
      (is
       (= :loading
          (required/gate me :home {:error "Service unavailable" :loading? true}))
       "trying again")
      (is
       (= :setup
          (required/gate me :home {:data my-credentials :error "Service unavailable"}))))
    (testing "a tenant's Admin stays open to its admins and Tenants to operators, to repair a requirement nobody can meet"
      (is
       (= [:open :setup :setup]
          (mapv #(required/gate (assoc me :tenant-admin true) % {:data my-credentials}) [:admin :home :tenants])))
      (is
       (= [:open :setup]
          (mapv #(required/gate (assoc me :operator true) % {:data my-credentials}) [:tenants :admin])))
      (is
       (= :open
          (required/gate (assoc me :tenant-admin true) :admin {:error "Service unavailable"})))
      (is
       (= :setup
          (required/gate me :admin {:data my-credentials}))))
    (testing "operators without a tenant have no credentials"
      (is
       (= :open
          (required/gate {:operator true} :tenants {}))))))

(deftest the-viewer-s-own-state
  (is
   (= [:missing :missing :own nil]
      (mapv #(required/own-state my-credentials "ops" %) ["PAGERDUTY_TOKEN" "JIRA_TOKEN" "BITBUCKET_TOKEN" "OTHER"])))
  (testing "a team that requires it of its members, seen by someone who isn't one"
    (is
     (= [:not-required :own]
        (mapv #(required/own-state my-credentials "demo" %) ["JIRA_TOKEN" "BITBUCKET_TOKEN"])))
    (is
     (= "Required of members, not of you"
        (second (required/own-state-chips :not-required))))))
