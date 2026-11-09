(ns manager-web.accounts.events-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [manager-web.components.credential-form :as credential-form]))

(defn- remove-request
  [credential]
  (credential-form/clear-request :mine credential {:error ::action :refetch [:my-credentials]}))

(deftest removing-a-credential-says-what-follows
  (testing "a required one closes the platform to the person"
    (is
     (true?
      (string/includes? (:body (remove-request {:id "JIRA_TOKEN" :name "Jira" :provided-by ["tenant" "user"]
                                                :required true}))
                        "stays closed to you"))))
  (testing "a shared value takes over where one may be set"
    (is
     (true?
      (string/includes? (:body (remove-request {:id "BITBUCKET_TOKEN" :name "Bitbucket"
                                                :provided-by ["tenant" "user"]}))
                        "shared team or tenant account"))))
  (testing "a personal one stops its tools"
    (is
     (true?
      (string/includes? (:body (remove-request {:id "JIRA_TOKEN" :name "Jira" :provided-by ["user"]}))
                        "stop working for you"))))
  (let [request
        (remove-request {:id "JIRA_TOKEN" :name "Jira" :provided-by ["user"]})]
    (is
     (= {:cmd-id :delete-my-credential :attrs {:credential-id "JIRA_TOKEN"}}
        (get-in request [:run :command])))
    (is
     (= ["Remove your Jira credential?" "Remove" ::action [:my-credentials]]
        [(:title request) (:confirm request) (get-in request [:run :error]) (get-in request [:run :refetch])]))))
