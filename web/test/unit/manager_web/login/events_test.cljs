(ns manager-web.login.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-web.login.events :as events]
            [manager-web.session :as session]))

(deftest the-server-says-how-people-log-in
  (is
   (= [:oidc :jwt :login :login :login]
      (mapv events/mode-of [{:mode "oidc"} {:mode :jwt} {:mode "login"} {} nil]))))

(deftest entering-the-login-page-asks-for-the-auth-mode
  (let [{:keys [db fx] :as effects}
        (events/init-effects {} {:next "/runs"})]
    (is
     (= "/api/auth/config"
        (get-in effects [:manager-web.api/call :path])))
    (is
     (= "/runs"
        (::events/next db)))
    (is
     (= [nil]
        fx))
    (testing "an older server without the endpoint offers the test login"
      (is
       (= [::events/config-loaded {:mode "login"}]
          (get-in effects [:manager-web.api/call :on-not-found])))))
  (testing "back from the identity provider the login is finished"
    (is
     (= [[:dispatch [::events/logged-in]]]
        (:fx (events/init-effects {} {:next "/runs" :complete "1"})))))
  (testing "next never leaves the site"
    (is
     (= "/"
        (::events/next (:db (events/init-effects {} {:next "//evil.example"})))))))

(deftest once-logged-in-setup-knows-who-the-person-is
  (let [[me credentials]
        (:fx (events/logged-in-effects))]
    (is
     (= [:dispatch [:manager-web.session/load-me]]
        me))
    (is
     (= {:query-id :get-my-credentials}
        (get-in credentials [1 1 :query])))))

(deftest the-test-login-lists-personas-the-provider-login-does-not
  (is
   (= [[:dispatch [::events/load-personas]]]
      (:fx (events/config-loaded {} {:mode "login"}))))
  (let [{:keys [db fx]}
        (events/config-loaded {} {:mode "oidc" :error "Login refused: your account belongs to no tenant"})]
    (is
     (= [nil]
        fx))
    (is
     (= [:oidc "Login refused: your account belongs to no tenant"]
        [(::events/mode db) (::events/refused db)]))))

(deftest signing-in-goes-to-the-provider-login-with-next
  (is
   (= "/api/auth/oidc/login?next=%2Fruns%3Fteam%3Ddemo"
      (events/sign-in-url "/runs?team=demo"))))

(deftest logging-out-ends-the-provider-session-when-the-server-says-so
  (is
   (= "https://idp.example/logout?client_id=manager"
      (::session/assign-location (session/logged-out-effects {} {:redirect "https://idp.example/logout?client_id=manager"}))))
  (testing "otherwise the login page"
    (doseq [result [nil "" "Network error" {:redirect "javascript:alert(1)"}]]
      (let [effects
            (session/logged-out-effects {} result)]
        (is
         (nil? (::session/assign-location effects)))
        (is
         (= [[:dispatch [:edd.events/navigate {:url "/login" :replace? true}]]]
            (:fx effects)))))))
