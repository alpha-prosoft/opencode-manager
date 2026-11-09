(ns manager-svc.git-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-svc.git :as git]
            [manager-svc.test-support :as support])
  (:import [java.nio.file Files LinkOption Paths]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn- key-path
  [ssh-command]
  (second (re-find #"-i (\S+)" ssh-command)))

(deftest ssh-keys-are-owner-only-and-removed-afterwards
  (let [seen
        (atom nil)]
    (git/with-credentials "git@example.com:a/b.git" nil "PRIVATE KEY"
      (fn [_ env]
        (let [path
              (Paths/get (key-path (get env "GIT_SSH_COMMAND")) (make-array String 0))]
          (reset! seen path)
          (is
           (= "rw-------"
              (PosixFilePermissions/toString (Files/getPosixFilePermissions path (make-array LinkOption 0)))))
          (is
           (= "PRIVATE KEY\n"
              (slurp (str path)))))))
    (is
     (not (Files/exists @seen (make-array LinkOption 0))))))

(deftest https-tokens-travel-as-a-header-not-in-arguments
  (git/with-credentials "https://example.com/a.git" "me" "tok"
    (fn [args env]
      (is
       (= {}
          env))
      (is
       (string/starts-with? (second args) "http.extraHeader=Authorization: Basic ")))))

(deftest failures-mask-the-token
  (is
   (= "fatal: could not read from https://***@example.com"
      (git/failure-message "warning: x\nfatal: could not read from https://tok@example.com" "tok" ["clone"]))))

(deftest a-checkout-replaces-a-symlink-without-touching-its-target
  (let [dir
        (support/temp-dir)

        origin
        (io/file dir "origin")

        elsewhere
        (io/file dir "elsewhere")

        target
        (io/file dir "target")]
    (.mkdirs origin)
    (shell/sh "git" "init" "-q" "-b" "main" :dir origin)
    (spit (io/file origin "a.txt") "a")
    (shell/sh "git" "add" "." :dir origin)
    (shell/sh "git" "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "-m" "init" :dir origin)
    (.mkdirs elsewhere)
    (spit (io/file elsewhere "keep.txt") "keep")
    (Files/createSymbolicLink (.toPath target) (.toPath elsewhere) (make-array FileAttribute 0))
    (is
     (re-matches #"[0-9a-f]{40}"
                 (git/checkout {:url (str "file://" (.getPath origin))
                                :branch "main"
                                :target target
                                :local-roots [(.getPath dir)]})))
    (is
     (= "keep"
        (slurp (io/file elsewhere "keep.txt"))))
    (is
     (= "a"
        (slurp (io/file target "a.txt"))))))
