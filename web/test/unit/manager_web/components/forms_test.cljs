(ns manager-web.components.forms-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.credential-form :as credential-form]
            [manager-web.components.schema-form :as schema-form]
            [manager-web.components.workflows :as workflows]))

(deftest set-input-drops-empty-values
  (is
   (= {:a 1}
      (schema-form/set-input {:a 1 :b "x"} :b "")))
  (is
   (= {:a 1 :b 2}
      (schema-form/set-input {:a 1} :b 2))))

(deftest nullable-any-of-collapses
  (is
   (= {:type "string" :description "Branch" :default "main"}
      (schema-form/resolve-field {:anyOf [{:type "string"} {:type "null"}] :description "Branch" :default "main"}))))

(deftest start-run-spec
  (is
   (= {:id ::workflows/start-run
       :command {:cmd-id :start-run :id 7 :attrs {:team-id "demo" :workflow-id "review" :inputs {:pull-request 3}}}
       :navigate [:run {:run-id "7"}]
       :on-success [::workflows/close]}
      (workflows/start-run-spec {:team-id "demo" :workflow {:id "review"} :inputs {:pull-request 3}} 7))))

(deftest credential-save-spec
  (is
   (= {:id [::credential-form/save :mine "git/hub"]
       :method "PUT"
       :path "/api/credentials/git%2Fhub"
       :body {:secret "s"}
       :edd? true
       :refetch [:my-credentials]
       :on-success [:saved]}
      (credential-form/save-spec {:scope :mine :credential-id "git/hub" :value {:secret "s"}
                                  :refetch [:my-credentials] :on-saved [:saved]}))))
