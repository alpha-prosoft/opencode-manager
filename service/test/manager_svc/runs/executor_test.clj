(ns manager-svc.runs.executor-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.auth :as auth]
            [manager-svc.runs.ci :as ci]
            [manager-svc.runs.executor :as executor]
            [manager-svc.runs.fixtures :as f]
            [manager-svc.runs.git :as git]
            [manager-svc.runs.handlers :as handlers]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.http :as http]
            [manager-svc.opencode :as opencode])
  (:import [java.time Instant]))

(def run-id #uuid "11111111-1111-1111-1111-111111111111")

(def session-id (executor/session-id-for run-id))

(def diff
  "diff --git a/src/app.py b/src/app.py
--- a/src/app.py
+++ b/src/app.py
@@ -1,2 +1,3 @@
 import os
+import sys
 print(os.name)
")

(defn- fake-scm
  [calls]
  (fn [{:keys [provider]} {:keys [secret]}]
    {:git-username "x-access-token"
     :git-host "github.com"
     :token secret
     :open-pull-requests (fn [_] [])
     :pull-request (fn [repository number]
                     {:id number :title "Add sys" :author "dave" :source-branch "feature"
                      :source-commit "abc123" :destination-branch "main"
                      :url (str "https://github.com/" repository "/pull/" number)
                      :clone-url (str "https://github.com/" repository ".git")})
     :diff (fn [_ _] diff)
     :comment (fn [repository number content path line]
                (swap! calls conj [:comment (name provider) repository number path line (first (string/split-lines content))])
                (count @calls))
     :create-pull-request (fn [repository source destination title _description draft]
                            (swap! calls conj [:create-pr repository source destination title draft])
                            {:id 12 :url (str "https://github.com/" repository "/pull/12")
                             :source-branch source :destination-branch destination})}))

(defn- fake-opencode
  "Redefinitions of the OpenCode client; statuses is an atom of {conversation status}."
  [calls statuses]
  {#'opencode/create-session! (fn [_ password _title]
                                (let [conversation (str "conv-" (inc (count (filter #(= :create (first %)) @calls))))]
                                  (swap! calls conj [:create password conversation])
                                  conversation))
   #'opencode/prompt-async! (fn [_ _ conversation model text]
                              (swap! calls conj [:prompt conversation model text]))
   #'opencode/messages (fn [_ _ conversation]
                         (if (some #(= [:prompt conversation] (take 2 %)) @calls)
                           [{:info {:role "user"}}]
                           []))
   #'opencode/session-statuses (fn [_ _] @statuses)
   #'opencode/last-reply (fn [_ _ _] "done")
   #'opencode/abort! (fn [_ _ conversation] (swap! calls conj [:abort conversation]))})

(defn- with-fakes*
  [redefs f]
  (with-redefs-fn (merge {#'git/checkout! (fn [_] "abc123")
                          #'executor/idle-grace-seconds -1}
                         redefs)
    f))

(defn- run-state
  [system id]
  (:run ((get-in system [:test/edd :query]) {:query-id :get-run-source :run-id id})))

(defn- platform!
  [system cmd-id attrs]
  ((get-in system [:test/edd :command]) {:cmd-id cmd-id :id run-id :attrs attrs}))

(defn- start!
  [ctx workflow-id inputs]
  (f/command! ctx "alice" {:cmd-id :start-run
                           :id run-id
                           :attrs {:team-id "alpha" :workflow-id workflow-id :inputs inputs}}))

(defn- prompts
  [calls]
  (filter #(= :prompt (first %)) @calls))

(deftest a-review-run-from-start-to-posted-comments
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [calls
            (atom [])

            statuses
            (atom {"conv-1" {:type "busy"}})

            {:keys [ctx system]}
            (f/env {:runs/scm (fake-scm calls)})]
        (with-fakes* (fake-opencode calls statuses)
          (fn []
            (start! ctx "review-prs" {:pull-request 4})
            (executor/advance! system run-id)
            (testing "the run prepares the session and prompts the agent"
              (let [run (run-state system run-id)
                    session (get @store session-id)
                    config (http/read-json (get-in session [:sandbox :files "opencode/opencode.json"]))]
                (is
                 (= [:awaiting-result session-id "conv-1" "model-a"]
                    ((juxt :status :session-id :conversation :model) run)))
                (is
                 (= ["Review PRs · PR #4" :team "acme" "alpha" run-id "alice"]
                    ((juxt :name :visibility :tenant :team-id :run-id :sub) session)))
                (testing "the session carries the project of the run"
                  (is
                   (= ["app" "app"]
                      [(:project run) (:project session)])))
                (is
                 (= {:url "http://manager:8001/mcp/"
                     :headers {:X-Platform-Key (auth/derived-key (:settings system) "mcp" session-id)}}
                    (select-keys (get-in config [:mcp :platform]) [:url :headers])))
                (is
                 (= "http://manager:8001/llm/v1"
                    (get-in config [:provider :platform :options :baseURL])))
                (is
                 (= "conv-1"
                    (get-in session [:labels "conversation"])))
                (is
                 (= [true true "pw"]
                    [(:handed-over session) (:started session)
                     (get-in session [:sandbox :env "OPENCODE_SERVER_PASSWORD"])]))
                (is
                 (string/starts-with? (nth (first (prompts calls)) 3) "Review pull request #4 of acme/app."))
                (is
                 (= "platform/model-a"
                    (nth (first (prompts calls)) 2)))))
            (platform! system :submit-run-result
                       {:tool "submit_review"
                        :payload {:verdict "REQUEST_CHANGES"
                                  :summary "One problem"
                                  :findings [{:severity "major" :title "Unused import" :body "Remove it"
                                              :path "src/app.py" :line 2}
                                             {:severity "nit" :title "Elsewhere" :body "Off diff"
                                              :path "src/other.py" :line 40}]}})
            (reset! statuses {"conv-1" {:type "idle"}})
            (executor/advance! system run-id)
            (testing "the result is finalized, posted and the run completes"
              (let [run (run-state system run-id)]
                (is
                 (= :completed
                    (:status run)))
                (is
                 (= {:mode "auto" :comments 2 :inline 1 :folded 1}
                    (get-in run [:result :plan])))
                (is
                 (= 2
                    (get-in run [:result :posted])))
                (is
                 (= [[:comment "github" "acme/app" 4 nil nil "## Automated review: REQUEST CHANGES"]
                     [:comment "github" "acme/app" 4 "src/app.py" 2 "**major**: Unused import"]]
                    (filter #(= :comment (first %)) @calls)))
                (is
                 (= ["summary" "finding-0"]
                    (map :key (:posted-comments run))))
                (is
                 (true? (get-in @store [session-id :stopped])))
                (is
                 (true? (:notified run)))))))))))

(deftest the-agent-is-nudged-then-the-run-fails-without-a-result
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [calls
            (atom [])

            {:keys [ctx system]}
            (f/env)]
        (with-fakes* (fake-opencode calls (atom {}))
          (fn []
            (start! ctx "analyse" {:brief "Look at the architecture"})
            (executor/advance! system run-id)
            (let [run (run-state system run-id)]
              (is
               (= [:failed "No result: the agent finished without submitting a result"]
                  ((juxt :status :error) run)))
              (is
               (= 2
                  (:nudges run))))
            (is
             (= 2
                (count (filter #(string/includes? (nth % 3) "Call the `submit_document` tool now") (prompts calls)))))
            (is
             (true? (get-in @store [session-id :stopped])))))))))

(deftest an-unavailable-model-falls-back-to-the-next
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [calls
            (atom [])

            statuses
            (atom {"conv-1" {:type "retry" :attempt 3 :message "rate limited"}
                   "conv-2" {:type "busy"}})

            {:keys [ctx system]}
            (f/env)]
        (with-fakes* (fake-opencode calls statuses)
          (fn []
            (start! ctx "analyse" {:brief "Look at the architecture"})
            (executor/advance! system run-id)
            (let [run (run-state system run-id)]
              (is
               (= [:awaiting-result "conv-2" "model-b" ["model-a: rate limited"]]
                  ((juxt :status :conversation :model :unavailable) run))))
            (is
             (some #{[:abort "conv-1"]} @calls))
            (swap! statuses assoc "conv-2" {:type "retry" :action {:reason "free_tier_limit"} :message "free tier"})
            (executor/advance! system run-id)
            (is
             (= [:failed "No result: model-a: rate limited; model-b: free tier"]
                ((juxt :status :error) (run-state system run-id))))))))))

(deftest an-implementation-opens-a-pull-request-and-follows-its-failed-build
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [calls
            (atom [])

            tool-calls
            (atom [])

            statuses
            (atom {"conv-1" {:type "busy"}})

            ci-tool
            (fn [_ _ _ tool arguments]
              (swap! tool-calls conj [tool arguments])
              (case tool
                "get_build" {:number 5 :result "FAILURE" :building false :url "http://jenkins/5"
                             :started (+ 5000 (.toEpochMilli (Instant/now)))
                             :stages [{:name "build" :status "SUCCESS"} {:name "test" :status "FAILED"}]}
                "get_build_log" "AssertionError: expected 1"))

            {:keys [ctx system]}
            (f/env {:runs/scm (fake-scm calls) :runs/ci ci-tool})]
        (with-fakes* (merge (fake-opencode calls statuses)
                            {#'git/diff (fn [_] diff)
                             #'git/commit-and-push! (fn [{:keys [ref message author]}]
                                                      (swap! calls conj [:push ref (first (string/split-lines message)) author])
                                                      "c0ffee1234567890")
                             #'ci/poll-seconds -1})
          (fn []
            (start! ctx "implement" {:task "Add a health endpoint"})
            (executor/advance! system run-id)
            (platform! system :submit-run-result {:tool "submit_implementation"
                                                  :payload {:title "Add health endpoint" :summary "Adds /health"
                                                            :verified true}})
            (reset! statuses {})
            (executor/advance! system run-id)
            (let [run (run-state system run-id)
                  follow-up-id (get-in run [:result :ci :follow-up-run])
                  follow-up (run-state system follow-up-id)]
              (is
               (= :completed
                  (:status run)))
              (is
               (= [[:push (str "refs/heads/agent/" run-id) "Add health endpoint"
                    ["alice (via Agent Platform)" "alice@users.local"]]]
                  (filter #(= :push (first %)) @calls)))
              (is
               (= [[:create-pr "acme/app" (str "agent/" run-id) "main" "Add health endpoint" false]]
                  (filter #(= :create-pr (first %)) @calls)))
              (is
               (= ["src/app.py"]
                  (get-in run [:result :files])))
              (is
               (= {:pull-request {:id 12 :url "https://github.com/acme/app/pull/12" :draft false}
                   :build {:state "failed" :number 5 :follow-up-run follow-up-id}}
                  (select-keys (manager-svc.runs.model/outcome (:result run)) [:pull-request :build])))
              (is
               (= :github
                  (manager-svc.runs.model/scm-provider run))
               "the run knows where it delivered its pull request")
              (is
               (= ["get_build" {:job "app/PR-12" :number "lastBuild"}]
                  (first @tool-calls)))
              (is
               (= ["analysis" "alice" {:type "ci_failure" :run run-id :build 5 :by "alice"}]
                  ((juxt :activity :created-by :trigger) follow-up)))
              (is
               (string/includes? (get-in follow-up [:inputs :brief]) "Failed stage(s): test."))
              (is
               (string/includes? (get-in follow-up [:inputs :brief]) "AssertionError: expected 1")))))))))

(deftest interactive-workspaces-complete-once-the-agent-is-prompted
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [calls
            (atom [])

            {:keys [ctx system]}
            (f/env)]
        (with-fakes* (fake-opencode calls (atom {}))
          (fn []
            (start! ctx "workspace" {:title "Poke around" :prompt "List the files"})
            (executor/advance! system run-id)
            (let [run (run-state system run-id)]
              (is
               (= [:completed {:session-id session-id}]
                  ((juxt :status :result) run))))
            (is
             (= [:private "Poke around"]
                ((juxt :visibility :name) (get @store session-id))))
            (is
             (nil? (get-in @store [session-id :stopped])))
            (is
             (= "List the files"
                (nth (first (prompts calls)) 3)))))))))

(deftest failing-steps-are-retried-then-fail-the-run
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)]
        (with-fakes* {#'manager-svc.sessions.api/create-session! (fn [_ _] (throw (RuntimeException. "docker is down")))}
          (fn []
            (start! ctx "analyse" {:brief "Look at the architecture"})
            (executor/advance! system run-id)
            (is
             (= [:preparing 1 "docker is down"]
                (let [run (run-state system run-id)]
                  [(:status run) (:attempts run) (get-in run [:last-step-error :message])])))
            (executor/advance! system run-id)
            (executor/advance! system run-id)
            (is
             (= [:failed "docker is down"]
                ((juxt :status :error) (run-state system run-id))))))))))

(deftest permanent-errors-fail-the-run-at-once
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env {:runs/scm (fn [_ _] (throw (ex-info "Unknown SCM provider" {:permanent true})))})]
        (with-fakes* {}
          (fn []
            (start! ctx "review-prs" {:pull-request 4})
            (executor/advance! system run-id)
            (is
             (= [:failed "Unknown SCM provider"]
                ((juxt :status :error) (run-state system run-id))))))))))

(deftest preparing-again-reuses-the-session
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [{:keys [ctx system]}
            (f/env)

            checkouts
            (atom 0)]
        (swap! store assoc session-id {:id session-id :secret-key "pw" :ready false})
        (with-fakes* {#'git/checkout! (fn [_] (swap! checkouts inc) "abc")}
          (fn []
            (start! ctx "analyse" {:brief "Look at the architecture"})
            (platform! system :mark-run-preparing nil)
            (executor/advance! system run-id)
            (is
             (= [:running session-id]
                ((juxt :status :session-id) (run-state system run-id))))
            (is
             (= 0
                @checkouts))))))))

