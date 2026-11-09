(ns manager-web.components.team-context-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.team-context :as team-context]))

(def items
  [{:id 1 :team-id "demo"} {:id 2 :team-id "ops"} {:id 3 :team-id "demo"}])

(deftest every-team-without-a-choice
  (is
   (= items
      (team-context/of-team nil items))))

(deftest only-the-chosen-team
  (is
   (= [1 3]
      (mapv :id (team-context/of-team "demo" items)))))

(deftest a-team-the-person-left-is-no-longer-chosen
  (is
   (= "demo"
      (team-context/current-team "demo" [{:id "demo"} {:id "ops"}])))
  (is
   (nil? (team-context/current-team "payments" [{:id "demo"}]))))

(def teams
  [{:id "demo"} {:id "ops"}])

(deftest the-team-page-is-its-own-context
  (is
   (= "ops"
      (team-context/context-team :team "demo" "ops" teams)))
  (is
   (= "demo"
      (team-context/context-team :runs "demo" "ops" teams)))
  (is
   (nil? (team-context/context-team :runs "payments" nil teams)))
  (is
   (= "ops"
      (team-context/context-team :project "demo" "ops" teams))))

(deftest choosing-a-team-keeps-the-page-when-it-shows-one-team
  (is
   (= [:runs {} {:team "ops"}]
      (team-context/target :runs "ops")))
  (is
   (= [:sessions {} {}]
      (team-context/target :sessions nil)))
  (is
   (= [:team {:team-id "ops"}]
      (team-context/target :team "ops")))
  (is
   (= [:teams]
      (team-context/target :team nil)))
  (is
   (= [:team {:team-id "ops"}]
      (team-context/target :project "ops")))
  (is
   (= [:projects {} {:team "ops"}]
      (team-context/target :projects "ops")))
  (is
   (= [:home {} {:team "ops"}]
      (team-context/target :run "ops"))))
