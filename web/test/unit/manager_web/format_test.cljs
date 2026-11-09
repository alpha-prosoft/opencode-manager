(ns manager-web.format-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.format :as format]))

(deftest kw-is-lower-kebab-case
  (is
   (= :approve-with-nits
      (format/kw "APPROVE_WITH_NITS")))
  (is
   (= :following-ci
      (format/kw :following_ci)))
  (is
   (nil? (format/kw nil))))

(deftest label-and-words
  (is
   (= "Pull request"
      (format/label :pull-request)))
  (is
   (= "max tokens"
      (format/words "max_tokens"))))

(deftest truncate-never-overruns
  (is
   (= "abc"
      (format/truncate "abcdef" 3)))
  (is
   (= "ab"
      (format/truncate "ab" 3)))
  (is
   (= ""
      (format/truncate nil 3))))

(deftest short-commit
  (is
   (= "0123456789ab"
      (format/short-commit "0123456789abcdef")))
  (is
   (= "—"
      (format/short-commit ""))))

(deftest numbers
  (is
   (= "1.5k"
      (format/tokens 1500)))
  (is
   (= "2.0M"
      (format/tokens 2000000)))
  (is
   (= "$1.50"
      (format/money 1.5)))
  (is
   (= "—"
      (format/money nil)))
  (is
   (= "1 file"
      (format/plural 1 "file")))
  (is
   (= "2 files"
      (format/plural 2 "file"))))

(deftest run-and-session-states
  (is
   (true?
    (format/run-active? "following_ci")))
  (is
   (false?
    (format/run-active? :cancelled)))
  (is
   (true?
    (format/session-live? "starting")))
  (is
   (false?
    (format/session-live? :stopped))))

(deftest workflow-trigger
  (is
   (= "Runs every 15 min"
      (format/workflow-trigger {:trigger {:type "schedule" :every-minutes 15}})))
  (is
   (= "Started by hand"
      (format/workflow-trigger {}))))

(deftest gerrit-changes-are-named-changes
  (is
   (= "Change 12"
      (format/change-name :gerrit 12)))
  (is
   (= "PR #12"
      (format/change-name "github" 12)))
  (is
   (= "Change 7 in demo-service"
      (format/trigger-label {:type "pull_request" :scm {:provider "gerrit" :repository "demo-service"}
                             :pull-request {:id 7}})))
  (is
   (= "PR #7 in acme/app"
      (format/trigger-label {:type "pull_request" :scm {:provider "bitbucket" :repository "acme/app"}
                             :pull-request {:id 7}}))))

(deftest runs-are-named-by-their-title
  (is
   (= ["Fix the login" "Implement"]
      (mapv format/run-title [{:inputs {:title "Fix the login"} :workflow-name "Implement"}
                              {:inputs {:title " "} :workflow-name "Implement"}]))))

(deftest who-started-a-run
  (is
   (= ["Team session · started by bob" "Started by the platform (schedule)" "Started by the platform (webhook)"]
      (mapv format/trigger-label [{:type "team_session" :by "bob"} {:type "schedule"} {:type "webhook"}]))))

(deftest possessives
  (is
   (= ["Operations'" "acme's"]
      (mapv format/possessive ["Operations" "acme"]))))
