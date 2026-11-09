(ns manager-svc.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.auth :as auth]
            [manager-svc.test-support :as support])
  (:import [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [java.nio.file Files LinkOption]
           [java.nio.file.attribute PosixFilePermissions]
           [java.time Instant]
           [java.util Date]))

(def settings
  support/settings)

(def jwt-settings
  (assoc settings
         :auth-mode "jwt"
         :jwt-secret "idp-secret"
         :jwt-issuer "https://idp.example"
         :jwt-audience "manager"))

(defn- idp-token
  [{:keys [sub issuer audience claims]
    :or {issuer "https://idp.example" audience "manager" claims {"tenant" "acme"}}}]
  (-> (JWT/create)
      (.withSubject sub)
      (.withIssuer issuer)
      (.withAudience (into-array String [audience]))
      (.withExpiresAt (Date/from (.plusSeconds (Instant/now) 600)))
      (.withPayload ^java.util.Map claims)
      (.sign (Algorithm/HMAC256 "idp-secret"))))

(deftest platform-is-reserved
  (is
   (nil?
    (auth/login-identity settings "platform")))
  (is
   (nil?
    (auth/login-identity settings " Platform ")))
  (is
   (= "platformer"
      (get-in (auth/login-identity settings "platformer") [:identity :sub])))
  (testing "a person token named platform is never a person"
    (is
     (nil?
      (auth/edd-user settings (auth/login-token settings {:sub "platform" :email "p@x" :name "p"}))))))

(deftest a-test-login-takes-its-claims-from-the-dev-users-file
  (is
   (= {:sub "alice"
       :email "alice@acme.test"
       :name "Alice"
       :tenant "acme"
       :teams {"alpha" :admin}
       :tenant-admin true
       :operator false}
      (:identity (auth/login-identity settings "alice"))))
  (testing "someone without a tenant is refused"
    (is
     (= {:refused "Login refused: your account belongs to no tenant"}
        (auth/login-identity settings "nobody")))
    (is
     (= {:refused "Login refused: your account belongs to no tenant"}
        (auth/login-identity settings "stranger"))))
  (testing "an operator needs no tenant"
    (is
     (= {:tenant nil :operator true}
        (select-keys (:identity (auth/login-identity settings "oscar")) [:tenant :operator])))))

(deftest tokens-carry-tenant-teams-and-roles
  (let [identity
        (:identity (auth/login-identity settings "alice"))

        token
        (auth/login-token settings identity)]
    (is
     (= identity
        (auth/person settings token)))
    (is
     (= {:id "alice" :tenant "acme" :teams {"alpha" :admin} :tenant-admin true :operator false :role :user}
        (select-keys (auth/edd-user settings token) [:id :tenant :teams :tenant-admin :operator :role])))
    (testing "handoff and session tokens carry them too"
      (is
       (= identity
          (auth/read-handoff settings (auth/handoff-token settings identity "s9") "s9")))
      (is
       (= identity
          (auth/identity-of settings (auth/read-session-token settings (auth/session-token settings identity "s9" 4102444800) "s9")))))))

(deftest keycloak-tokens-go-through-the-claims-parser
  (let [token
        (idp-token {:sub "f3b2c1d0-0000-4000-8000-000000000001"
                    :claims {"preferred_username" "dave"
                             "email" "dave@acme.test"
                             "tenant" "acme"
                             "groups" ["/acme/payments/admin" "/acme/ops" "/globex/research"]
                             "realm_access" {"roles" ["offline_access"]}}})]
    (is
     (= {:sub "dave"
         :email "dave@acme.test"
         :name "dave"
         :tenant "acme"
         :teams {"ops" :member "payments" :admin}
         :tenant-admin false
         :operator false}
        (auth/person jwt-settings token))))
  (testing "a refused token is a 403 with the parser's reason"
    (let [token
          (idp-token {:sub "erin" :claims {"groups" ["/acme/a" "/globex/b"]}})]
      (is
       (nil?
        (auth/person jwt-settings token)))
      (is
       (= {:status 403 :message "Login refused: your account belongs to no tenant"}
          (try
            (auth/edd-user jwt-settings token)
            (catch clojure.lang.ExceptionInfo e
              (ex-data e))))))))

