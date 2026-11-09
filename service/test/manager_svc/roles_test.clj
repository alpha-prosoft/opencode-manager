(ns manager-svc.roles-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.roles :as roles]
            [manager-svc.test-support :as support]))

(def bob
  (support/person "bob" {:teams {"demo" :admin "ops" :member}}))

(def alice
  (support/person "alice" {:tenant-admin true}))

(def gina
  (support/person "gina" {:tenant "globex" :tenant-admin true :teams {"demo" :admin}}))

(def oscar
  (support/person "oscar" {:tenant nil :operator true}))

(deftest team-roles-come-from-claims-within-the-tenant
  (is
   (= [:admin :member nil]
      (mapv #(roles/team-role bob "acme" %) ["demo" "ops" "payments"])))
  (testing "a tenant admin is admin of every team of their tenant"
    (is
     (= :admin
        (roles/team-role alice "acme" "payments"))))
  (testing "nothing carries over to another tenant"
    (is
     (nil? (roles/team-role gina "acme" "demo")))
    (is
     (false? (roles/tenant-admin? gina "acme")))
    (is
     (= #{}
        (roles/claimed-team-ids bob "globex")))))

(deftest scoped-checks-pass-for-the-platform
  (let [ctx
        (support/as-platform (support/ctx))]
    (is
     (nil? (roles/require-tenant-admin! ctx "acme")))
    (is
     (nil? (roles/require-team-admin! ctx "acme" "demo")))
    (is
     (nil? (roles/require-operator! ctx)))
    (is
     (nil? (roles/require-tenant! ctx "globex")))))

(defn- status-of
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:status (ex-data e)))))

(deftest scoped-checks-refuse-everyone-else
  (let [as
        (fn [person] (assoc-in (support/ctx) [:meta :user] person))]
    (is
     (= [nil 403 403 403]
        (mapv #(status-of (fn [] (roles/require-tenant-admin! (as %) "acme"))) [alice bob gina oscar])))
    (is
     (= [nil nil 403 403]
        (mapv #(status-of (fn [] (roles/require-team-admin! (as %) "acme" "demo"))) [alice bob gina oscar])))
    (is
     (= [403 403 nil]
        (mapv #(status-of (fn [] (roles/require-operator! (as %)))) [alice gina oscar])))
    (testing "other tenants' data does not exist for a person"
      (is
       (= [nil 404 404]
          (mapv #(status-of (fn [] (roles/require-tenant! (as %) "acme"))) [bob gina oscar]))))))

(deftest teams-from-a-token-may-be-a-sorted-map-of-keywords
  (let [hank
        (support/person "hank" {:tenant "globex" :teams (sorted-map :research "member")})]
    (is
     (= :member
        (roles/team-role hank "globex" "research")))
    (is
     (nil? (roles/team-role hank "globex" "demo")))
    (is
     (false? (roles/member? hank "globex" "demo")))))
