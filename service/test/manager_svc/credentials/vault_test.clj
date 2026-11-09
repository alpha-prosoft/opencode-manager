(ns manager-svc.credentials.vault-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [manager-svc.credentials.fixtures :as fixtures]
            [manager-svc.credentials.vault :as vault]))

(deftest sealed-values-round-trip
  (let [sealed
        (vault/seal fixtures/settings "jira" "alice" {:secret "s3cret-value" :username "alice"})]
    (is
     (= {:secret "s3cret-value" :username "alice"}
        (vault/unseal fixtures/settings "jira" "alice" sealed)))
    (is
     (not (string/includes? sealed "s3cret")))
    (testing "a value is bound to its credential and owner"
      (is
       (thrown? Exception
                (vault/unseal fixtures/settings "jira" "bob" sealed)))
      (is
       (thrown? Exception
                (vault/unseal fixtures/settings "github" "alice" sealed))))))

(deftest key-is-generated-into-the-data-dir-when-not-configured
  (let [data-dir
        (str "target/test-data/vault-" (System/nanoTime))

        settings
        {:data-dir data-dir}

        sealed
        (vault/seal settings "github" "" {:secret "abc"})]
    (is
     (.exists (io/file data-dir "credentials.key")))
    (is
     (= {:secret "abc"}
        (vault/unseal settings "github" "" sealed)))))

(deftest short-keys-are-rejected
  (is
   (thrown-with-msg? Exception #"32 bytes"
                     (vault/master-key {:credentials-key "c2hvcnQ="}))))

(deftest hints-show-only-the-tail-of-long-secrets
  (is
   (= "…cdef"
      (vault/hint {:secret "0123456789abcdef"})))
  (is
   (= "set"
      (vault/hint {:secret "short"})))
  (is
   (= "alice · …cdef"
      (vault/hint {:secret "0123456789abcdef" :username "alice"}))))
