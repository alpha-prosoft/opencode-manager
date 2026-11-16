(ns manager-svc.tokens-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.test-support :as support]
            [manager-svc.tokens :as tokens]))

(def settings
  support/settings)

(def alice
  {:sub "alice"
   :email "alice@acme.test"
   :name "Alice"
   :tenant "acme"
   :teams {"alpha" :admin "ops" :member}
   :tenant-admin true
   :operator false})

(def session-id
  "11111111-1111-1111-1111-111111111111")

(deftest a-persons-tokens-carry-their-identity
  (is
   (= alice
      (tokens/person settings (tokens/platform-token settings alice 60))))
  (is
   (= alice
      (tokens/read-handoff settings (tokens/handoff-token settings alice "s9") "s9")))
  (is
   (= alice
      (tokens/identity-of (tokens/read-session-token settings (tokens/session-token settings alice "s9" 4102444800) "s9"))))
  (testing "an operator without a tenant"
    (let [oscar
          {:sub "oscar" :email "o@x" :name "Oscar" :tenant nil :teams {} :tenant-admin false :operator true}]
      (is
       (= oscar
          (tokens/person settings (tokens/platform-token settings oscar 60)))))))

(deftest a-platform-token-ends
  (is
   (nil?
    (tokens/person settings (tokens/platform-token settings alice -1)))))

(deftest token-audiences-are-separate
  (let [platform-token
        (tokens/platform-token settings alice 60)

        service-token
        (tokens/service-token settings)

        session-token
        (tokens/session-token settings alice session-id 4102444800)

        handoff-token
        (tokens/handoff-token settings alice session-id)]
    (testing "only a platform token is a person"
      (is
       (= [true false false false]
          (mapv #(some? (tokens/person settings %)) [platform-token service-token session-token handoff-token]))))
    (testing "only the service token is the platform"
      (is
       (= [false true false false]
          (mapv #(tokens/service-token? settings %) [platform-token service-token session-token handoff-token]))))
    (testing "only a session host's token opens a session host, and only its own"
      (is
       (= [false false true false]
          (mapv #(some? (tokens/read-session-token settings % session-id))
                [platform-token service-token session-token handoff-token])))
      (is
       (nil?
        (tokens/read-session-token settings session-token "22222222-2222-2222-2222-222222222222"))))
    (testing "only a handoff hands over"
      (is
       (= [false false false true]
          (mapv #(some? (tokens/read-handoff settings % session-id))
                [platform-token service-token session-token handoff-token]))))))

(deftest a-handoff-works-once-and-for-its-session
  (let [token
        (tokens/handoff-token settings alice "s1")]
    (is
     (nil?
      (tokens/read-handoff settings token "s2")))
    (is
     (= "alice"
        (:sub (tokens/read-handoff settings token "s1"))))
    (is
     (nil?
      (tokens/read-handoff settings token "s1")))))
