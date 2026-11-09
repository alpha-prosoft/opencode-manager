(ns manager-svc.platform.spec-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [manager-svc.platform.catalog :as catalog]
            [manager-svc.git :as git]
            [manager-svc.platform.spec :as spec]))

(def harness
  {:llm {:connection "llm" :models ["model-a"]}})

(defn- errors
  [schema raw]
  (:errors (spec/validate schema raw)))

(defn- mentions?
  [fragment messages]
  (boolean (some #(string/includes? % fragment) messages)))

(deftest snake-case-files-decode-to-kebab-case
  (let [{:keys [value errors]}
        (spec/parse-platform (slurp "../examples/onboarding/platform.json"))]
    (is
     (= []
        errors))
    (is
     (= :platform-or-user
        (get-in value [:credentials 0 :provided-by])))
    (is
     (= :github-copilot
        (get-in value [:llm-connections 0 :type])))
    (is
     (= {:input 3.0 :output 15.0 :cached-input 0.3}
        (get-in value [:llm-connections 0 :pricing :claude-sonnet-5.5])))
    (is
     (= ["github.com"]
        (get-in value [:secret-hosts :GITHUB_TOKEN])))
    (is
     (= {:daily-tokens 2000000 :max-running-sessions 2}
        (get-in value [:teams 1 :budget])))))

(deftest defaults-are-filled-in-nested-objects
  (let [{:keys [value]}
        (spec/validate spec/harness-spec harness)]
    (is
     (= {:cpus 1.0 :memory-mb 2048 :pids 512}
        (:resources value)))
    (is
     (= {:deny-commands [] :deny-paths []}
        (:policy value)))
    (is
     (= :none
        (:network value)))))

(deftest specs-reject-unknown-fields-and-bad-values
  (is
   (mentions? "privileged: disallowed key" (errors spec/harness-spec (assoc harness :privileged true))))
  (is
   (mentions? "set exactly one of prompt or prompt_file" (errors spec/agent-spec {:prompt "x" :prompt_file "y"})))
  (is
   (mentions? "trigger.every_minutes"
              (errors spec/workflow-spec {:name "x" :activity "review" :agent "a" :harness "h"
                                          :trigger {:type "schedule" :every_minutes 1}})))
  (is
   (mentions? "teams.0.repository.url"
              (:errors (spec/parse-platform {:teams [{:id "t" :name "T" :repository {:url "ftp://x/y"}}]}))))
  (is
   (mentions? "invalid regular expression"
              (errors spec/harness-spec (assoc harness :policy {:deny_commands ["("]})))))

(deftest team-source-types-default-to-git
  (let [kinds
        (for [repository [{:url "https://git.example.com/x.git"}
                          {:type "git" :url "git@git.example.com:org/x.git"}
                          {:type "url" :url "https://artifacts.example.com/x.tar.gz"}
                          {:type "path" :location "/repos/x"}]]
          (get-in (spec/validate spec/team-entry {:id "t" :name "T" :repository repository})
                  [:value :repository :type]))]
    (is
     (= [:git :git :url :path]
        kinds))
    (is
     (seq (errors spec/team-entry {:id "t" :name "T" :repository {:type "url" :url "http://insecure/x.tgz"}})))))

(deftest mcp-servers-need-declared-credentials-and-allowed-hosts
  (let [base
        {:credentials [{:id "TOKEN" :name "Token" :hosts ["tools.internal"]}]}

        server
        {:id "tools" :name "Tools" :url "http://tools.internal/mcp" :credential "TOKEN"}]
    (is
     (= []
        (:errors (spec/parse-platform (assoc base :mcp_servers [server])))))
    (is
     (mentions? "may not be sent"
                (:errors (spec/parse-platform (assoc base :mcp_servers [(assoc server :url "https://evil.example/mcp")])))))
    (is
     (mentions? "undeclared credential"
                (:errors (spec/parse-platform {:mcp_servers [server]}))))))

(deftest platform-rules
  (testing "ids are unique"
    (is
     (mentions? "duplicate llm connection ids"
                (:errors (spec/parse-platform {:llm_connections [{:id "a" :secret "K" :models ["m"]}
                                                                 {:id "a" :secret "K" :models ["m"]}]})))))
  (testing "LLM secrets cannot be personal"
    (is
     (mentions? "must be provided_by platform"
                (:errors (spec/parse-platform {:llm_connections [{:id "a" :secret "K" :models ["m"]}]
                                               :credentials [{:id "K" :name "K" :provided_by "user"}]})))))
  (testing "a credential test is a url or an MCP tool"
    (is
     (mentions? "set exactly one of url or mcp_tool"
                (:errors (spec/parse-platform {:credentials [{:id "K" :name "K" :test {}}]})))))
  (testing "the $schema key is ignored"
    (is
     (= []
        (:errors (spec/parse-platform {:$schema "x"}))))))

(deftest activity-options-and-inputs-decode
  (is
   (= {:post-mode :dry-run :max-comments 30 :guidelines-file "g.md"}
      (:value (catalog/validate-options "review" {:guidelines_file "g.md"}))))
  (is
   (= {:pull-request 4}
      (:value (catalog/validate-inputs "review" {:pull_request 4}))))
  (is
   (= {:branch "feature/x" :base "main"}
      (:value (catalog/validate-inputs "review" {:branch "feature/x"} {:repository {:url "file:///repos/x"}}))))
  (is
   (mentions? "branch" (:errors (catalog/validate-inputs "review" {:pull_request 4} {:repository {:url "file:///repos/x"}}))))
  (is
   (mentions? "task" (:errors (catalog/validate-inputs "implement" {:task "short"}))))
  (is
   (mentions? "unknown activity" (:errors (catalog/validate-inputs "nope" {})))))

(deftest catalog-describes-activities-with-json-schemas
  (let [review
        (catalog/activity "review")]
    (is
     (= ["session" "review" "implement" "analysis"]
        (map :id @catalog/activities)))
    (is
     (= "submit_review"
        (:result-tool review)))
    (is
     (= ["pull_request"]
        (get-in review [:inputs-schema :required])))
    (is
     (= ["branch"]
        (get-in (catalog/inputs-schema "review" {:repository {:url "file:///repos/x"}}) [:required])))
    (is
     (= (:inputs-schema review)
        (catalog/inputs-schema "review" {:scm {:provider :github}})))))

(deftest git-hosts-and-urls
  (is
   (= "bitbucket.org"
      (git/host-of "git@bitbucket.org:acme/repo.git")))
  (is
   (= "github.com"
      (git/host-of "ssh://git@github.com/acme/repo.git")))
  (is
   (thrown-with-msg? clojure.lang.ExceptionInfo #"must live under"
                     (git/check-url "file:///etc" ["/repos"])))
  (is
   (thrown-with-msg? clojure.lang.ExceptionInfo #"only https://"
                     (git/check-url "http://example.com/x.git" ["/repos"])))
  (is
   (nil?
    (git/check-url "file:///repos/x" ["/repos"]))))
