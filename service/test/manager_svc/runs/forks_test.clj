(ns manager-svc.runs.forks-test
  "What the runs domain tells and builds for a fork of a workflow's session
   that lands in another team."
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.api :as api]
            [manager-svc.runs.fixtures :as f]
            [manager-svc.test-support :as support]))

(def fork-id #uuid "44444444-4444-4444-4444-444444444444")

(def beta
  {:id "beta" :tenant f/tenant :name "Beta" :budget {:idle-minutes 5}})

(defn- team
  [_ _tenant team-id]
  (get {"alpha" f/team "beta" beta} team-id))

(defn- grants
  [ctx team-id workflow-id]
  (mock/query (support/as-platform ctx) {:query-id :get-fork-grants
                                         :tenant f/tenant
                                         :team-id team-id
                                         :workflow-team-id "alpha"
                                         :workflow-id workflow-id}))

(deftest the-picked-team-is-checked-against-the-harness-of-the-sources-workflow
  (mock/with-mock-dal
    (f/with-fakes [_store]
      (let [{:keys [ctx]}
            (f/env)

            checked
            (atom [])]
        (with-redefs [platform/team team
                      platform/missing-grants (fn [_ tenant team-id harness]
                                                (swap! checked conj [tenant team-id (:llm harness)])
                                                ["team beta may not use llm connection main"])]
          (is
           (= {:team-id "beta" :team-name "Beta" :known true :missing ["team beta may not use llm connection main"]}
              (grants ctx "beta" "workspace")))
          (is
           (= [["acme" "beta" {:connection "main" :models ["model-a" "model-b"]}]]
              @checked))
          (testing "a workflow that is gone is reported when the fork is prepared"
            (is
             (= {:team-id "beta" :team-name "Beta" :known true :missing []}
                (grants ctx "beta" "gone"))))
          (testing "a team the tenant doesn't have is said so"
            (is
             (= {:team-id "gone" :team-name nil :known false}
                (select-keys (grants ctx "gone" "workspace") [:team-id :team-name :known])))))))))

(deftest a-forks-sandbox-is-the-sources-workflow-in-the-forks-team
  (f/with-fakes [_store]
    (let [read
          (atom [])

          fork
          {:id fork-id :tenant f/tenant :team-id "beta" :labels {:workflow "workspace" :workflow-team "alpha"}}]
      (with-redefs [platform/team team
                    platform/definition (fn [_ tenant team-id kind id]
                                          (swap! read conj team-id)
                                          (when (= "alpha" team-id)
                                            (f/definition tenant team-id kind id)))]
        (is
         (= {:tenant "acme" :team-id "beta" :idle-minutes 5 :llm-connection "main"}
            (select-keys (api/fork-sandbox {:settings support/settings} fork)
                         [:tenant :team-id :idle-minutes :llm-connection]))
         "a session of the fork's team, under that team's limits")
        (is
         (= #{"alpha"}
            (set @read))
         "built from the definitions of the team whose workflow it is")
        (testing "a fork within the team runs its own workflow"
          (is
           (= "alpha"
              (:team-id (api/fork-sandbox {:settings support/settings}
                                          {:id fork-id :tenant f/tenant :team-id "alpha"
                                           :labels {:workflow "workspace"}})))))
        (testing "a workflow that is gone builds nothing"
          (is
           (nil? (api/fork-sandbox {:settings support/settings} (assoc-in fork [:labels :workflow] "gone")))))))))
