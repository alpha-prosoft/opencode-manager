(ns manager-web.components.sessions-table-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.sessions-table :as sessions-table]))

(deftest the-platform-owns-team-sessions
  (is
   (sessions-table/team-session? {:sub "platform" :team-session true}))
  (is
   (not (sessions-table/team-session? {:sub "platform"})))
  (is
   (not (sessions-table/team-session? {:sub "bob" :team-session false}))))

(def me
  {:tenant "acme" :teams [{:id "demo" :name "Demo team" :role :member :claimed-role :member}]})

(deftest owners
  (is
   (= ["You" "Team Demo team" "bob"]
      (mapv #(sessions-table/owner-label me %) [{:is-owner true :sub "alice"}
                                                {:sub "platform" :team-session true :team-id "demo"}
                                                {:sub "bob"}]))))

(deftest narrow-screens-keep-team-owner-and-sharing
  (is
   (= "Demo team · bob · Private to bob"
      (sessions-table/context-line me {:sub "bob" :team-id "demo" :visibility :private} true)))
  (is
   (= "You · Everyone in acme"
      (sessions-table/context-line me {:sub "alice" :is-owner true :team-id "demo" :visibility :tenant} false))))
