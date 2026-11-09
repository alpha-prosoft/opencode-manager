(ns manager-web.session-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.format :as format]
            [manager-web.components.credential-form :as credential-form]))

(deftest provided-by-is-a-list-of-scopes
  (is
   (= #{:tenant :user}
      (format/scopes ["tenant" "user"])))
  (is
   (= "Tenant admins, Each person"
      (format/provided-by-label [:user :tenant])))
  (is
   (format/shared-source? "team"))
  (is
   (not (format/shared-source? :own))))

(deftest clearing-a-team-credential
  (is
   (= {:cmd-id :delete-team-credential :attrs {:team-id "demo" :credential-id "JIRA"}}
      (get-in (credential-form/clear-request [:team "demo"] {:id "JIRA" :name "Jira"} {}) [:run :command]))))
