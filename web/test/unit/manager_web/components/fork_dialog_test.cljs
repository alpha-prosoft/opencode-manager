(ns manager-web.components.fork-dialog-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-web.components.fork-dialog :as fork-dialog]))

(def bob
  {:tenant "acme" :sub "bob" :teams [{:id "ops" :name "Operations" :role :member :claimed-role :member}]})

(def erin
  {:tenant "acme"
   :sub "erin"
   :teams [{:id "ops" :name "Operations" :role :member :claimed-role :member}
           {:id "payments" :name "Payments" :role :admin :claimed-role :admin}]})

(def root
  {:tenant "acme"
   :sub "root"
   :tenant-admin true
   :teams [{:id "ops" :name "Operations" :role :admin :claimed-role nil}]})

(deftest a-person-forks-into-the-teams-they-are-a-member-of
  (is
   (= [["ops" "Operations"] ["payments" "Payments"]]
      (fork-dialog/team-options erin)))
  (testing "a tenant admin's reach over every team is not membership"
    (is
     (= []
        (fork-dialog/team-options root)))))

(deftest the-sessions-team-is-chosen-when-it-is-one-of-the-persons
  (is
   (= "payments"
      (fork-dialog/default-team erin {:team-id "payments"})))
  (testing "a session of a team they are not in leaves the choice to them"
    (is
     (nil? (fork-dialog/default-team erin {:team-id "billing"}))))
  (testing "someone with one team has nothing to choose"
    (is
     (= "ops"
        (fork-dialog/default-team bob {:team-id "billing"}))))
  (testing "someone without a team has nowhere to fork into"
    (is
     (nil? (fork-dialog/default-team root {:team-id "ops"})))))

(deftest the-dialog-opens-with-the-session-and-the-default-team
  (is
   (= {:session {:id 1 :name "Fix login"} :team-id "ops"}
      (fork-dialog/request erin {:id 1 :name "Fix login" :team-id "ops" :status :running}))))

(deftest a-fork-needs-a-team-of-the-person
  (is
   (= [true false false false]
      [(fork-dialog/can-fork? erin {:team-id "payments"})
       (fork-dialog/can-fork? erin {:team-id nil})
       (fork-dialog/can-fork? erin {:team-id "billing"})
       (fork-dialog/can-fork? root {:team-id "ops"})])))

(deftest a-fork-lands-in-the-chosen-team-and-opens-the-copy
  (is
   (= {:id [:fork-session 1]
       :error ::fork-dialog/error
       :command {:cmd-id :fork-session :id 2 :attrs {:source-id 1 :team-id "payments"}}
       :refetch-on-failure [:my-credentials]
       :navigate [:session {:session-id "2"}]
       :on-success [::fork-dialog/close]}
      (fork-dialog/fork-spec 1 2 "payments"))))

(deftest the-dialog-says-what-a-fork-is-and-whose-credentials-it-uses
  (is
   (= "You get your own copy of \"Fix login\", private to you. The original stays as it is."
      (fork-dialog/intro {:name "Fix login"})))
  (is
   (= "The copy belongs to Payments and runs with your credentials: your own where set, else Payments', else acme's."
      (fork-dialog/team-help erin "payments")))
  (is
   (= "Choose the team the copy belongs to. It runs with your own credentials where set, else that team's."
      (fork-dialog/team-help erin nil))))
