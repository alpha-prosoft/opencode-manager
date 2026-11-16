(ns manager-svc.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.auth :as auth]
            [manager-svc.signin :as signin]
            [manager-svc.test-support :as support]
            [manager-svc.tokens :as tokens]))

(def settings
  support/settings)

(deftest edd-knows-a-person-by-their-platform-token
  (is
   (= {:id "alice"
       :email "alice@acme.test"
       :name "Alice"
       :tenant "acme"
       :teams {"alpha" :admin}
       :tenant-admin true
       :operator false
       :role :user
       :roles [:user]
       :realm :main}
      (auth/edd-user settings (:token (signin/test-login settings "alice"))))))

(deftest edd-knows-the-platform-by-its-service-token
  (is
   (= {:id "platform" :role :non-interactive :roles [:non-interactive]}
      (select-keys (auth/edd-user settings (tokens/service-token settings)) [:id :role :roles]))))

(deftest edd-accepts-only-tokens-the-manager-issued
  (let [alice
        (:identity (signin/test-login settings "alice"))

        jwt-settings
        (assoc settings :auth-mode "jwt" :jwt-secret "idp-secret" :jwt-issuer "https://idp.example" :jwt-audience "manager")]
    (testing "an identity provider's token is nobody, in jwt mode too"
      (is
       (nil?
        (auth/edd-user jwt-settings (support/provider-token "idp-secret" {"sub" "alice" "tenant" "acme"})))))
    (testing "a session host's token and a handoff are nobody"
      (is
       (nil?
        (auth/edd-user settings (tokens/session-token settings alice "s1" 4102444800))))
      (is
       (nil?
        (auth/edd-user settings (tokens/handoff-token settings alice "s1")))))
    (testing "a platform token named platform is never a person"
      (is
       (nil?
        (auth/edd-user settings (support/platform-token {:sub "platform" :email "p@x" :name "p"})))))
    (testing "a token signed with another key is nobody"
      (is
       (nil?
        (auth/edd-user settings (support/platform-token (assoc settings :secret-key "another") alice)))))
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"Not authenticated"
                       ((auth/resolve-user settings) {:req {:headers {:x-authorization "forged"}}})))))
