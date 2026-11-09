(ns manager-svc.runs.run-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.directory :as directory]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.api :as api]
            [manager-svc.runs.fixtures :as f]
            [manager-svc.runs.model :as model]
            [manager-svc.runs.run :as run]))

(def run-id #uuid "11111111-1111-1111-1111-111111111111")

(defn- start!
  [ctx user workflow-id inputs]
  (f/command! ctx user {:cmd-id :start-run
                        :id run-id
                        :attrs {:team-id "alpha" :workflow-id workflow-id :inputs inputs}}))

(defn- platform!
  [system cmd-id attrs]
  ((get-in system [:test/edd :command]) (cond-> {:cmd-id cmd-id :id run-id} attrs (assoc :attrs attrs))))

(defn- state
  [system]
  (:run ((get-in system [:test/edd :query]) {:query-id :get-run-source :run-id run-id})))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch Exception e
      (or (get-in (ex-data e) [:error]) (ex-message e)))))

(defn- refusal
  "{:status :message} a call fails with, or nil."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (select-keys (ex-data e) [:status :message]))))

(defn- run-of
  [system id]
  (:run ((get-in system [:test/edd :query]) {:query-id :get-run-source :run-id id})))

(deftest members-start-runs-with-validated-inputs
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)]
        (start! ctx "alice" "implement" {:task "Add a health endpoint" :pull_request 7})
        (let [run (state system)]
          (is
           (= :queued
              (:status run)))
          (is
           (= {:task "Add a health endpoint" :pull-request 7}
              (:inputs run)))
          (is
           (= {:type "manual" :by "alice"}
              (:trigger run)))
          (is
           (= "alice"
              (:created-by run))))))))

(deftest manual-pull-request-workflows-record-the-scm
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)]
        (start! ctx "alice" "review-prs" {:pull-request 4})
        (is
         (= {:provider "github" :repository "acme/app" :credentials "gh-token"}
            (get-in (state system) [:trigger :scm])))))))

