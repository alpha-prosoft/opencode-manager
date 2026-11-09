(ns manager-svc.runs.authorization-test
  "Who may start, see, cancel and retry runs, list pull requests and
   schedules, as the handlers decide it."
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.runs.fixtures :as f]))

(def run-id #uuid "44444444-4444-4444-4444-444444444444")

(defn- outcome
  "The status a call fails with, or :ok."
  [f]
  (try
    (f)
    :ok
    (catch clojure.lang.ExceptionInfo e
      (or (:status (ex-data e))
          (get-in (ex-data e) [:error :status])
          (ex-message e)))))

(defn- start!
  [ctx user]
  (f/command! ctx user {:cmd-id :start-run
                        :id run-id
                        :attrs {:team-id "alpha" :workflow-id "implement" :inputs {:task "Add a health endpoint"}}}))

(deftest people-outside-the-team-cannot-see-or-touch-its-runs
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx]}
            (f/env)]
        (start! ctx "alice")
        (is
         (= {:get-run 404 :cancel-run 404 :retry-run 404}
            {:get-run (outcome #(f/query ctx "mallory" {:query-id :get-run :run-id run-id}))
             :cancel-run (outcome #(f/command! ctx "mallory" {:cmd-id :cancel-run :id run-id}))
             :retry-run (outcome #(f/command! ctx "mallory" {:cmd-id :retry-run :id (random-uuid)
                                                             :attrs {:source-id run-id}}))}))
        (testing "nor list them by naming the team"
          (is
           (= []
              (f/query ctx "mallory" {:query-id :list-runs :team-id "alpha"}))))))))

(deftest the-creator-cancels-their-own-run
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx]}
            (f/env)]
        (start! ctx "alice")
        (is
         (= :ok
            (outcome #(f/command! ctx "alice" {:cmd-id :cancel-run :id run-id}))))))))

(deftest any-member-may-retry-a-teammates-run
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx]}
            (f/env)]
        (start! ctx "alice")
        (is
         (= :ok
            (outcome #(f/command! ctx "bob" {:cmd-id :retry-run :id (random-uuid) :attrs {:source-id run-id}}))))))))

(deftest a-run-shows-its-session-only-to-those-who-may-see-it
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [{:keys [ctx system]}
            (f/env)

            session-id
            #uuid "55555555-5555-5555-5555-555555555555"]
        (swap! store assoc session-id {:id session-id :sub "alice" :visibility :private :secret-key "pw"})
        (start! ctx "alice")
        ((get-in system [:test/edd :command]) {:cmd-id :record-run-session :id run-id
                                               :attrs {:session-id session-id :interactive true :models ["a"]}})
        (is
         (= {"alice" session-id "bob" nil}
            (into {} (for [user ["alice" "bob"]]
                       [user (get-in (f/query ctx user {:query-id :get-run :run-id run-id}) [:session :id])]))))))))

(deftest open-pull-requests-are-listed-for-team-members-with-their-credential
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [asked
            (atom nil)

            {:keys [ctx]}
            (f/env {:runs/scm (fn [provider token]
                                (reset! asked [provider token])
                                {:open-pull-requests (fn [_]
                                                       [{:id 3 :title "Old" :author "x" :source-branch "a"}
                                                        {:id 7 :title "New" :author "y" :source-branch "b"}])})})

            list-for
            (fn [user workflow-id]
              (f/query ctx user {:query-id :list-open-pull-requests :team-id "alpha" :workflow-id workflow-id}))]
        (is
         (= [{:id 7 :title "New" :author "y" :branch "b"}
             {:id 3 :title "Old" :author "x" :branch "a"}]
            (list-for "bob" "implement")))
        (is
         (= ["github" "token-gh-token"]
            @asked))
        (testing "a workflow without pull requests lists none"
          (is
           (= []
              (list-for "bob" "analyse"))))
        (testing "unknown workflows and other teams are not found"
          (is
           (= {:unknown-workflow 404 :stranger 404}
              {:unknown-workflow (outcome #(list-for "bob" "nope"))
               :stranger (outcome #(list-for "mallory" "implement"))})))))))

(deftest schedules-are-listed-for-platform-admins-only
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx]}
            (f/env)]
        (is
         (= {"carol" 403 "root" :ok}
            (into {} (for [user ["carol" "root"]]
                       [user (outcome #(f/query ctx user {:query-id :list-schedules}))]))))))))
