(ns manager-web.admin.events-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.admin.events :as events]))

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
