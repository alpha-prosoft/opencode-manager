(ns manager-svc.webhooks.mapping-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.webhooks.mapping :as mapping]))

(deftest body-paths
  (is
   (= ["pull_request" "title"]
      (mapping/parse-path "$.pull_request.title")))
  (is
   (= ["commits" 0 "id"]
      (mapping/parse-path "$.commits[0].id")))
  (is
   (= ["commits" 0 "id"]
      (mapping/parse-path "commits.0.id")))
  (is
   (= ["a/b" "c~d" 2]
      (mapping/parse-path "/a~1b/c~0d/2")))
  (is
   (= ["weird key"]
      (mapping/parse-path "$['weird key']")))
  (is
   (= []
      (mapping/parse-path "$")))
  (is
   (nil? (mapping/parse-path "a..b")))
  (is
   (= [true "x"]
      (mapping/at-path {"a" [{"b" "x"}]} ["a" 0 "b"])))
  (is
   (= [false nil]
      (mapping/at-path {"a" []} ["a" 0 "b"]))))

(def schema
  {:properties {:brief {:type "string"}
                :pull_request {:anyOf [{:type "integer"} {:type "null"}]}
                :draft {:type "boolean"}}})

(def request
  (mapping/request-data {:headers {"X-Event" "push" "Content-Type" "application/json"}
                         :query {:ref "main"}
                         :body-text "{\"pr\": {\"number\": \"42\", \"labels\": [\"a\"]}, \"draft\": \"true\"}"}))

(deftest inputs-from-every-source
  (is
   (= {:inputs {"brief" "push" "pull_request" 42 "draft" true}
       :missing []}
      (mapping/inputs schema
                      [{:input "brief" :source :header :value "x-event"}
                       {:input "pull_request" :source :body :value "$.pr.number"}
                       {:input "draft" :source "body" :value "draft"}]
                      request)))
  (testing "objects become JSON text, constants and query params pass, the raw body is the whole text"
    (is
     (= {"brief" "[\"a\"]"}
        (:inputs (mapping/inputs schema [{:input "brief" :source :body :value "$.pr.labels"}] request))))
    (is
     (= {"brief" "main"}
        (:inputs (mapping/inputs schema [{:input "brief" :source :query :value "ref"}] request))))
    (is
     (= {"brief" "fixed text"}
        (:inputs (mapping/inputs schema [{:input "brief" :source :constant :value "fixed text"}] request))))
    (is
     (= {"brief" "{\"pr\": {\"number\": \"42\", \"labels\": [\"a\"]}, \"draft\": \"true\"}"}
        (:inputs (mapping/inputs schema [{:input "brief" :source :raw-body}] request)))))
  (testing "missing values are left out"
    (is
     (= {:inputs {} :missing ["brief"]}
        (mapping/inputs schema [{:input "brief" :source :body :value "$.nope"}] request)))))

(deftest forms-are-bodies-too
  (is
   (= {"title" "a b" "n" "1"}
      (mapping/parse-body "application/x-www-form-urlencoded" "title=a+b&n=1"))))

(deftest validation-messages-become-field-errors
  (is
   (= [{:field "brief" :message "should be at least 10 characters"}
       {:field nil :message "invalid type"}]
      (mapping/field-errors ["brief: should be at least 10 characters" "file: invalid type"]))))

(deftest mapping-problems
  (is
   (= ["The workflow has no input nope"
       "Say where brief comes from"
       "Input brief is mapped twice"]
      (mapping/mapping-problems schema [{:input "nope" :source :constant :value "x"}
                                        {:input "brief" :source :header :value ""}
                                        {:input "brief" :source :raw-body}])))
  (is
   (= ["The body path of brief is invalid"]
      (mapping/mapping-problems schema [{:input "brief" :source :body :value "a..b"}]))))

(deftest values-that-cannot-be-read-are-left-as-they-are
  (is
   (nil? (mapping/parse-body "application/x-www-form-urlencoded" "brief=%zz")))
  (is
   (= [1.0E300 7]
      [(mapping/coerce {:type "integer"} 1.0E300) (mapping/coerce {:type "integer"} 7.0)])))

(deftest rows-are-normalized
  (is
   (= [{:input "brief" :source :body :value "$.a"}
       {:input "brief" :source :raw-body}
       {:input nil :source nil :value "5"}
       {:input "brief" :source :constant :value " kept as typed "}]
      (mapv mapping/row [{:input " brief " :source "body" :value " $.a "}
                         {:input "brief" :source :raw-body :value "ignored"}
                         {:source 5 :value 5}
                         {:input "brief" :source "constant" :value " kept as typed "}]))))