(deftest cancelled-runs-release-their-session
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [{:keys [ctx system]}
            (f/env)]
        (with-fakes* (fake-opencode (atom []) (atom {"conv-1" {:type "busy"}}))
          (fn []
            (start! ctx "analyse" {:brief "Look at the architecture"})
            (executor/advance! system run-id)
            (f/command! ctx "alice" {:cmd-id :cancel-run :id run-id})
            (executor/advance! system run-id)
            (let [run (run-state system run-id)]
              (is
               (= [:cancelled true]
                  ((juxt :status :session-released) run))))
            (is
             (true? (get-in @store [session-id :stopped])))))))))

(deftest a-session-that-failed-to-start-fails-the-run-at-once
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [{:keys [ctx system]}
            (f/env)]
        (with-fakes* (fake-opencode (atom []) (atom {}))
          (fn []
            (start! ctx "analyse" {:brief "Look at the architecture"})
            (with-redefs [sessions/ready? (constantly false)]
              (executor/advance! system run-id))
            (swap! store update session-id assoc :ready false :error "image not found")
            (executor/advance! system run-id)
            (is
             (= [:failed "The session could not start: image not found"]
                ((juxt :status :error) (run-state system run-id))))))))))

(deftest a-session-that-stopped-before-it-was-ready-is-started-again
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [{:keys [ctx system]}
            (f/env)]
        (with-fakes* (fake-opencode (atom []) (atom {}))
          (fn []
            (start! ctx "analyse" {:brief "Look at the architecture"})
            (with-redefs [sessions/ready? (constantly false)]
              (executor/advance! system run-id))
            (swap! store update session-id assoc :ready false :started false :stopped-at "2026-01-01T00:00:00Z")
            (executor/advance! system run-id)
            (is
             (= [:running true]
                [(:status (run-state system run-id)) (get-in @store [session-id :started])]))))))))

