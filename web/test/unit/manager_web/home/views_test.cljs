(ns manager-web.home.views-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.home.views :as views]))

(def bob
  {:tenant "acme" :teams [{:id "demo" :name "Demo team" :role :member :claimed-role :member}
                          {:id "ops" :name "Operations" :role :admin :claimed-role :admin}]})

(deftest home-says-which-teams-it-shows
  (is
   (= ["Showing your 2 teams." "Showing Operations (admin)." "You're in acme but not in a team yet."]
      [(views/context-text bob nil) (views/context-text bob "ops") (views/context-text {:tenant "acme" :teams []} nil)]))
  (is
   (re-find #"^Workflows of Operations\." (views/workflows-text bob "ops"))))

(deftest people-without-a-team-see-what-the-tenant-shares
  (is
   (= [2]
      (mapv :id (views/shared-with-tenant [{:id 1 :visibility :team} {:id 2 :visibility "tenant"}])))))

(deftest the-reason-workflows-are-hidden-is-one-click-away
  (is
   (= [{:page :team :params {:team-id "ops"}}
       {:page :teams}
       {:page :team :params {:team-id "demo"}}]
      [(views/problems-link bob "ops")
       (views/problems-link bob nil)
       (views/problems-link {:tenant "acme" :teams [{:id "demo"}]} nil)])))
