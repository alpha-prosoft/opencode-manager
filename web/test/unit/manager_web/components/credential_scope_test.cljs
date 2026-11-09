(ns manager-web.components.credential-scope-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.credential-scope :as scope]))

(def bitbucket
  {:provided-by ["tenant" "team" "user"]})

(deftest where-the-value-comes-from
  (is
   (= [:own :team :tenant :tenant :missing]
      (mapv #(scope/resolution (assoc bitbucket :state %)) ["own" :team :tenant :platform :missing]))))

(deftest overriding-needs-user-in-provided-by
  (is
   (scope/may-override? bitbucket))
  (is
   (not (scope/may-override? {:provided-by [:tenant :team]}))))

(deftest falls-back-to-the-shared-scopes-in-order
  (is
   (= [:team :tenant]
      (scope/shared-scopes {:provided-by [:user :tenant :team]})))
  (is
   (= []
      (scope/shared-scopes {:provided-by [:user]}))))

(deftest explanations
  (is
   (= "Your own value is used instead of your team's or the tenant's value. Removing it falls back to your team's or the tenant's."
      (scope/explanation (assoc bitbucket :state :own))))
  (is
   (= "Your team's value is used. You may add your own to act as yourself."
      (scope/explanation (assoc bitbucket :state :team))))
  (is
   (= "Your team's value is used. Only your team admins can change it."
      (scope/explanation {:provided-by [:tenant :team] :state :team})))
  (is
   (= "Each person provides their own; you haven't yet."
      (scope/explanation {:provided-by [:user] :state :missing}))))

(deftest explanations-name-the-team-and-tenant
  (is
   (= "Demo team's value is used. You may add your own to act as yourself."
      (scope/explanation (assoc bitbucket :state :team) {:team "Demo team" :tenant "acme"})))
  (is
   (= "Not set. Only admins can set Demo team's or acme's value."
      (scope/explanation {:provided-by [:tenant :team] :state :missing} {:team "Demo team" :tenant "acme"}))))

(deftest one-line-per-team-for-people-in-several
  (let [credential
        {:teams [{:team-id "demo" :state :tenant} {:team-id "ops" :state :team} {:team-id "payments" :state :missing}]}]
    (is
     (= ["demo: tenant value" "ops: team value" "payments: missing"]
        (mapv second (scope/team-breakdown identity credential))))
    (is
     (nil? (scope/team-breakdown identity {:teams [{:team-id "demo" :state :team}]})))))

(deftest admins-are-sent-where-they-set-the-value
  (let [credential
        {:provided-by [:tenant :team] :teams [{:team-id "demo"} {:team-id "ops"}]}]
    (is
     (= [[:team "demo" {:tab "settings"}] [:tenant nil {:tab "credentials"}]]
        (mapv (juxt :kind :team-id :query)
              (scope/admin-links {:tenant "acme"} credential {:team-admin? #{"demo"} :tenant-admin? true}))))
    (is
     (empty? (scope/admin-links {:tenant "acme"} credential {:team-admin? #{} :tenant-admin? false})))))

(deftest the-service-says-who-may-provide-their-own
  (is
   (scope/may-override? {:provided-by ["tenant"] :may-provide true}))
  (is
   (not (scope/may-override? {:provided-by ["user"] :may-provide false}))))

(deftest what-to-add-before-something-works
  (is
   (= "Add your Jira and GitHub credential to use these tools."
      (scope/missing-text ["Jira" "GitHub"] "these tools"))))