(deftest start-run-is-refused
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx]}
            (f/env)]
        (testing "for people of the tenant outside the team, with the reason"
          (is
           (= {:status 403
               :message "Only members of alpha can work in it. Ask an admin of alpha or your tenant admin to add you."}
              (refusal #(start! ctx "mallory" "implement" {:task "Add a health endpoint"})))))
        (testing "for a team the tenant doesn't have"
          (is
           (= {:status 404 :message "Team not found"}
              (refusal #(f/command! ctx "mallory" {:cmd-id :start-run
                                                   :id run-id
                                                   :attrs {:team-id "nope" :workflow-id "implement"
                                                           :inputs {:task "Add a health endpoint"}}})))))
        (testing "for inputs the activity rejects"
          (is
           (= "task: should be at least 10 characters"
              (failure #(start! ctx "alice" "implement" {:task "short"}))))
          (is
           (= "extra: disallowed key"
              (failure #(start! ctx "alice" "implement" {:task "Add a health endpoint" :extra 1})))))
        (testing "for unknown workflows"
          (is
           (= "Workflow alpha/nope does not exist or is invalid"
              (failure #(start! ctx "alice" "nope" {})))))))))

(deftest lifecycle-moves-through-every-status
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)

            session-id
            #uuid "22222222-2222-2222-2222-222222222222"

            status-after
            (fn [cmd-id attrs]
              (platform! system cmd-id attrs)
              (:status (state system)))]
        (start! ctx "alice" "implement" {:task "Add a health endpoint"})
        (is
         (= :preparing
            (status-after :mark-run-preparing nil)))
        (is
         (= :running
            (status-after :record-run-session {:session-id session-id :interactive false :models ["model-a"]})))
        (is
         (= :awaiting-result
            (status-after :record-run-conversation {:conversation "c1" :model "model-a"})))
        (is
         (= :awaiting-result
            (status-after :submit-run-result {:tool "submit_implementation" :payload {:summary "Done"}})))
        (is
         (= :finalizing
            (status-after :finalize-run {:result {:pull-request {:id 9 :url "u"}}})))
        (is
         (= :following-ci
            (status-after :follow-run-ci {:ci {:job "app/PR-9" :state "waiting" :build nil}})))
        (is
         (= :completed
            (status-after :complete-run nil)))
        (let [run (state system)]
          (is
           (= session-id
              (:session-id run)))
          (is
           (= {:pull-request {:id 9 :url "u"}
               :ci {:job "app/PR-9" :state "waiting" :build nil
                    :started-at (get-in run [:result :ci :started-at])
                    :checked-at (get-in run [:result :ci :checked-at])}}
              (:result run))))))))

(deftest model-fallback-and-failure
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)]
        (start! ctx "alice" "analyse" {:brief "Look at the architecture"})
        (platform! system :record-run-session {:session-id (random-uuid) :interactive false :models ["a" "b"]})
        (platform! system :record-run-conversation {:conversation "c1" :model "a"})
        (platform! system :record-model-unavailable {:model "a" :message "quota"})
        (let [run (state system)]
          (is
           (= [:running 1 ["a: quota"]]
              [(:status run) (:model-index run) (:unavailable run)])))
        (platform! system :fail-run {:message "No result: a: quota"})
        (is
         (= [:failed "No result: a: quota"]
            ((juxt :status :error) (state system))))))))

(deftest cancel
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)

            cancel!
            #(f/command! ctx % {:cmd-id :cancel-run :id run-id})]
        (start! ctx "alice" "implement" {:task "Add a health endpoint"})
        (testing "only by the creator or a team admin"
          (is
           (= "Only the run's creator or a team admin can do that"
              (failure #(cancel! "bob")))))
        (cancel! "carol")
        (let [run (state system)]
          (is
           (= [:cancelled "Cancelled by carol" "carol"]
              ((juxt :status :error :cancelled-by) run))))
        (testing "people read the error as :error-message, since edd answers a result holding :error as an error"
          (let [detail
                (f/query ctx "alice" {:query-id :get-run :run-id run-id})]
            (is
             (= "Cancelled by carol"
                (:error-message detail)))
            (is
             (not (contains? detail :error)))))
        (testing "a finished run can't be cancelled again"
          (is
           (= "Run is already cancelled"
              (failure #(cancel! "alice")))))
        (testing "lifecycle steps racing the cancel change nothing"
          (platform! system :record-run-session {:session-id (random-uuid) :interactive false :models ["a"]})
          (platform! system :complete-run nil)
          (is
           (= :cancelled
              (:status (state system)))))
        (testing "a result can't be submitted"
          (is
           (some?
            (failure #(platform! system :submit-run-result {:tool "submit_implementation" :payload {}})))))))))

(deftest retry-starts-a-new-run-with-the-same-inputs
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)

            retry-id
            #uuid "33333333-3333-3333-3333-333333333333"]
        (start! ctx "alice" "implement" {:task "Add a health endpoint"})
        (platform! system :fail-run {:message "boom"})
        (f/command! ctx "bob" {:cmd-id :retry-run :id retry-id :attrs {:source-id run-id}})
        (let [retried
              (:run ((get-in system [:test/edd :query]) {:query-id :get-run-source :run-id retry-id}))]
          (is
           (= [:queued "bob" {:task "Add a health endpoint"}]
              ((juxt :status :created-by :inputs) retried)))
          (is
           (= {:type "manual" :by "bob" :retry-of run-id}
              (:trigger retried))))))))

(deftest only-finished-runs-are-run-again
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)

            retry-id
            #uuid "33333333-3333-3333-3333-333333333333"

            retry
            (fn []
              (refusal #(f/command! ctx "bob" {:cmd-id :retry-run :id retry-id :attrs {:source-id run-id}})))]
        (start! ctx "alice" "implement" {:task "Add a health endpoint"})
        (is
         (= {:status 409 :message "Run is still active; cancel it before running it again"}
            (retry)))
        (is
         (nil? (run-of system retry-id)))
        (f/command! ctx "alice" {:cmd-id :cancel-run :id run-id})
        (is
         (nil? (retry)))
        (is
         (= :queued
            (:status (run-of system retry-id))))))))

(deftest a-retry-waits-for-a-free-session-like-a-start
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [{:keys [ctx system]}
            (f/env)

            team
            (assoc f/team :budget {:max-running-sessions 1})

            running
            #uuid "22222222-2222-2222-2222-222222222222"

            retry-id
            #uuid "33333333-3333-3333-3333-333333333333"

            retry
            (fn []
              (refusal #(f/command! ctx "bob" {:cmd-id :retry-run :id retry-id :attrs {:source-id run-id}})))]
        (start! ctx "alice" "analyse" {:brief "Look at the architecture"})
        (platform! system :fail-run {:message "boom"})
        (with-redefs [platform/team (fn [_ _ _] team)]
          (swap! store assoc running {:tenant "acme" :team-id "alpha"})
          (is
           (= {:status 422 :message "Team alpha already has 1 running sessions (limit 1). Stop one and retry."}
              (retry)))
          (swap! store assoc-in [running :stopped] true)
          (is
           (nil? (retry)))
          (is
           (= :queued
              (:status (run-of system retry-id)))))))))

(deftest the-platforms-own-starts-read-no-session-count
  (is
   (nil? (run/running-sessions-dep {:meta {:user {:id "platform" :role :non-interactive}}}
                                   {:attrs {:tenant "acme" :team-id "alpha"}})))
  (is
   (= {:query-id :count-running-sessions :tenant "acme" :team-id "alpha"}
      (run/running-sessions-dep {:meta {:user {:id "alice" :tenant "acme"}}}
                                {:attrs {:team-id "alpha"}}))))

(deftest list-and-get-runs
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [{:keys [ctx system]}
            (f/env)

            session-id
            #uuid "22222222-2222-2222-2222-222222222222"]
        (swap! store assoc session-id {:id session-id :sub "alice" :visibility :team :secret-key "pw"})
        (start! ctx "alice" "review-prs" {:pull-request 4})
        (platform! system :record-run-session {:session-id session-id :interactive false :models ["a"]})
        (platform! system :finalize-run {:result {:review {:verdict "APPROVE"}
                                                  :pull-request {:id 4 :url "u" :branch "b"}}})
        (let [[listed]
              (f/query ctx "bob" {:query-id :list-runs :team-id "alpha"})]
          (is
           (= {:id run-id
               :status :finalizing
               :workflow-name "Review PRs"
               :has-result true
               :outcome {:pull-request {:id 4 :url "u"} :verdict "APPROVE"}
               :scm-provider :github}
              (select-keys listed [:id :status :workflow-name :has-result :outcome :scm-provider])))
          (is
           (not (contains? listed :result)))
          (is
           (= "app"
              (:project listed))))
        (testing "a project's runs: those of the workflows naming it"
          (is
           (= [run-id]
              (mapv :id (f/query ctx "bob" {:query-id :list-runs :team-id "alpha" :project-id "app"}))))
          (is
           (= []
              (f/query ctx "bob" {:query-id :list-runs :team-id "alpha" :project-id "other"})))
          (is
           (= []
              (f/query ctx "gina" {:query-id :list-runs :team-id "alpha" :project-id "app"}))))
        (testing "a project id alone names no project: ids are only unique within a team"
          (is
           (= [run-id]
              (mapv :id (f/query ctx "bob" {:query-id :list-runs :project-id "other"})))))
        (testing "an id that is no UUID is refused"
          (is
           (= {:run-id ["should be a uuid"]}
              (failure #(f/query ctx "bob" {:query-id :get-run :run-id "nope"})))))
        (is
         (= []
            (f/query ctx "mallory" {:query-id :list-runs})))
        (let [detail
              (f/query ctx "bob" {:query-id :get-run :run-id run-id})]
          (is
           (= {:id session-id :sub "alice" :visibility :team :is-owner false}
              (:session detail)))
          (is
           (= 3
              (get-in detail [:usage :calls])))
          (is
           (= "APPROVE"
              (get-in detail [:result :review :verdict])))
          (is
           (= "app"
              (:project detail))))
        (is
         (re-find #"Only members of"
                  (failure #(f/query ctx "mallory" {:query-id :get-run :run-id run-id}))))))))

(deftest run-counts-for-the-admin-console
  (mock/with-mock-dal
    (f/with-fakes [_store]
      (let [{:keys [ctx system]}
            (f/env)]
        (start! ctx "alice" "review-prs" {:pull-request 4})
        (f/command! ctx "alice" {:cmd-id :cancel-run :id run-id})
        (is
         (= {:cancelled 1}
            (api/run-counts-by-status system "acme" "2000-01-01T00:00:00Z")))
        (is
         (= {"alice" 1}
            (api/run-counts-by-user system "acme" "2000-01-01T00:00:00Z")))
        (is
         (= {}
            (api/run-counts-by-status system "acme" "2999-01-01T00:00:00Z")))
        (testing "another tenant counts none of them"
          (is
           (= [{} {}]
              [(api/run-counts-by-status system "globex" "2000-01-01T00:00:00Z")
               (api/run-counts-by-user system "globex" "2000-01-01T00:00:00Z")])))))))

(deftest runs-are-listed-from-team-and-person-directories
  (mock/with-mock-dal
    (f/with-fakes [_store]
      (let [{:keys [ctx system]}
            (f/env)

            beta-run
            #uuid "33333333-3333-3333-3333-333333333333"

            listed
            (fn [user]
              (set (map (juxt :id :status) (f/query ctx user {:query-id :list-runs}))))]
        (start! ctx "alice" "review-prs" {:pull-request 4})
        ((get-in system [:test/edd :command]) {:cmd-id :start-run
                                               :id beta-run
                                               :attrs {:tenant "acme" :team-id "beta" :workflow-id "analyse"
                                                       :inputs {:brief "Weekly architecture review"}}})
        (is
         (= #{[run-id :queued]}
            (listed "bob")))
        (with-redefs [platform/teams-of (fn [_ _] #{"alpha" "beta"})]
          (is
           (= #{[run-id :queued] [beta-run :queued]}
              (listed "root"))))
        (testing "a status change shows in the list"
          (f/command! ctx "alice" {:cmd-id :cancel-run :id run-id})
          (is
           (= #{[run-id :cancelled]}
              (listed "bob"))))
        (testing "pending runs are the ones still needing work"
          (is
           (= [beta-run]
              (map :id ((get-in system [:test/edd :query]) {:query-id :list-pending-runs})))))
        (testing "the person's directory holds the runs they started"
          (is
           (= [run-id]
              (map :id (directory/rows ctx (directory/user "alice") :runs))))
          (is
           (= []
              (directory/rows ctx (directory/user "platform") :runs))))))))

(deftest the-platform-marks-runs-it-starts-on-its-own
  (testing "a run of its own works with team credentials"
    (is
     (= {:trigger {:type "schedule"} :created-by "platform:schedule" :platform-started true}
        (run/platform-start {:trigger {:type "schedule"} :created-by "platform:schedule"}))))
  (testing "a run continuing a person's run acts for that person"
    (is
     (= {:trigger {:type "manual"} :created-by "alice" :platform-started false}
        (run/platform-start {:person "alice" :created-by "alice"}))))
  (testing "the platform's own name is never a person"
    (is
     (true? (:platform-started (run/platform-start {:person "platform"})))))
  (testing "only the marker makes a run the platform's, not its creator's name"
    (is
     (false? (model/platform-created? {:created-by "platformer"})))
    (is
     (= "platformer"
        (model/person {:created-by "platformer" :platform-started false})))
    (is
     (nil? (model/person {:created-by "platform:schedule" :platform-started true})))))

(deftest platform-started-runs-carry-the-marker
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [system]}
            (f/env)]
        (platform! system :start-run {:tenant "acme" :team-id "alpha" :workflow-id "analyse" :inputs {:brief "Look at the architecture"}
                                      :created-by "platform:schedule"})
        (is
         (= [true "platform:schedule"]
            ((juxt :platform-started :created-by) (state system))))))))

(deftest run-rows-change-only-with-what-a-list-shows
  (is
   (nil? (:updated-at (model/row {:id run-id :status :running :updated-at "2026-01-01T00:00:00Z"}))))
  (is
   (empty? (filter run/event-ids [:run-nudged :run-step-failed :run-step-recovered
                                  :run-review-comment-posted :run-ci-checked])))
  (is
   (every? run/event-ids [:run-created :run-ci-updated :run-completed :run-session-released])))

(deftest the-session-limit-is-read-before-the-command
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [{:keys [ctx system]}
            (f/env)

            team
            (assoc f/team :budget {:max-running-sessions 1})]
        (with-redefs [platform/team (fn [_ _ _] team)]
          (swap! store assoc #uuid "22222222-2222-2222-2222-222222222222" {:tenant "acme" :team-id "alpha"})
          (is
           (= "Team alpha already has 1 running sessions (limit 1). Stop one and retry."
              (failure #(start! ctx "alice" "analyse" {:brief "Look at the architecture"}))))
          (swap! store assoc-in [#uuid "22222222-2222-2222-2222-222222222222" :stopped] true)
          (start! ctx "alice" "analyse" {:brief "Look at the architecture"})
          (is
           (= :queued
              (:status (state system)))))))))

(deftest a-person-chooses-who-sees-the-session-of-their-run
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)]
        (f/command! ctx "alice" {:cmd-id :start-run
                                 :id run-id
                                 :attrs {:team-id "alpha" :workflow-id "implement" :inputs {:task "Add a health endpoint"}
                                         :visibility "private"}})
        (is
         (= :private
            (:session-visibility (state system))))
        (testing "a retry keeps the choice"
          (platform! system :fail-run {:message "boom"})
          (let [retry-id (random-uuid)]
            (f/command! ctx "bob" {:cmd-id :retry-run :id retry-id :attrs {:source-id run-id}})
            (is
             (= :private
                (:session-visibility (:run ((get-in system [:test/edd :query])
                                            {:query-id :get-run-source :run-id retry-id})))))))
        (testing "without a choice the workflow decides"
          (let [other-id (random-uuid)]
            (f/command! ctx "alice" {:cmd-id :start-run
                                     :id other-id
                                     :attrs {:team-id "alpha" :workflow-id "implement" :inputs {:task "Add a health endpoint"}}})
            (is
             (nil? (:session-visibility (:run ((get-in system [:test/edd :query])
                                               {:query-id :get-run-source :run-id other-id})))))))))))

(deftest work-in-a-team-needs-the-credentials-it-requires-of-the-person
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)

            refused
            "Team Alpha requires your own Jira and PagerDuty credentials. Add them under Accounts, then start again; team and tenant values don't count."

            missing
            [{:id "JIRA_TOKEN" :name "Jira"} {:id "PAGERDUTY_TOKEN" :name "PagerDuty"}]]
        (binding [f/*missing-credentials* {["alice" "alpha"] missing
                                           ["carol" "alpha"] missing}]
          (testing "a person starting a run"
            (is
             (= refused
                (failure #(start! ctx "alice" "implement" {:task "Add a health endpoint"}))))
            (is
             (nil? (state system))))
          (testing "a team admin opening a team session"
            (is
             (= refused
                (failure #(f/command! ctx "carol" {:cmd-id :start-team-session
                                                   :id run-id
                                                   :attrs {:team-id "alpha" :workflow-id "workspace" :inputs {}}})))))
          (testing "someone who has added them starts as before"
            (start! ctx "bob" "implement" {:task "Add a health endpoint"})
            (is
             (= :queued
                (:status (state system)))))
          (testing "a person retrying a run"
            (platform! system :fail-run {:message "boom"})
            (is
             (= refused
                (failure #(f/command! ctx "alice" {:cmd-id :retry-run
                                                   :id (random-uuid)
                                                   :attrs {:source-id run-id}}))))))
        (testing "the platform's own runs need no person's credentials"
          (binding [f/*missing-credentials* {["platform" "alpha"] missing}]
            (let [platform-run
                  (random-uuid)]
              ((get-in system [:test/edd :command]) {:cmd-id :start-run
                                                     :id platform-run
                                                     :attrs {:tenant "acme" :team-id "alpha" :workflow-id "analyse"
                                                             :inputs {:brief "Weekly architecture review"}}})
              (is
               (= :queued
                  (:status (:run ((get-in system [:test/edd :query]) {:query-id :get-run-source :run-id platform-run}))))))))))))
