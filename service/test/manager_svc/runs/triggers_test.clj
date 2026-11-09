(ns manager-svc.runs.triggers-test
  (:refer-clojure :exclude [pr])
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.client :as client]
            [manager-svc.directory :as directory]
            [manager-svc.runs.fixtures :as f]
            [manager-svc.runs.triggers :as triggers]
            [manager-svc.time :as time]))

(deftest desired-schedules-come-from-triggered-workflows
  (is
   (= {"trigger-alpha-review-prs" {:kind "pull_request" :team "alpha" :workflow "review-prs" :minutes 15}
       "trigger-alpha-nightly" {:kind "schedule" :team "alpha" :workflow "nightly" :minutes 60}}
      (triggers/desired-schedules
       {"alpha" [{:id "review-prs" :trigger {:type "pull_request" :poll-minutes 15}}
                 {:id "nightly" :trigger {:type "schedule" :every-minutes 60}}
                 {:id "manual" :trigger {:type "manual"}}]}))))

(deftest schedules-are-due-one-interval-after-the-last-run
  (let [poll
        {:kind "pull_request" :minutes 15}

        hourly
        {:kind "schedule" :minutes 60}

        registered
        {:registered-at "2026-01-01T10:00:00Z"}]
    (testing "a pull request poll runs as soon as it is registered"
      (is
       (= "2026-01-01T10:00:00Z"
          (triggers/next-run registered poll)))
      (is
       (true? (triggers/due? registered poll "2026-01-01T10:00:00Z"))))
    (testing "a scheduled run waits one interval"
      (is
       (= "2026-01-01T11:00:00Z"
          (triggers/next-run registered hourly)))
      (is
       (false? (triggers/due? registered hourly "2026-01-01T10:59:59Z")))
      (is
       (true? (triggers/due? registered hourly "2026-01-01T11:00:00Z"))))
    (testing "then one interval after the last run"
      (is
       (= "2026-01-01T12:15:00Z"
          (triggers/next-run (assoc registered :last-run "2026-01-01T12:00:00Z") poll)))
      (is
       (false? (triggers/due? (assoc registered :last-run "2026-01-01T12:00:00Z") poll "2026-01-01T12:14:00Z"))))
    (testing "unregistered schedules are not due"
      (is
       (false? (triggers/due? {} poll "2026-01-01T12:14:00Z"))))))

(defn- pr
  [id destination]
  {:id id :title (str "PR " id) :author "dave" :source-branch (str "f" id) :source-commit "abc"
   :destination-branch destination :url (str "https://github.com/acme/app/pull/" id)
   :clone-url "https://github.com/acme/app.git"})

(defn- runs-of
  [system]
  (->> ((get-in system [:test/edd :query]) {:query-id :list-pending-runs})
       (sort-by #(get-in % [:inputs :pull-request]))))

(deftest pull-requests-start-one-run-each-after-the-baseline
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [open
            (atom [(pr 1 "main")])

            tokens
            (atom [])

            {:keys [system]}
            (f/env {:runs/scm (fn [_ token]
                                (swap! tokens conj token)
                                {:open-pull-requests (fn [_] @open)})})

            poll!
            #(triggers/new-pull-requests system "alpha" "review-prs")]
        (testing "the pull requests open at first sight are the baseline"
          (is
           (= []
              (poll!)))
          (is
           (= ["1" "__baseline__"]
              ((get-in system [:test/edd :query]) {:query-id :get-trigger-items :team-id "alpha" :workflow-id "review-prs"}))))
        (testing "new pull requests start a run once"
          (reset! open [(pr 1 "main") (pr 2 "main")])
          (let [[started] (poll!)]
            (is
             (uuid? started))
            (is
             (= []
                (poll!)))
            (let [[run] (runs-of system)]
              (is
               (= [started "review" {:pull-request 2} "platform:pull_request"]
                  ((juxt :id :activity :inputs :created-by) run)))
              (is
               (= {:provider "github" :repository "acme/app" :credentials "gh-token"}
                  (get-in run [:trigger :scm])))
              (is
               (= 2
                  (get-in run [:trigger :pull-request :id]))))))
        (is
         (= "secret-gh-token"
            (first @tokens)))))))

(deftest backfill-runs-the-pull-requests-already-open
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [system]}
            (f/env {:runs/scm (fn [_ _] {:open-pull-requests (fn [_] [(pr 1 "main") (pr 2 "release")])})})]
        (with-redefs [manager-svc.runs.fixtures/workflows
                      (assoc-in f/workflows ["review-prs" :trigger]
                                {:type "pull_request" :provider "github" :repository "acme/app"
                                 :credentials "gh-token" :backfill true :target-branches ["main"]})]
          (is
           (= 1
              (count (triggers/new-pull-requests system "alpha" "review-prs"))))
          (is
           (= [{:pull-request 1}]
              (map :inputs (runs-of system)))))))))

