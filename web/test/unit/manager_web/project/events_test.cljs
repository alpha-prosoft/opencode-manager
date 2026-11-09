(ns manager-web.project.events-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.data :as data]
            [manager-web.project.events :as events]))

(deftest the-projects-workflows-belong-to-its-team
  (is
   (= [{:team-id "demo" :team-name "Demo" :workflow {:id "review"}}]
      (events/workflow-items {:team {:id "demo" :name "Demo"} :workflows [{:id "review"}]}))))

(deftest builds-are-runs-with-a-ci-build
  (is
   (= ["b"]
      (mapv :id (events/builds [{:id "a" :outcome {:pull-request {:id 1}}}
                                {:id "b" :outcome {:build {:state "success" :number 3}}}
                                {:id "c"}])))))

(deftest tabs-from-links
  (is
   (= :runs
      (data/tab-of events/tabs "runs")))
  (is
   (= :workflows
      (data/tab-of events/tabs "nope"))))

(deftest changes-are-named-like-their-provider-names-them
  (is
   (= "Change 12 · Fix"
      (events/change-label :gerrit {:id 12 :title "Fix"})))
  (is
   (= "PR #3 · Add"
      (events/change-label "github" {:id 3 :title "Add"}))))
