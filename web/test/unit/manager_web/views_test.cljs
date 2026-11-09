(ns manager-web.views-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.activities.views :as activities]
            [manager-web.admin.views :as admin]
            [manager-web.components.audit :as audit]
            [manager-web.components.live-activity :as live-activity]
            [manager-web.components.project-card :as project-card]
            [manager-web.harnesses.views :as harnesses]))

(deftest a-harness-says-where-each-credential-comes-from
  (is
   (= ["Jira: your account" "Jira: team account" "Jira: shared account" "Jira: shared account"
       "Jira: needs your credential"]
      (mapv #(harnesses/credential-text {:name "Jira" :source %}) ["user" :team :tenant "platform" nil]))))

(deftest harnesses-are-grouped-by-team-in-their-order
  (is
   (= [["ops" [{:team-id "ops" :id "a"} {:team-id "ops" :id "c"}]]
       ["demo" [{:team-id "demo" :id "b"}]]]
      (harnesses/by-team [{:team-id "ops" :id "a"} {:team-id "demo" :id "b"} {:team-id "ops" :id "c"}]))))

(deftest activity-fields-show-their-type-and-defaults
  (is
   (= ["minor | major" "Source" "string | integer" "boolean" "object"]
      (mapv activities/field-type [{:enum ["minor" "major"]}
                                   {:$ref "#/definitions/Source"}
                                   {:anyOf [{:type "string"} {:type "integer"} {:type "null"}]}
                                   {:type "boolean"}
                                   {}])))
  (is
   (= {:post true}
      (activities/defaults {:properties {:post {:default true} :branch {:type "string"}}}))))

(deftest admin-names-jobs-and-todays-runs
  (is
   (= ["Sync repositories"
       "Stop idle sessions"
       "Run on a schedule (scheduled-workspace · ops)"
       "Check for new pull requests (review · demo)"
       "Workflow trigger (acme-demo-review)"
       "other"]
      (mapv admin/job-name [{:id "tenant-sync"}
                            {:id "reap-sessions"}
                            {:id "trigger-acme-ops-scheduled-workspace" :kind "schedule" :team "ops" :workflow "scheduled-workspace"}
                            {:id "trigger-acme-demo-review" :kind "pull_request" :team "demo" :workflow "review"}
                            {:id "trigger-acme-demo-review"}
                            {:id "other"}])))
  (is
   (= "2 completed, 1 failed"
      (admin/runs-today-hint (array-map :completed 2 :failed 1))))
  (is
   (= "none yet"
      (admin/runs-today-hint {}))))

(deftest audit-records-are-summarised
  (is
   (= ["Prompt, 12 tokens in, 0 out" "Agent reply" "bash: Run tests" "“Fix login”" "note"]
      (mapv audit/summary [{:type "message" :payload {:role "user" :tokens {:input 12}}}
                           {:type :message :payload {:role "assistant"}}
                           {:type "tool" :payload {:tool "bash" :state {:title "Run tests"}}}
                           {:type "conversation" :payload {:title "Fix login"}}
                           {:type "note"}]))))

(deftest the-monitor's-events-read-as-sentences
  (is
   (= [{:text "Blocked bash: rm is denied" :color :warning}
       {:text "edit: a.clj" :color :success}
       {:text "Edited src/a.clj" :color :default}
       {:text "Agent finished and is waiting" :color :default}]
      (mapv live-activity/describe [{:kind "tool.blocked" :tool "bash" :detail {:reason "rm is denied"}}
                                    {:kind :tool.end :tool "edit" :detail {:title "a.clj"}}
                                    {:kind "event" :detail {:event "file.edited" :details "{\"file\":\"src/a.clj\"}"}}
                                    {:kind "event" :detail {:event "session.idle"}}])))
  (is
   (= [3]
      (mapv :id (live-activity/shown-events [{:id 1 :kind "tool.start"}
                                             {:id 2 :kind "event" :detail {:event "session.status"}}
                                             {:id 3 :kind "tool.end"}])))))

(deftest a-project-card-names-its-repository-and-build
  (is
   (= "github acme/api"
      (project-card/repository-label {:scm {:provider :github :repository "acme/api"}})))
  (is
   (= ["CI api-build via jenkins" "CI first" nil]
      (mapv project-card/ci-label [{:ci {:job "api-build" :mcp-server "jenkins"}}
                                   {:ci {:jobs ["first" "second"]}}
                                   {}]))))