(deftest due-schedules-fire-and-are-recorded
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env {:runs/scm (fn [_ _] {:open-pull-requests (fn [_] [(pr 1 "main")])})})]
        (testing "first sight registers both schedules and polls pull requests"
          (is
           (= {"trigger-alpha-review-prs" [] "trigger-alpha-analyse" []}
              (triggers/run-due! system #{"pull_request" "schedule"}))))
        (testing "the schedules are listed in their team's directory"
          (is
           (= #{"trigger-alpha-review-prs" "trigger-alpha-analyse"}
              (set (map :schedule-key (directory/rows ctx "team:alpha" :schedules))))))
        (testing "nothing is due right after"
          (is
           (= {"trigger-alpha-review-prs" [] "trigger-alpha-analyse" []}
              (triggers/run-due! system #{"pull_request" "schedule"}))))
        (testing "admins see the schedules"
          (let [schedules
                (f/query ctx "root" {:query-id :list-schedules})]
            (is
             (= ["trigger-alpha-analyse" "trigger-alpha-review-prs"]
                (map :id schedules)))
            (is
             (every? :next-run schedules))
            (is
             (= [false true]
                (map (comp some? :last-run) schedules)))))
        (testing "a due scheduled run starts the workflow with the trigger's inputs"
          (with-redefs [triggers/due? (fn [_ schedule _] (= "schedule" (:kind schedule)))]
            (let [fired (triggers/run-due! system #{"schedule"})
                  [run-id] (get fired "trigger-alpha-analyse")
                  run (:run ((get-in system [:test/edd :query]) {:query-id :get-run-source :run-id run-id}))]
              (is
               (= ["analysis" {:brief "Weekly architecture review"} {:type "schedule"} "platform:schedule"]
                  ((juxt :activity :inputs :trigger :created-by) run))))))))))

(deftest schedules-registered-without-a-team-get-listed
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env {:runs/scm (fn [_ _] {:open-pull-requests (fn [_] [])})})

            platform!
            (get-in system [:test/edd :command])]
        (platform! {:cmd-id :register-schedule :attrs {:schedule-key "trigger-alpha-analyse"}})
        (let [registered-at
              (:registered-at (mock/get-by-id ctx {:id (triggers/schedule-id "trigger-alpha-analyse")}))]
          (is
           (= []
              (directory/rows ctx "team:alpha" :schedules)))
          (triggers/run-due! system #{"schedule"})
          (is
           (= [{:schedule-key "trigger-alpha-analyse" :team-id "alpha" :registered-at registered-at}]
              (map #(select-keys % [:schedule-key :team-id :registered-at])
                   (directory/rows ctx "team:alpha" :schedules)))))))))

(deftest new-pull-requests-are-marked-seen-in-one-command
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [marks
            (atom 0)

            {:keys [system]}
            (f/env {:runs/scm (fn [_ _] {:open-pull-requests (fn [_] [(pr 1 "main") (pr 2 "main")])})})

            command
            client/command]
        (with-redefs [client/command (fn [settings & commands]
                                       (when (some #(= :mark-trigger-items-seen (:cmd-id %)) commands)
                                         (swap! marks inc))
                                       (apply command settings commands))
                      manager-svc.runs.fixtures/workflows (assoc-in f/workflows ["review-prs" :trigger :backfill] true)]
          (is
           (= 2
              (count (triggers/new-pull-requests system "alpha" "review-prs"))))
          (is
           (= 1
              @marks))
          (is
           (= ["1" "2" "__baseline__"]
              ((get-in system [:test/edd :query]) {:query-id :get-trigger-items :team-id "alpha" :workflow-id "review-prs"}))))))))

(deftest seen-pull-requests-are-forgotten-after-ninety-days
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [system]}
            (f/env)

            mark!
            (fn [items]
              ((get-in system [:test/edd :command]) {:cmd-id :mark-trigger-items-seen
                                                     :attrs {:team-id "alpha" :workflow-id "review-prs" :items items}}))]
        (with-redefs [time/now (constantly "2026-01-01T00:00:00Z")]
          (mark! [{:item "__baseline__"} {:item "1"}]))
        (with-redefs [time/now (constantly "2026-04-15T00:00:00Z")]
          (mark! [{:item "2"}]))
        (is
         (= ["2" "__baseline__"]
            ((get-in system [:test/edd :query]) {:query-id :get-trigger-items :team-id "alpha" :workflow-id "review-prs"})))))))
