(ns manager-svc.signin-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.signin :as signin]
            [manager-svc.test-support :as support]
            [manager-svc.tokens :as tokens]))

(def settings
  support/settings)

(deftest signing-in-issues-the-platform-token-of-what-the-parser-found
  (let [{:keys [identity token]}
        (signin/login settings {:sub "f3b2c1d0-0000-4000-8000-000000000001"
                                :preferred_username "dave"
                                :email "dave@acme.test"
                                :tenant "acme"
                                :groups ["/acme/payments/admin" "/acme/ops" "/globex/research"]
                                :realm_access {:roles ["offline_access"]}})]
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
        (tokens/person settings token)))))

(deftest a-refused-sign-in-gets-no-token
  (is
   (= {:refused "Login refused: your account belongs to no tenant"}
      (signin/login settings {:sub "erin" :groups ["/acme/a" "/globex/b"]}))))

(deftest a-login-lasts-the-configured-hours
  (is
   (= 7200
      (signin/login-seconds (assoc settings :auth-ttl-hours 2)))))

(deftest a-test-login-takes-its-claims-from-the-dev-users-file
  (is
   (= {:sub "alice"
       :email "alice@acme.test"
       :name "Alice"
       :tenant "acme"
       :teams {"alpha" :admin}
       :tenant-admin true
       :operator false}
      (:identity (signin/test-login settings "alice"))))
  (testing "someone without a tenant is refused"
    (is
     (= {:refused "Login refused: your account belongs to no tenant"}
        (signin/test-login settings "nobody")))
    (is
     (= {:refused "Login refused: your account belongs to no tenant"}
        (signin/test-login settings "stranger"))))
  (testing "an operator needs no tenant"
    (is
     (= {:tenant nil :operator true}
        (select-keys (:identity (signin/test-login settings "oscar")) [:tenant :operator])))))

(deftest platform-is-reserved
  (is
   (nil?
    (signin/test-login settings "platform")))
  (is
   (nil?
    (signin/test-login settings " Platform ")))
  (is
   (= "platformer"
      (get-in (signin/test-login settings "platformer") [:identity :sub]))))

(deftest settings-are-checked-at-start
  (is
   (nil?
    (signin/check-settings! {:auth-mode "login"})))
  (is
   (nil?
    (signin/check-settings! {:auth-mode "jwt"})))
  (is
   (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown MANAGER_AUTH_MODE none"
                     (signin/check-settings! {:auth-mode "none"}))))