(deftest jwt-mode-checks-issuer-audience-and-subject
  (is
   (= "alice"
      (:sub (auth/person jwt-settings (idp-token {:sub "alice"})))))
  (is
   (nil?
    (auth/person jwt-settings (idp-token {:sub "alice" :issuer "https://other"}))))
  (is
   (nil?
    (auth/person jwt-settings (idp-token {:sub "alice" :audience "other-app"}))))
  (is
   (nil?
    (auth/person jwt-settings (idp-token {:sub "Not A Username!"}))))
  (is
   (nil?
    (auth/person jwt-settings (idp-token {:sub "platform"}))))
  (testing "without issuer and audience no external token is accepted"
    (is
     (nil?
      (auth/person (dissoc jwt-settings :jwt-issuer) (idp-token {:sub "alice"}))))))

(deftest settings-are-checked-at-start
  (is
   (nil?
    (auth/check-settings! {:auth-mode "login"})))
  (is
   (nil?
    (auth/check-settings! jwt-settings)))
  (is
   (thrown? clojure.lang.ExceptionInfo
            (auth/check-settings! (dissoc jwt-settings :jwt-issuer))))
  (is
   (thrown? clojure.lang.ExceptionInfo
            (auth/check-settings! (dissoc jwt-settings :jwt-audience))))
  (is
   (thrown? clojure.lang.ExceptionInfo
            (auth/check-settings! {:auth-mode "none"}))))

(deftest duplicate-cookies-are-dropped
  (is
   (= {"other" "1"}
      (auth/parse-cookies "oc_auth=a; other=1; oc_auth=b")))
  (is
   (= {"oc_auth" "a"}
      (auth/parse-cookies "oc_auth=a"))))

(deftest cookies-get-the-host-prefix-on-https
  (is
   (= "oc_auth"
      (auth/login-cookie settings)))
  (is
   (= "__Host-oc_auth"
      (auth/login-cookie (assoc settings :public-scheme "https"))))
  (is
   (= "__Host-oc_auth=t; Path=/; HttpOnly; SameSite=Lax; Max-Age=10; Secure"
      (auth/set-cookie (assoc settings :public-scheme "https") "__Host-oc_auth" "t" 10))))

(deftest token-audiences-are-separate
  (let [identity
        {:sub "alice" :email "alice@x" :name "Alice"}

        session-id
        "11111111-1111-1111-1111-111111111111"

        session-token
        (auth/session-token settings identity session-id 4102444800)]
    (testing "a session host's token is no login token"
      (is
       (nil?
        (auth/edd-user settings session-token)))
      (is
       (nil?
        (auth/caller-token settings {"oc_auth" session-token} nil))))
    (testing "it works only for its session"
      (is
       (= "alice"
          (:sub (auth/read-session-token settings session-token session-id))))
      (is
       (nil?
        (auth/read-session-token settings session-token "22222222-2222-2222-2222-222222222222"))))
    (testing "a login token is no session token"
      (is
       (nil?
        (auth/read-session-token settings (auth/login-token settings identity) session-id))))))

(deftest a-handoff-works-once
  (let [token
        (auth/handoff-token settings {:sub "alice" :email "a@x" :name "A"} "s1")]
    (is
     (= "alice"
        (:sub (auth/read-handoff settings token "s1"))))
    (is
     (nil?
      (auth/read-handoff settings token "s1")))))

(deftest derived-keys-name-their-session
  (let [session-id
        #uuid "11111111-1111-1111-1111-111111111111"

        key
        (auth/derived-key settings "llm" session-id)]
    (is
     (= session-id
        (auth/derived-key-owner settings key #{"llm"})))
    (is
     (nil?
      (auth/derived-key-owner settings key #{"tools"})))
    (is
     (nil?
      (auth/derived-key-owner settings (str key "0") #{"llm"})))
    (is
     (nil?
      (auth/derived-key-owner settings "not-a-uuid.abc" #{"llm"})))))

(deftest a-generated-signing-key-is-private
  (let [dir
        (str (Files/createTempDirectory "auth" (make-array java.nio.file.attribute.FileAttribute 0)))

        generated
        (auth/signing-key {:data-dir dir})

        path
        (.toPath (java.io.File. dir "signing.key"))]
    (is
     (= generated
        (auth/signing-key {:data-dir dir})))
    (is
     (= "rw-------"
        (PosixFilePermissions/toString (Files/getPosixFilePermissions path (make-array LinkOption 0)))))))
