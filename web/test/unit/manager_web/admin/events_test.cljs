(ns manager-web.admin.events-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.admin.events :as events]
            [manager-web.data :as data]
            [manager-web.components.credential-form :as credential-form]))

(deftest test-results
  (is
   (= {:state :ok :text "Answered in 120 ms"}
      (events/test-result {:ok true :latency-ms 120})))
  (is
   (= {:state :failed :text "401"}
      (events/test-result {:ok false :message "401"}))))

(deftest maintenance-shares-one-busy-flag
  (is
   (= ::events/maintenance
      (:busy (events/maintenance-spec :sync))
      (:busy (events/maintenance-spec :idle)))))

(deftest stopping-idle-sessions-is-only-requested
  (is
   (= [::events/report :idle true "Asked the reaper to stop idle sessions."]
      (:on-success (events/maintenance-spec :idle))))
  (is
   (= {:cmd-id :stop-idle-sessions}
      (:command (events/maintenance-spec :idle)))))

(deftest syncing-is-scoped-to-the-tenant
  (is
   (= {:cmd-id :sync-tenant}
      (:command (events/maintenance-spec :sync)))))

(deftest clearing-a-credential-clears-the-tenant-value
  (is
   (= {:cmd-id :delete-tenant-credential :attrs {:credential-id "JIRA"}}
      (get-in (credential-form/clear-request :tenant {:id "JIRA" :name "Jira"} {}) [:run :command]))))

(deftest credential-help-follows-the-scopes
  (is
   (= "Used by everyone in the tenant and by automated runs."
      (credential-form/tenant-help {:provided-by [:tenant]})))
  (is
   (= "Used by everyone who hasn't added their own, and by automated runs."
      (credential-form/tenant-help {:provided-by ["tenant" "user"]})))
  (is
   (= "Used by every team that hasn't set its own value, and by automated runs."
      (credential-form/tenant-help {:provided-by [:tenant :team]}))))

(deftest usage-per-team
  (is
   (= [{:team-id "demo" :calls 3 :tokens 350 :errors 1 :cost 0.5}
       {:team-id "ops" :calls 1 :tokens 10 :errors 0 :cost nil}]
      (events/team-totals [{:team-id "ops" :calls 1 :prompt-tokens 10 :errors 0}
                           {:team-id "demo" :calls 2 :prompt-tokens 100 :completion-tokens 50 :errors 1 :cost 0.2}
                           {:team-id "demo" :calls 1 :prompt-tokens 200 :errors 0 :cost 0.3}]))))

(deftest required-credentials-say-who-requires-them
  (let [team-name
        {"ops" "Operations" "demo" "Demo team"}]
    (is
     (= "Required by acme (tenant.json, everyone in the teams it applies to)"
        (events/required-text "acme" team-name {:required true :required-by-teams []})))
    (is
     (= "Required by Operations (team.json, its members)"
        (events/required-text "acme" team-name {:required false :required-by-teams ["ops"]})))
    (is
     (= "Required by acme (tenant.json, everyone in the teams it applies to) and Demo team and Operations (team.json, their members)"
        (events/required-text "acme" team-name {:required true :required-by-teams ["demo" "ops"]})))
    (is
     (nil? (events/required-text "acme" team-name {:required false :required-by-teams []})))))

(deftest a-sync-asked-for-here-reports-how-it-went
  (let [asked
        {:before "2026-11-09T08:00:00Z"}

        teams
        [{:id "demo" :status :ok} {:id "ops" :status :error :error-message "clone failed"} {:id "web" :status "ok"}]]
    (is
     (nil? (events/sync-outcome nil {:synced-at "2026-11-09T08:00:00Z"} teams)))
    (is
     (= {:state :pending}
        (events/sync-outcome asked {:synced-at "2026-11-09T08:00:00Z"} teams)))
    (is
     (= {:state :failed :teams 3 :failed 1 :message nil}
        (events/sync-outcome asked {:synced-at "2026-11-09T08:05:00Z"} teams)))
    (is
     (= {:state :synced :teams 2 :failed 0 :message nil}
        (events/sync-outcome asked {:synced-at "2026-11-09T08:05:00Z"} [(first teams) (last teams)])))
    (is
     (= ["The sync is running. This says how it went once it has finished."
         "Synced, but 1 of 3 team repositories failed."
         "Synced: all 2 team repositories are up to date."
         "The sync failed: tenant.json: unexpected end"]
        (mapv events/sync-outcome-text [{:state :pending}
                                        {:state :failed :teams 3 :failed 1}
                                        {:state :synced :teams 2 :failed 0}
                                        {:state :failed :teams 2 :failed 0 :message "tenant.json: unexpected end"}]))))
  (is
   (= {:before "2026-11-09T08:00:00Z"}
      (data/ui (events/asked {:manager-web.data/loads {:admin-overview {:data {:status {:synced-at "2026-11-09T08:00:00Z"}}}}}
                             :sync)
               :admin :sync nil)))
  (is
   (nil? (data/ui (events/asked {} :idle) :admin :sync nil))))
