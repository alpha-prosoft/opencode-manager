(ns manager-web.home.subs-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.home.subs :as subs]))

(def team
  {:id "demo" :name "Demo" :definitions [{:kind :workflows :id "review"}]})

(deftest workflows-wait-for-every-team
  (is
   (nil? (:data (subs/workflows-load {:data [{:id "demo"} {:id "ops"}]} [{:data team} nil])))))

(deftest a-failed-team-still-shows-the-others
  (let [{:keys [data error]}
        (subs/workflows-load {:data [{:id "demo"} {:id "ops"}]} [{:data team} {:error "ops is broken"}])]
    (is
     (= [{:team-id "demo" :team-name "Demo" :workflow {:kind :workflows :id "review"}}]
        data))
    (is
     (= "ops is broken"
        error))))

(deftest no-teams-means-no-workflows
  (is
   (= []
      (:data (subs/workflows-load {:data []} [])))))

(deftest a-failed-team-list-is-an-error
  (is
   (= {:data nil :error "down"}
      (subs/workflows-load {:error "down"} []))))
