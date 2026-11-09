(ns manager-web.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-web.routes :as routes]
            [reitit.core :as reitit]))

(def router
  (reitit/router routes/routes))

(defn- matched
  [path]
  (let [match
        (reitit/match-by-path router path)]
    [(get-in match [:data :name]) (:path-params match)]))

(deftest tenant-pages-live-under-the-tenant
  (is
   (= [:home {:tenant "acme"}]
      (matched "/acme")))
  (is
   (= [:runs {:tenant "acme"}]
      (matched "/acme/runs")))
  (is
   (= [:run {:tenant "acme" :run-id "r1"}]
      (matched "/acme/runs/r1")))
  (is
   (= [:session {:tenant "acme" :session-id "s1"}]
      (matched "/acme/sessions/s1")))
  (is
   (= [:admin {:tenant "acme"}]
      (matched "/acme/admin")))
  (is
   (= [:projects {:tenant "acme"}]
      (matched "/acme/projects"))))

(deftest a-team-and-its-project-are-like-an-owner-and-its-repository
  (is
   (= [:team {:tenant "acme" :team-id "demo"}]
      (matched "/acme/demo")))
  (is
   (= [:project {:tenant "acme" :team-id "demo" :project-id "demo-service"}]
      (matched "/acme/demo/demo-service")))
  (is
   (= [:team-alias {:tenant "acme" :team-id "demo"}]
      (matched "/acme/teams/demo"))))

(deftest fixed-paths-win-over-tenants
  (is
   (= [:root {}]
      (matched "/")))
  (is
   (= [:login {}]
      (matched "/login")))
  (is
   (= [:tenants {}]
      (matched "/platform/tenants")))
  (is
   (= [:legacy/run {:run-id "r1"}]
      (matched "/runs/r1")))
  (is
   (= [:legacy/team {:team-id "demo"}]
      (matched "/teams/demo"))))

(deftest paths-of-pages
  (is
   (= "/acme"
      (routes/path "acme" :home nil)))
  (is
   (= "/acme?team=demo"
      (routes/path "acme" :home nil {:team "demo"})))
  (is
   (= "/acme/demo/demo-service?tab=runs"
      (routes/path "acme" :project {:team-id "demo" :project-id "demo-service"} {:tab "runs"})))
  (is
   (= "/acme/runs/r%201"
      (routes/path "acme" :run {:run-id "r 1"})))
  (is
   (= "/platform/tenants"
      (routes/path "acme" :tenants nil)))
  (testing "a tenant page without a tenant leads to the root"
    (is
     (= "/"
        (routes/path nil :runs nil))))
  (testing "every path a page gets is matched back to it"
    (doseq [[page params] [[:home {}] [:runs {}] [:sessions {}] [:teams {}] [:projects {}] [:harnesses {}]
                           [:activities {}] [:accounts {}] [:admin {}]
                           [:run {:run-id "r1"}] [:session {:session-id "s1"}]
                           [:team {:team-id "ops"}] [:project {:team-id "ops" :project-id "api"}]]]
      (is
       (= [page (assoc params :tenant "acme")]
          (matched (routes/path "acme" page params)))))))

(deftest old-urls-redirect-to-the-persons-tenant
  (let [me
        {:tenant "acme"}]
    (is
     (= "/acme/runs/r1"
        (routes/redirect me :legacy/run {:run-id "r1"} {})))
    (is
     (= "/acme/sessions?team=demo"
        (routes/redirect me :legacy/sessions {} {:team "demo"})))
    (is
     (= "/acme/demo?tab=settings"
        (routes/redirect me :legacy/team {:team-id "demo"} {:tab "settings"})))
    (is
     (= "/acme/demo"
        (routes/redirect me :team-alias {:tenant "acme" :team-id "demo"} {})))
    (is
     (= "/acme?team=ops"
        (routes/redirect me :root {} {:team "ops"})))
    (is
     (= "/platform/tenants"
        (routes/redirect me :legacy/tenants {} {})))
    (is
     (nil? (routes/redirect me :runs {:tenant "acme"} {}))))
  (testing "operators outside any tenant go to the platform's tenants"
    (is
     (= "/platform/tenants"
        (routes/redirect {:operator true} :legacy/run {:run-id "r1"} {})))
    (is
     (= "/platform/tenants"
        (routes/redirect {:operator true} :root {} {})))))

(deftest another-tenants-url-is-refused
  (is
   (true? (routes/foreign-tenant? {:tenant "acme"} :run {:tenant "globex" :run-id "r1"})))
  (is
   (false? (routes/foreign-tenant? {:tenant "acme"} :run {:tenant "acme" :run-id "r1"})))
  (is
   (true? (routes/foreign-tenant? {:operator true} :home {:tenant "acme"})))
  (is
   (false? (routes/foreign-tenant? {:tenant "acme"} :tenants {}))))

(deftest what-a-url-leads-to
  (testing "pages that only send people on"
    (is
     (= [:redirect "/platform/tenants"]
        (routes/destination {:operator true} :root {} {})))
    (is
     (= [:redirect "/acme"]
        (routes/destination {:operator true :tenant "acme"} :root {} {})))
    (is
     (= [:redirect "/acme/demo"]
        (routes/destination {:tenant "acme"} :team-alias {:tenant "acme" :team-id "demo"} {}))))
  (testing "a page is shown as it is"
    (is
     (= [:page]
        (routes/destination {:operator true} :tenants {} {})))
    (is
     (= [:page]
        (routes/destination {:tenant "acme"} :runs {:tenant "acme"} {}))))
  (testing "another tenant's URL is refused before it is redirected"
    (is
     (= [:foreign]
        (routes/destination {:tenant "acme"} :team-alias {:tenant "globex" :team-id "demo"} {})))
    (is
     (= [:foreign]
        (routes/destination {:tenant "acme"} :run {:tenant "globex" :run-id "r1"} {})))))
