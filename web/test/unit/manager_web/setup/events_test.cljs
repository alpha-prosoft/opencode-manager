(ns manager-web.setup.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-web.data :as data]
            [manager-web.setup.events :as events]))

(deftest pending-credentials-are-those-the-person-may-provide
  (is
   (= [{:id "bitbucket" :provided-by ["team" "user"]}]
      (events/pending-credentials {:credentials [{:id "bitbucket" :provided-by ["team" "user"]}
                                                 {:id "github" :provided-by ["user"]}
                                                 {:id "nexus" :provided-by ["tenant" "team"]}]
                                   :pending ["bitbucket" "nexus"]}))))

(def credentials
  [{:id "bitbucket" :provided-by ["team" "user"] :state "missing"}
   {:id "jira" :provided-by ["user"] :state "tenant" :required true}
   {:id "github" :provided-by ["user"] :state "own" :required true}])

(deftest required-ones-come-first-and-can't-be-left-for-later
  (is
   (= ["jira" "bitbucket"]
      (mapv :id (events/pending-credentials {:credentials credentials :pending ["jira" "bitbucket"]})))
   "in the order the service offers them")
  (testing "a required credential the person hasn't added is owed, even with a tenant value"
    (is
     (= [false true false]
        (mapv #(events/owed? % #{}) credentials))))
  (is
   (true? (events/owes-required? credentials #{})))
  (testing "saving it in setup settles it"
    (is
     (false? (events/owes-required? credentials #{"jira"})))))

(deftest finishing-leaves-setup-either-way
  (let [spec
        (events/finish-spec ["bitbucket"])]
    (is
     (= {:cmd-id :mark-credentials-seen :attrs {:credentials ["bitbucket"]}}
        (:command spec)))
    (is
     (= (:on-success spec)
        (:on-failure spec)))))

(deftest the-service-says-who-may-provide
  (is
   (= ["jira"]
      (mapv :id (events/pending-credentials {:credentials [{:id "jira" :provided-by ["tenant"] :may-provide true}
                                                           {:id "nexus" :provided-by ["user"] :may-provide false}]
                                             :pending ["jira" "nexus"]})))))

(deftest setup-is-left-on-what-the-service-has-now
  (let [db
        {::events/next "/acme/runs" ::events/step 2 ::events/saved #{"jira"}}]
    (testing "with nothing owed the app opens on the fresh credentials"
      (let [settled
            {:credentials credentials :missing-required []}

            effects
            (events/left db settled)]
        (is
         (= [[:dispatch [:manager-web.session/continue-to "/acme/runs"]]]
            (:fx effects)))
        (is
         (= settled
            (data/loaded-data (:db effects) :my-credentials)))))
    (testing "while a required one is owed the steps start again"
      (let [effects
            (events/left db {:credentials credentials :missing-required ["jira"]})]
        (is
         (nil? (:fx effects)))
        (is
         (= [0 #{}]
            [(::events/step (:db effects)) (::events/saved (:db effects))]))))
    (is
     (= [::events/left]
        (:on-success events/leave-spec)))))
