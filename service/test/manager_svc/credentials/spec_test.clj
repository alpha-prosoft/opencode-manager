(ns manager-svc.credentials.spec-test
  (:require [clojure.test :refer [deftest is]]
            [manager-svc.credentials.fixtures :as fixtures]
            [manager-svc.credentials.spec :as spec]))

(deftest declarations-come-with-the-tenant-defaults
  (is
   (= {:header "Authorization" :scheme :bearer}
      (:auth (spec/mcp-server fixtures/tenant-spec "github"))))
  (is
   (= [:tenant]
      (:provided-by (spec/credential fixtures/tenant-spec "llm-key"))))
  (is
   (= [true false]
      [(spec/personal? (spec/credential fixtures/tenant-spec "github"))
       (spec/personal? (spec/credential fixtures/tenant-spec "llm-key"))]))
  (is
   (= [true false]
      [(spec/provides? (spec/credential fixtures/tenant-spec "bitbucket") :team)
       (spec/provides? (spec/credential fixtures/tenant-spec "github") :team)]))
  (is
   (spec/personal-only? (spec/credential fixtures/tenant-spec "jira")))
  (is
   (spec/for-teams? "*" ["alpha"]))
  (is
   (not (spec/for-teams? ["beta"] ["alpha"]))))
