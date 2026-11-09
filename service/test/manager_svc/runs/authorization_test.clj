(ns manager-svc.runs.authorization-test
  "Who may start, see, cancel and retry runs, list pull requests and
   schedules, as the handlers decide it."
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.runs.fixtures :as f]
            [manager-svc.runs.triggers :as triggers]))

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
         (= {:get-run 403 :cancel-run 403 :retry-run 403}
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
      (let [{:keys [ctx system]}
            (f/env)]
        (start! ctx "alice")
        ((get-in system [:test/edd :command]) {:cmd-id :fail-run :id run-id :attrs {:message "boom"}})
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
            (f/env {:runs/scm (fn [{:keys [provider]} {:keys [secret]}]
                                (reset! asked [provider secret])
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
        (testing "unknown workflows and teams are not found; a team of the tenant the person isn't in is forbidden"
          (is
           (= {:unknown-workflow 404 :unknown-team 404 :stranger 403}
              {:unknown-workflow (outcome #(list-for "bob" "nope"))
               :unknown-team (outcome #(f/query ctx "mallory" {:query-id :list-open-pull-requests
                                                               :team-id "nope" :workflow-id "implement"}))
               :stranger (outcome #(list-for "mallory" "implement"))})))))))

(deftest schedules-are-listed-for-tenant-admins-only
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx]}
            (f/env)]
        (is
         (= {"carol" 403 "root" :ok}
            (into {} (for [user ["carol" "root"]]
                       [user (outcome #(f/query ctx user {:query-id :list-schedules}))]))))))))

(deftest cancelling-is-for-the-creator-and-admins
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx]}
            (f/env)

            cancel
            (fn [user]
              (outcome #(f/command! ctx user {:cmd-id :cancel-run :id run-id})))]
        (start! ctx "alice")
        (is
         (= 403
            (cancel "bob")))
        (testing "a team admin may"
          (is
           (= :ok
              (cancel "carol")))))))
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx]}
            (f/env)]
        (start! ctx "alice")
        (testing "and so may an admin of the tenant"
          (is
           (= :ok
              (outcome #(f/command! ctx "root" {:cmd-id :cancel-run :id run-id})))))))))

(deftest another-tenant-never-sees-or-touches-the-runs
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)]
        (start! ctx "alice")
        (testing "gina is a member of a team alpha too, but of tenant globex"
          (is
           (= {:get-run 404 :cancel-run 404 :retry-run 404 :list-runs []}
              {:get-run (outcome #(f/query ctx "gina" {:query-id :get-run :run-id run-id}))
               :cancel-run (outcome #(f/command! ctx "gina" {:cmd-id :cancel-run :id run-id}))
               :retry-run (outcome #(f/command! ctx "gina" {:cmd-id :retry-run :id (random-uuid)
                                                            :attrs {:source-id run-id}}))
               :list-runs (f/query ctx "gina" {:query-id :list-runs :team-id "alpha"})})))
        (testing "starting a workflow runs in her own tenant, which has none of acme's"
          (is
           (= 422
              (outcome #(f/command! ctx "gina" {:cmd-id :start-run
                                                :id (random-uuid)
                                                :attrs {:team-id "alpha" :workflow-id "implement"
                                                        :inputs {:task "Add a health endpoint"}}})))))
        (testing "nor does she see acme's schedules or pull requests"
          (triggers/run-due! system #{"schedule"})
          (is
           (= 403
              (outcome #(f/query ctx "gina" {:query-id :list-schedules}))))
          (is
           (= 404
              (outcome #(f/query ctx "gina" {:query-id :list-open-pull-requests
                                             :team-id "alpha" :workflow-id "implement"})))))))))

(deftest a-globex-admin-lists-only-globex-schedules
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env {:runs/scm (fn [_ _] {:open-pull-requests (fn [_] [])})})]
        (triggers/run-due! system #{"schedule" "pull_request"})
        (is
         (seq (f/query ctx "root" {:query-id :list-schedules})))
        (is
         (= []
            (mock/query (manager-svc.test-support/as-user ctx "gwen" {:tenant "globex" :tenant-admin true})
                        {:query-id :list-schedules})))))))

(def team-session-id #uuid "66666666-6666-6666-6666-666666666666")

(defn- team-session!
  [ctx user attrs]
  (f/command! ctx user {:cmd-id :start-team-session
                        :id team-session-id
                        :attrs (merge {:team-id "alpha" :workflow-id "workspace"} attrs)}))

(deftest team-admins-open-team-scoped-sessions
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)]
        (is
         (= {"bob" 403 "mallory" 403 "gina" 403}
            (into {} (for [user ["bob" "mallory" "gina"]]
                       [user (outcome #(team-session! ctx user {}))]))))
        (testing "only interactive workflows open a team session"
          (is
           (= 422
              (outcome #(team-session! ctx "carol" {:workflow-id "analyse"})))))
        (team-session! ctx "carol" {})
        (let [run
              (:run ((get-in system [:test/edd :query]) {:query-id :get-run-source :run-id team-session-id}))]
          (is
           (= ["acme" "alpha" true "platform" :team {:type "team_session" :by "carol"}]
              ((juxt :tenant :team-id :platform-started :created-by :session-visibility :trigger) run))))
        (testing "the run carries the project of its workflow"
          (is
           (= "app"
              (:project (:run ((get-in system [:test/edd :query]) {:query-id :get-run-source :run-id team-session-id})))))
          (is
           (= [team-session-id]
              (mapv :id (f/query ctx "bob" {:query-id :list-runs :team-id "alpha" :project-id "app"})))))
        (testing "team members see the run, as for any team run"
          (is
           (= team-session-id
              (:id (f/query ctx "bob" {:query-id :get-run :run-id team-session-id})))))))))

(deftest a-team-session-may-be-shared-with-the-tenant
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)]
        (team-session! ctx "root" {:visibility :tenant})
        (is
         (= :tenant
            (:session-visibility (:run ((get-in system [:test/edd :query])
                                        {:query-id :get-run-source :run-id team-session-id})))))))))
