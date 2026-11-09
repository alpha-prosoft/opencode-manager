(ns manager-svc.observability.authorization-test
  "Who may read a session's audit trail and activity, usage and metrics,
   and that nobody reads another tenant's."
  (:require [clojure.test :refer [deftest is testing]]
            [edd.security :as security]
            [edd.test.fixture.dal :as mock]
            [manager-svc.auth :as auth]
            [manager-svc.client :as client]
            [manager-svc.observability.core :as core]
            [manager-svc.observability.edge :as edge]
            [manager-svc.observability.metrics :as metrics]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.test-support :as support]))

(def session-id
  #uuid "6f1c5a43-5555-4a52-9c1b-6a3f0b6a0001")

(defn- ctx
  []
  (security/register (core/register (support/ctx))))

(defn- status
  [f]
  (try
    (f)
    :ok
    (catch clojure.lang.ExceptionInfo e
      (or (:status (ex-data e))
          (ex-message e)))))

(defmacro ^:private with-private-session
  [& body]
  `(with-redefs [sessions/get-session (fn [_# id#]
                                        (when (= session-id id#)
                                          {:id session-id :sub "alice" :visibility :private :tenant "acme" :team-id "alpha"}))
                 sessions/can-view? (fn [_# session# caller#]
                                      (or (= (:id caller#) (:sub session#))
                                          (true? (:tenant-admin caller#))))]
     ~@body))

(deftest a-session-trail-is-read-by-those-who-see-the-session
  (with-private-session
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            read-as
            (fn [username query-id]
              (status #(mock/query (support/as-user ctx username) {:query-id query-id :session-id session-id})))]
        (is
         (= {:list-audit-events 404 :verify-audit 404 :get-session-activity 404}
            (into {} (for [query-id [:list-audit-events :verify-audit :get-session-activity]]
                       [query-id (read-as "mallory" query-id)]))))
        (is
         (= {:list-audit-events :ok :verify-audit :ok :get-session-activity :ok}
            (into {} (for [query-id [:list-audit-events :verify-audit :get-session-activity]]
                       [query-id (read-as "alice" query-id)]))))
        (testing "an unknown session is not found either"
          (is
           (= 404
              (status #(mock/query (support/as-user ctx "alice") {:query-id :verify-audit :session-id (random-uuid)})))))))))

(deftest another-tenants-session-trail-does-not-exist
  (with-private-session
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            read-as
            (fn [user query-id]
              (status #(mock/query (assoc-in ctx [:meta :user] user) {:query-id query-id :session-id session-id})))]
        (doseq [user [(support/person "alice" {:tenant "globex"})
                      (support/person "gina" {:tenant "globex" :tenant-admin true})]]
          (is
           (= {:list-audit-events 404 :verify-audit 404 :get-session-activity 404}
              (into {} (for [query-id [:list-audit-events :verify-audit :get-session-activity]]
                         [query-id (read-as user query-id)])))))
        (testing "while a tenant admin of the session's tenant reads it"
          (is
           (= :ok
              (read-as (support/person "root" {:tenant-admin true}) :list-audit-events))))))))

(deftest admin-usage-is-for-tenant-admins
  (with-private-session
    (mock/with-mock-dal
      (let [ctx
            (ctx)]
        (is
         (= {"alice" 403 "root" :ok}
            {"alice" (status #(mock/query (support/as-user ctx "alice") {:query-id :get-admin-usage :days 1}))
             "root" (status #(mock/query (support/as-user ctx "root" {:tenant-admin true}) {:query-id :get-admin-usage :days 1}))}))))))

(defn- cookies-of
  ([username]
   (cookies-of username {}))
  ([username opts]
   {"oc_auth" (auth/login-token support/settings
                                (merge {:sub username
                                        :email (str username "@users.local")
                                        :name username
                                        :tenant "acme"
                                        :teams {}}
                                       opts))}))

(deftest metrics-are-for-operators
  (with-private-session
    (with-redefs [client/query (fn [_ _] {:usage [] :tools []})
                  sessions/running-session-ids (fn [& _] [])]
      (let [system
            {:settings support/settings}

            scrape
            (fn [cookies]
              (status #(metrics/handler system {:cookies cookies :headers {}})))]
        (is
         (= {:anonymous 401 :member 403 :tenant-admin 403 :operator :ok}
            {:anonymous (scrape {})
             :member (scrape (cookies-of "alice"))
             :tenant-admin (scrape (cookies-of "root" {:tenant-admin true}))
             :operator (scrape (cookies-of "oscar" {:tenant nil :operator true}))}))))))

(deftest the-audit-export-reads-as-the-caller
  (let [asked
        (atom [])]
    (with-redefs [client/query-as (fn [_ token query]
                                    (swap! asked conj [(:sub (auth/person support/settings token)) (:session-id query)])
                                    [])]
      (let [system
            {:settings support/settings}

            path
            (str "/api/sessions/" session-id "/audit/export")]
        (is
         (= 401
            (status #(edge/export-audit system {:path path :cookies {} :headers {}}))))
        (edge/export-audit system {:path path :cookies (cookies-of "alice") :headers {}})
        (is
         (= [["alice" (str session-id)]]
            @asked))))))
