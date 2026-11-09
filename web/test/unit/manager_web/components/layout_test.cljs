(ns manager-web.components.layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-web.components.layout :as layout]))

(def me
  {:tenant "acme" :teams [{:id "ops" :name "Operations" :role :member :claimed-role :member}]
   :tenant-teams [{:id "ops" :name "Operations"} {:id "payments" :name "Payments"}]})

(def els
  {:tenant :t :team :m :project :p :list :l})

(deftest breadcrumbs-of-something-of-a-team
  (testing "members follow every segment"
    (is
     (= [{:label "acme" :page :home :el-id :t}
         {:label "Operations" :el-id :m :page :team :params {:team-id "ops"}}
         {:label "api" :el-id :p :page :project :params {:team-id "ops" :project-id "api"}}
         {:label "Runs" :page :runs :query {:team "ops"} :el-id :l}
         {:label "Review 12"}]
        (layout/entity-segments me {:team-id "ops" :project "api"
                                    :list {:label "Runs" :page :runs :query {:team "ops"}}
                                    :label "Review 12" :els els}))))
  (testing "a team the viewer isn't in, and its project, are plain text"
    (is
     (= [:home nil nil :sessions nil]
        (mapv :page (layout/entity-segments me {:team-id "payments" :project "ledger"
                                                :list {:label "Sessions" :page :sessions}
                                                :label "Shared" :els els}))))
    (is
     (= "Payments"
        (:label (second (layout/entity-segments me {:team-id "payments" :label "Shared"}))))))
  (testing "a team's own page has only the tenant above it"
    (is
     (= ["acme" "Operations"]
        (mapv :label (layout/entity-segments me {:label "Operations"}))))))

(deftest definition-problems-read-as-text
  (is
   (= ["harness missing" "bad model"]
      (layout/error-texts ["harness missing" {:message "bad model"} "" "harness missing"]))))
