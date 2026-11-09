(ns manager-web.teams.views-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.teams.views :as views]))

(deftest team-cards-list-projects-with-repository-and-builds
  (is
   (= "Demo service · gerrit demo-service · builds demo-service-verify"
      (views/project-line {:id "demo-service" :name "Demo service"
                           :scm {:provider "gerrit" :repository "demo-service"}
                           :ci {:mcp-server "jenkins" :jobs ["demo-service-verify"]}})))
  (is
   (= "app"
      (views/project-line {:id "app" :scm {}}))))

(deftest team-cards-carry-the-role
  (is
   (= ["admin" "member" nil]
      (mapv views/role-attribute [:admin "member" nil]))))
