(ns manager-web.components.runs-table-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.runs-table :as runs-table]))

(deftest pull-request-chips-name-gerrit-changes
  (is
   (= "Change 3"
      (runs-table/pull-request-label :gerrit {:id 3})))
  (is
   (= "Pushed to Change 3"
      (runs-table/pull-request-label :gerrit {:id 3 :updated true})))
  (is
   (= "Draft PR #3"
      (runs-table/pull-request-label :github {:id 3 :draft true}))))

(deftest narrow-screens-keep-the-team-and-sharing
  (is
   (= "Demo team · Only me"
      (runs-table/context-line {:tenant "acme" :sub "bob" :teams [{:id "demo" :name "Demo team"}]}
                               {:team-id "demo" :created-by "bob" :session-visibility :private}
                               true)))
  (is
   (= ""
      (runs-table/context-line {:tenant "acme"} {:team-id "demo"} false))))

(deftest lists-ask-for-the-runs-of-the-chosen-team
  (is
   (= {:query-id :list-runs :limit runs-table/limit}
      (runs-table/query nil)))
  (is
   (= {:query-id :list-runs :limit runs-table/limit :team-id "ops"}
      (runs-table/query "ops")))
  (is
   (= {:query-id :list-runs :limit runs-table/limit :team-id "ops" :project-id "api"}
      (runs-table/query "ops" "api"))))

(deftest a-list-at-the-limit-may-be-cut
  (is
   (true? (runs-table/cut? (repeat runs-table/limit {}))))
  (is
   (false? (runs-table/cut? (repeat (dec runs-table/limit) {})))))
