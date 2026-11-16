(ns manager-svc.jwt-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.jwt :as jwt]
            [manager-svc.test-support :as support]
            [manager-svc.tokens :as tokens]))

(def settings
  (assoc support/settings
         :auth-mode "jwt"
         :jwt-secret "idp-secret"
         :jwt-issuer "https://idp.example"
         :jwt-audience "manager"))

(defn- provider-token
  [claims]
  (support/provider-token "idp-secret" (merge {"tenant" "acme"} claims)))

(defn- person-of
  [settings token]
  (get-in (jwt/exchange settings token) [:identity :sub]))

(deftest a-provider-token-becomes-a-platform-token
  (let [{:keys [identity token]}
        (jwt/exchange settings (provider-token {"sub" "f3b2c1d0-0000-4000-8000-000000000001"
                                                "preferred_username" "dave"
                                                "email" "dave@acme.test"
                                                "groups" ["/acme/payments/admin" "/acme/ops" "/globex/research"]
                                                "realm_access" {"roles" ["offline_access"]}}))]
    (is
     (= {:sub "dave"
         :email "dave@acme.test"
         :name "dave"
         :tenant "acme"
         :teams {"ops" :member "payments" :admin}
         :tenant-admin false
         :operator false}
        identity))
    (is
     (= identity
        (tokens/person settings token)))
    (testing "that lives for the request, not for a login"
      (is
       (<= (:expires-at (tokens/read-token settings token tokens/platform-audience))
           (+ (tokens/now-seconds) jwt/request-seconds))))))

(deftest the-claims-parser-may-refuse-a-valid-token
  (is
   (= {:refused "Login refused: your account belongs to no tenant"}
      (jwt/exchange settings (provider-token {"sub" "erin" "tenant" nil "groups" ["/acme/a" "/globex/b"]}))))
  (is
   (= {:refused "Login refused: the claims parser's answer is invalid (username should be a lowercase username)"}
      (jwt/exchange settings (provider-token {"sub" "Not A Username!"}))))
  (is
   (= {:refused "Login refused: platform is the platform's own name"}
      (jwt/exchange settings (provider-token {"sub" "platform"})))))

(deftest issuer-audience-and-signature-are-checked
  (is
   (= "alice"
      (person-of settings (provider-token {"sub" "alice"}))))
  (is
   (nil?
    (jwt/exchange settings (provider-token {"sub" "alice" "iss" "https://other"}))))
  (is
   (nil?
    (jwt/exchange settings (provider-token {"sub" "alice" "aud" "other-app"}))))
  (is
   (nil?
    (jwt/exchange settings (support/provider-token "another-secret" {"sub" "alice" "tenant" "acme"}))))
  (is
   (nil?
    (jwt/exchange settings nil)))
  (testing "without an issuer no provider token is accepted"
    (is
     (nil?
      (jwt/exchange (dissoc settings :jwt-issuer) (provider-token {"sub" "alice"})))))
  (testing "nor outside jwt mode"
    (is
     (nil?
      (jwt/exchange (assoc settings :auth-mode "login") (provider-token {"sub" "alice"})))))
  (testing "a platform token is no provider token"
    (is
     (nil?
      (jwt/exchange settings (support/platform-token settings {:sub "alice" :tenant "acme"}))))))

(deftest settings-are-checked-at-start
  (is
   (nil?
    (jwt/check-settings! settings)))
  (is
   (nil?
    (jwt/check-settings! {:auth-mode "login"})))
  (is
   (thrown? clojure.lang.ExceptionInfo
            (jwt/check-settings! (dissoc settings :jwt-issuer))))
  (is
   (thrown? clojure.lang.ExceptionInfo
            (jwt/check-settings! (dissoc settings :jwt-audience))))
  (is
   (thrown? clojure.lang.ExceptionInfo
            (jwt/check-settings! (dissoc settings :jwt-secret)))))
