(ns manager-svc.authorization-test
  "The role every command and query admits, for the whole service. A new
   command or query fails here until its roles are written down; the
   data-dependent checks (team, owner, admin) are tested in each domain."
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [malli.generator :as generator]
            [malli.util :as malli-util]
            [manager-svc.service :as service]
            [manager-svc.test-support :as support]))

(def platform
  [:non-interactive])

(def people
  [:user])

(def both
  [:user :non-interactive])

(def commands
  {:append-audit platform
   :cancel-run people
   :check-run-ci platform
   :complete-run platform
   :create-session platform
   :delete-my-credential people
   :delete-platform-credential people
   :delete-session both
   :fail-run platform
   :finalize-run platform
   :follow-run-ci platform
   :fork-session people
   :label-session platform
   :lock-session people
   :mark-credentials-seen both
   :mark-run-preparing platform
   :mark-trigger-items-seen platform
   :record-model-unavailable platform
   :record-platform-sync platform
   :record-review-comment platform
   :record-run-conversation platform
   :record-run-notified platform
   :record-run-nudge platform
   :record-run-outcome platform
   :record-run-session platform
   :record-run-session-released platform
   :record-run-step-error platform
   :record-run-step-recovered platform
   :record-schedule-run platform
   :record-session-deleted platform
   :record-session-failed platform
   :record-session-fork-prepared platform
   :record-session-started platform
   :record-session-stopped platform
   :record-team-sync platform
   :record-telemetry platform
   :record-usage platform
   :register-schedule platform
   :remove-directory-row platform
   :retry-run people
   :roll-up-telemetry platform
   :roll-up-usage platform
   :set-my-credential people
   :set-platform-credential people
   :start-run both
   :start-session both
   :stop-idle-sessions both
   :stop-session both
   :submit-run-result platform
   :sync-platform both
   :touch-session platform
   :update-run-ci platform
   :update-session both
   :upsert-directory-row platform})

(def queries
  {:check-credential platform
   :count-running-sessions platform
   :get-admin-overview people
   :get-admin-usage both
   :get-audit-summary platform
   :get-idle-stop-request platform
   :get-me people
   :get-my-credentials people
   :get-observability-metrics platform
   :get-personas platform
   :get-platform people
   :get-platform-state platform
   :get-registry platform
   :get-run people
   :get-run-source platform
   :get-schedule-states platform
   :get-schemas people
   :get-session people
   :get-session-activity both
   :get-session-record platform
   :get-stored-credential platform
   :get-team people
   :get-team-tokens-today platform
   :get-team-usage platform
   :get-trigger-items platform
   :get-usage-by-user platform
   :get-usage-rows platform
   :get-usage-summary platform
   :list-activities people
   :list-admin-credentials people
   :list-admin-users people
   :list-audit-events both
   :list-audit-keys platform
   :list-conversations people
   :list-harnesses people
   :list-mcp-servers people
   :list-open-pull-requests people
   :list-pending-runs platform
   :list-people-runs-since platform
   :list-runs people
   :list-runs-since platform
   :list-schedules people
   :list-session-rows platform
   :list-sessions people
   :list-teams people
   :test-llm-connection people
   :verify-audit both})

(defn- registered
  [ctx kind]
  (into {} (map (fn [[id options]] [id (get-in options [:auth :role])])) (get-in ctx [:edd-core kind])))

(defn- service-ctx
  []
  (service/register (support/ctx)))

(def ^:private fixed-samples
  "Bodies whose ids are derived from their values, which a generated value
   doesn't satisfy."
  {:roll-up-usage {:attrs {:scope :total :ts "2026-01-01T00:00:00Z"}}})

(defn- sample
  "A body that passes the action's schema, so the call reaches authorization."
  [ctx kind action-id]
  (try
    (or (get fixed-samples action-id)
        (generator/generate (-> (get-in ctx [:edd-core kind action-id :consumes])
                                (malli-util/dissoc :cmd-id)
                                (malli-util/dissoc :query-id))
                            {:seed 7 :size 3}))
    (catch Exception _
      {})))

(defn- forbidden-command?
  [ctx cmd-id]
  (try
    (= "Forbidden"
       (get-in (mock/handle-cmd ctx (merge (sample ctx :commands cmd-id) {:cmd-id cmd-id :id (random-uuid)}))
               [:error :message]))
    (catch Throwable _
      false)))

(defn- forbidden-query?
  [ctx query-id]
  (try
    (mock/query ctx (assoc (sample ctx :queries query-id) :query-id query-id))
    false
    (catch Throwable e
      (= "Forbidden" (ex-message e)))))

(defn- allowed
  "The actions of `table` admitting exactly `roles` that ctx is not refused."
  [forbidden? ctx table roles]
  (vec (sort (remove #(forbidden? ctx %) (keys (filter (comp #{roles} val) table))))))

(deftest every-command-and-query-declares-who-may-call-it
  (let [ctx
        (service-ctx)]
    (is
     (= commands
        (registered ctx :commands)))
    (is
     (= queries
        (registered ctx :queries)))))

(deftest people-cannot-call-what-only-the-platform-may
  (mock/with-mock-dal
    (let [person
          (support/as-user (service-ctx) "mallory")]
      (testing "commands"
        (is
         (= []
            (allowed forbidden-command? person commands platform))))
      (testing "queries"
        (is
         (= []
            (allowed forbidden-query? person queries platform)))))))

(deftest the-platform-cannot-act-as-a-person
  (mock/with-mock-dal
    (let [platform-ctx
          (support/as-platform (service-ctx))]
      (testing "commands"
        (is
         (= []
            (allowed forbidden-command? platform-ctx commands people))))
      (testing "queries"
        (is
         (= []
            (allowed forbidden-query? platform-ctx queries people)))))))
