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
  [{:keys [sub issuer audience] :or {issuer "https://idp.example" audience "manager"}}]
  (-> (JWT/create)
      (.withSubject sub)
      (.withIssuer issuer)
      (.withAudience (into-array String [audience]))
      (.withExpiresAt (Date/from (.plusSeconds (Instant/now) 600)))
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
      (:sub (auth/login-identity settings "platformer"))))
  (testing "a person token named platform is never a person"
    (is
     (nil?
      (auth/edd-user settings (auth/login-token settings {:sub "platform" :email "p@x" :name "p"}))))))

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
        (auth/session-token settings identity session-id)]
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
