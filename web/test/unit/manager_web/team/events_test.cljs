(ns manager-web.team.events-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.data :as data]
            [manager-web.team.events :as events]))

(def catalog
  [{:id "session" :interactive true} {:id "review" :interactive false}])

(def team
  {:id "demo"
   :name "Demo"
   :definitions [{:kind :workflows :id "workspace" :valid true :spec {:activity "session"}}
                 {:kind :workflows :id "broken" :valid false :spec {:activity "session"}}
                 {:kind :workflows :id "review" :valid true :spec {:activity "review"}}
                 {:kind :agents :id "engineer" :valid true}]})

(deftest team-sessions-start-from-valid-interactive-workflows
  (is
   (= ["workspace"]
      (mapv #(get-in % [:workflow :id]) (events/interactive-workflows team catalog)))))

(deftest members-see-whether-the-team-set-a-credential
  (is
   (= [:team "Team value set"]
      (events/member-credential-state {:id "BITBUCKET" :set true})))
  (is
   (= [:missing "No team value"]
      (events/member-credential-state {:id "NEXUS" :set false})))
  (is
   (= "Team admins set the team's value. Without it, the tenant's value is used where one is set. You may add your own value instead."
      (events/member-credential-note {:provided-by ["tenant" "team" "user"]})))
  (is
   (= "Team admins set the team's value."
      (events/member-credential-note {:provided-by ["team"]}))))

(deftest running-sessions-count-against-the-team-limit
  (is
   (= 1
      (events/running-count "demo" [{:team-id "demo" :status :running}
                                    {:team-id "demo" :status :stopped}
                                    {:team-id "ops" :status :running}]))))

(deftest limits-show-what-is-used
  (is
   (= [[:daily-tokens "Tokens today" "1.5k" "of 5.0M a day"]
       [:running-sessions "Running sessions" "1" "of at most 2"]
       [:idle-minutes "Idle timeout" "30 min" "Idle sessions stop after this"]]
      (events/limit-items {:budget {:daily-tokens 5000000 :max-running-sessions 2 :idle-minutes 30 :used-today 1500}} 1)))
  (is
   (= ["No daily limit" "No limit" "From each harness"]
      (mapv last (events/limit-items {:budget {}} 0)))))

(deftest budget-use
  (is
   (= 50
      (events/budget-percent {:daily-tokens 1000 :used-today 500})))
  (is
   (= 100
      (events/budget-percent {:daily-tokens 1000 :used-today 5000})))
  (is
   (= 0
      (events/budget-percent {}))))

(deftest every-member-sees-the-team-settings
  (is
   (= :settings
      (first (last events/tabs)))))

(deftest links-may-open-a-tab
  (is
   (= [:settings :workflows :workflows]
      [(data/tab-of events/tabs "settings") (data/tab-of events/tabs "nope") (data/tab-of events/tabs nil)])))
