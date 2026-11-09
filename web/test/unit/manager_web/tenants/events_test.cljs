(ns manager-web.tenants.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-web.tenants.events :as events]))

(deftest sync-states
  (is
   (= :failed
      (events/sync-state {:error-message "clone failed" :synced-at "2026-11-09T08:00:00Z"})))
  (is
   (= :synced
      (events/sync-state {:synced-at "2026-11-09T08:00:00Z"})))
  (is
   (= :never
      (events/sync-state {}))))

(deftest operators-sync-one-tenant
  (is
   (= {:cmd-id :sync-tenant :attrs {:tenant "globex"}}
      (:command (events/sync-spec "globex")))))

(deftest registering-trims-the-values
  (is
   (= {:cmd-id :register-tenant :attrs {:name "initech" :onboarding "file:///repos/initech"}}
      (:command (events/register-spec {:name " initech " :onboarding "file:///repos/initech "})))))

(deftest a-reload-reports-each-part
  (let [result
        {:claims-parser {:status "reloaded"}
         :tenants {:status "failed" :error "tenants.json: unexpected end"}
         :identity-provider {:discovery {:status "reloaded"} :keys {:status "unchanged"}}
         :new-tenants ["initech"]}]
    (is
     (= [:claims-parser :tenants :discovery :keys :new-tenants]
        (mapv first (events/reload-parts result))))
    (is
     (= ["Reloaded" "Failed: tenants.json: unexpected end" "Reloaded" "Unchanged" "Synced initech"]
        (mapv #(events/part-text (last %)) (events/reload-parts result))))
    (is
     (= [false true false false false]
        (mapv #(events/part-failed? (last %)) (events/reload-parts result)))))
  (testing "login mode has no identity provider"
    (is
     (= [:claims-parser :tenants :new-tenants]
        (mapv first (events/reload-parts {:claims-parser {:status "reloaded"} :tenants {:status "reloaded"}
                                          :identity-provider nil}))))
    (is
     (= "Nothing to do"
        (events/part-text nil)))
    (is
     (= "None added"
        (events/part-text []))))
  (is
   (= ["POST" "/api/admin/reload-config" [:tenants]]
      ((juxt :method :path :refetch) events/reload-spec))))