(deftest a-repeated-pass-reuses-the-labelled-conversation
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [calls
            (atom [])

            {:keys [ctx system]}
            (f/env)]
        (with-fakes* (fake-opencode calls (atom {"conv-9" {:type "busy"}}))
          (fn []
            (start! ctx "analyse" {:brief "Look at the architecture"})
            (with-redefs [executor/start-conversation! (fn [& _] :wait)]
              (executor/advance! system run-id))
            (swap! store update-in [session-id :labels] assoc :run-conversation "0:conv-9")
            (swap! calls conj [:prompt "conv-9" "platform/model-a" "earlier pass"])
            (executor/advance! system run-id)
            (is
             (= [:awaiting-result "conv-9"]
                ((juxt :status :conversation) (run-state system run-id))))
            (is
             (= 1
                (count (prompts calls))))
            (is
             (empty? (filter #(= :create (first %)) @calls)))))))))

(deftest a-conversation-of-an-earlier-model-is-not-reused
  (is
   (nil? (executor/reusable-conversation {:labels {:run-conversation "0:conv-1"}} {:model-index 1})))
  (is
   (= "conv-1"
      (executor/reusable-conversation {:labels {:run-conversation "0:conv-1"}} {}))))

(deftest a-run-cancelled-while-preparing-releases-its-session
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [{:keys [ctx system]}
            (f/env)]
        (with-fakes* {#'handlers/prepare! (fn [_] (f/command! ctx "alice" {:cmd-id :cancel-run :id run-id}))}
          (fn []
            (start! ctx "analyse" {:brief "Look at the architecture"})
            (executor/advance! system run-id)
            (is
             (= [:cancelled nil true]
                ((juxt :status :session-id :session-released) (run-state system run-id))))
            (is
             (true? (get-in @store [session-id :stopped])))))))))

(deftest a-successful-pass-clears-earlier-step-errors
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [{:keys [ctx system]}
            (f/env)]
        (with-fakes* {#'sessions/ready? (constantly false)}
          (fn []
            (start! ctx "analyse" {:brief "Look at the architecture"})
            (executor/advance! system run-id)
            (with-redefs [sessions/ready? (fn [& _] (throw (RuntimeException. "opencode unreachable")))]
              (executor/advance! system run-id))
            (is
             (= [:running 1]
                ((juxt :status :attempts) (run-state system run-id))))
            (executor/advance! system run-id)
            (is
             (= [:running 0]
                ((juxt :status :attempts) (run-state system run-id))))))))))

(deftest ci-polls-update-the-run-only-when-the-build-changes
  (is
   (= :check-run-ci
      (executor/ci-command {:state "running" :build {:number 3}} {:state "running" :build {:number 3 :duration 5}})))
  (is
   (= :update-run-ci
      (executor/ci-command {:state "running" :build {:number 3}} {:state "passed" :build {:number 3}})))
  (is
   (= :update-run-ci
      (executor/ci-command {:state "waiting"} {:state "running" :build {:number 1}}))))

(deftest runs-advance-on-the-bounded-pool
  (mock/with-mock-dal
    (f/with-fakes [_]
      (let [submitted
            (atom [])

            pool
            (proxy [java.util.concurrent.AbstractExecutorService] []
              (execute [task] (swap! submitted conj task))
              (shutdown [])
              (shutdownNow [] [])
              (isShutdown [] false)
              (isTerminated [] false)
              (awaitTermination [_ _] true))

            {:keys [ctx system]}
            (f/env {:runs/executor pool})]
        (start! ctx "analyse" {:brief "Look at the architecture"})
        (executor/execute! system)
        (executor/execute! system)
        (is
         (= 1
            (count @submitted)))
        (with-redefs [executor/advance! (fn [_ _] nil)]
          ((first @submitted)))
        (executor/execute! system)
        (is
         (= 2
            (count @submitted)))))))

(deftest sessions-are-shared-as-the-workflow-and-the-team-admin-say
  (let [interactive
        {:interactive true}

        automated
        {:interactive false}]
    (is
     (= {:person-team :private :person-tenant :tenant :automated-team :team :automated-tenant :tenant
         :team-session :team :team-session-for-the-tenant :tenant}
        {:person-team (executor/visibility {:visibility "team"} interactive {:created-by "alice"})
         :person-tenant (executor/visibility {:visibility "tenant"} interactive {:created-by "alice"})
         :automated-team (executor/visibility {:visibility "team"} automated {:platform-started true})
         :automated-tenant (executor/visibility {:visibility "tenant"} automated {:platform-started true})
         :team-session (executor/visibility {:visibility "team"} interactive
                                            {:platform-started true :session-visibility :team})
         :team-session-for-the-tenant (executor/visibility {:visibility "team"} interactive
                                                           {:platform-started true :session-visibility "tenant"})}))
    (testing "a person's choice for their run wins over the workflow"
      (is
       (= {:private-on-a-team-workflow :private :tenant-on-a-team-workflow :tenant :team-on-a-tenant-workflow :team}
          {:private-on-a-team-workflow (executor/visibility {:visibility "team"} automated
                                                            {:created-by "alice" :session-visibility :private})
           :tenant-on-a-team-workflow (executor/visibility {:visibility "team"} interactive
                                                           {:created-by "alice" :session-visibility :tenant})
           :team-on-a-tenant-workflow (executor/visibility {:visibility "tenant"} interactive
                                                           {:created-by "alice" :session-visibility "team"})})))))

(deftest sessions-are-named-after-what-they-work-on
  (is
   (= ["Review · PR #4" "Review · Change 7" "Fix login" "Review"]
      [(executor/session-title {:name "Review"} {:inputs {:pull-request 4} :trigger {:scm {:provider "github"}}})
       (executor/session-title {:name "Review"} {:inputs {:pull-request 7} :trigger {:scm {:provider "gerrit"}}})
       (executor/session-title {:name "Review"} {:inputs {:title "Fix login"}})
       (executor/session-title {:name "Review"} {:inputs {}})])))
