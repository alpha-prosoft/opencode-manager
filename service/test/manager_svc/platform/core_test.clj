(ns manager-svc.platform.core-test
  (:require [clojure.test :refer [deftest is]]
            [edd.security :as security]
            [edd.test.fixture.dal :as mock]
            [manager-svc.platform.api :as api]
            [manager-svc.platform.core :as platform]
            [manager-svc.test-support :as support]))

(deftest get-me-works-before-the-first-sync
  (mock/with-mock-dal
    (let [ctx
          (support/as-user (security/register (platform/register (support/ctx (platform/components support/settings))))
                           "alice")]
      (is
       (= {:sub "alice"
           :email "alice@users.local"
           :name "alice"
           :tenant "acme"
           :tenant-admin false
           :operator false
           :teams []
           :tenant-teams []}
          (mock/query ctx {:query-id :get-me}))))))

(deftest workers-sync-at-startup-and-on-request
  (let [jobs
        (platform/workers {:settings (assoc support/settings :sync-interval-minutes 5)})]
    (is
     (= [[:tenant-sync 300 0 nil] [:tenant-sync-requested 60 30 true] [:config-reload 10 0 nil]]
        (map (juxt :id :interval-seconds :initial-delay-seconds :reactive) jobs)))))

(deftest a-failed-cache-load-does-not-skip-the-startup-sync
  (let [synced
        (atom 0)]
    (with-redefs [api/load-cache! (fn [_] (throw (ex-info "edd not ready" {})))
                  api/sync! (fn [_] (swap! synced inc))]
      (platform/startup-sync (platform/components support/settings))
      (is
       (= 1
          @synced)))))
