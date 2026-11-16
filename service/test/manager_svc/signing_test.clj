(ns manager-svc.signing-test
  (:require [clojure.test :refer [deftest is]]
            [manager-svc.signing :as signing]
            [manager-svc.test-support :as support])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute PosixFilePermissions]))

(def settings
  support/settings)

(deftest derived-keys-name-their-session
  (let [session-id
        #uuid "11111111-1111-1111-1111-111111111111"

        key
        (signing/derived-key settings "llm" session-id)]
    (is
     (= session-id
        (signing/derived-key-owner settings key #{"llm"})))
    (is
     (nil?
      (signing/derived-key-owner settings key #{"tools"})))
    (is
     (nil?
      (signing/derived-key-owner settings (str key "0") #{"llm"})))
    (is
     (nil?
      (signing/derived-key-owner settings "not-a-uuid.abc" #{"llm"})))))

(deftest a-generated-signing-key-is-private
  (let [dir
        (str (support/temp-dir))

        generated
        (signing/signing-key {:data-dir dir})

        path
        (.toPath (java.io.File. dir "signing.key"))]
    (is
     (= generated
        (signing/signing-key {:data-dir dir})))
    (is
     (= "rw-------"
        (PosixFilePermissions/toString (Files/getPosixFilePermissions path (make-array LinkOption 0)))))))
