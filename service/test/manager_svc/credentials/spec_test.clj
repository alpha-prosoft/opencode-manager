(ns manager-svc.credentials.spec-test
  (:require [clojure.test :refer [deftest is]]
            [manager-svc.credentials.fixtures :as fixtures]
            [manager-svc.credentials.spec :as spec]))

(deftest declarations-come-with-the-platform-defaults
  (is
   (= {:header "Authorization" :scheme :bearer}
      (:auth (spec/mcp-server fixtures/platform-spec "github"))))
  (is
   (= [true false]
      [(spec/personal? (spec/credential fixtures/platform-spec "github"))
       (spec/personal? (spec/credential fixtures/platform-spec "llm-key"))]))
  (is
   (spec/for-teams? "*" ["alpha"]))
  (is
   (not (spec/for-teams? ["beta"] ["alpha"]))))
