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
        (spec/parse-tenant (slurp "../examples/tenants/acme/onboarding/tenant.json"))]
    (is
     (= []
        errors))
    (is
     (= [:tenant :team :user]
        (get-in value [:credentials 0 :provided-by])))
    (is
     (= [:user]
        (get-in value [:credentials 1 :provided-by])))
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
     (= ["demo" "payments" "ops"]
        (mapv :id (:teams value))))))

(deftest team-config-decodes-and-checks-against-the-tenant
  (let [tenant
        (:value (spec/parse-tenant (slurp "../examples/tenants/acme/onboarding/tenant.json")))

        {:keys [value errors]}
        (spec/parse-team-config tenant "demo" (slurp "../examples/tenants/acme/teams/demo/.platform/team.json"))]
    (is
     (= []
        errors))
    (is
     (= {:daily-tokens 5000000 :max-running-sessions 10 :idle-minutes 30}
        (:budget value)))
    (is
     (= [{:id "copilot" :models ["claude-sonnet-5.5" "claude-opus-5.5" "gemini-3.5-flash"]}]
        (spec/team-llm-connections tenant "demo" value)))
    (is
     (= ["bitbucket" "jenkins"]
        (spec/team-mcp-servers tenant "demo" value)))
    (testing "no team.json is an empty configuration"
      (is
       (= {:value {:version 1 :budget {} :projects [] :required-credentials []} :errors []}
          (spec/parse-team-config tenant "demo" nil))))
    (testing "unknown keys, bad limits and what the tenant doesn't offer are refused"
      (is
       (mentions? "colour: disallowed key" (:errors (spec/parse-team-config tenant "demo" {:colour "red"}))))
      (is
       (mentions? "budget.daily_tokens" (:errors (spec/parse-team-config tenant "demo" {:budget {:daily_tokens 1}}))))
      (is
       (mentions? "does not offer model gpt-9"
                  (:errors (spec/parse-team-config tenant "demo" {:llm_connections [{:id "copilot" :models ["gpt-9"]}]}))))
      (is
       (mentions? "projects.web.ci: MCP server teamcity is not configured"
                  (:errors (spec/parse-team-config tenant "demo" {:projects [{:id "web"
                                                                              :scm {:provider "github" :repository "acme/web"}
                                                                              :ci {:mcp_server "teamcity" :jobs ["web"]}}]})))))
    (testing "the tenant's grant limits what a team.json can choose"
      (let [scoped
            (assoc-in tenant [:llm-connections 0 :teams] ["ops"])]
        (is
         (mentions? "does not grant connection copilot to team demo"
                    (:errors (spec/parse-team-config scoped "demo" {:llm_connections [{:id "copilot"}]}))))
        (is
         (= []
            (spec/team-llm-connections scoped "demo" {})))))))

(deftest required-credentials-must-be-ones-people-provide
  (let [tenant
        (:value (spec/parse-tenant (slurp "../examples/tenants/acme/onboarding/tenant.json")))

        team-errors
        (fn [team-id required]
          (:errors (spec/parse-team-config tenant team-id {:required_credentials required})))]
    (testing "the examples require PagerDuty of ops (tenant.json) and Jira of ops' members (its team.json)"
      (is
       (= {:id "PAGERDUTY_TOKEN" :required true :provided-by [:user] :teams ["ops"]}
          (select-keys (spec/credential tenant "PAGERDUTY_TOKEN") [:id :required :provided-by :teams])))
      (is
       (= {:value ["JIRA_TOKEN"] :errors []}
          (update (spec/parse-team-config tenant "ops" (slurp "../examples/tenants/acme/teams/ops/.platform/team.json"))
                  :value :required-credentials))))
    (testing "credentials are optional unless marked"
      (is
       (false? (:required (spec/credential tenant "BITBUCKET_TOKEN")))))
    (testing "tenant.json can't require what people can't provide"
      (is
       (mentions? "credential K is required of people but they can't provide it"
                  (:errors (spec/parse-tenant {:credentials [{:id "K" :name "K" :provided_by ["tenant" "team"] :required true}]}))))
      (is
       (= []
          (:errors (spec/parse-tenant {:credentials [{:id "K" :name "K" :provided_by ["tenant" "user"] :required true}]})))))
    (testing "team.json requires only declared credentials people provide and the tenant grants the team"
      (is
       (= []
          (team-errors "demo" ["JIRA_TOKEN" "BITBUCKET_TOKEN"])))
      (is
       (mentions? "required_credentials: credential NOPE_TOKEN is not declared by the tenant"
                  (team-errors "demo" ["NOPE_TOKEN"])))
      (is
       (mentions? "required_credentials: people can't provide credential NEXUS_TOKEN"
                  (team-errors "demo" ["NEXUS_TOKEN"])))
      (is
       (mentions? "required_credentials: the tenant does not grant credential PAGERDUTY_TOKEN to team demo"
                  (team-errors "demo" ["PAGERDUTY_TOKEN"])))
      (is
       (mentions? "duplicate required credential ids"
                  (team-errors "demo" ["JIRA_TOKEN" "JIRA_TOKEN"])))
      (is
       (mentions? "required_credentials.0"
                  (team-errors "demo" ["jira"]))))))

(deftest provided-by-names-scopes
  (is
   (= [:tenant]
      (get-in (spec/validate spec/credential-spec {:id "K" :name "K"}) [:value :provided-by])))
  (is
   (mentions? "provided_by" (errors spec/credential-spec {:id "K" :name "K" :provided_by ["tenant" "tenant"]})))
  (is
   (mentions? "provided_by" (errors spec/credential-spec {:id "K" :name "K" :provided_by ["platform"]})))
  (is
   (mentions? "provided_by" (errors spec/credential-spec {:id "K" :name "K" :provided_by []})))
  (is
   (= [true false true]
      (mapv #(spec/provides? {:provided-by [:team :user]} %) [:team :tenant :user])))
  (is
   (true? (spec/personal? {:provided-by [:tenant :user]}))))

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
              (:errors (spec/parse-tenant {:teams [{:id "t" :name "T" :repository {:url "ftp://x/y"}}]}))))
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
        (:errors (spec/parse-tenant (assoc base :mcp_servers [server])))))
    (is
     (mentions? "may not be sent"
                (:errors (spec/parse-tenant (assoc base :mcp_servers [(assoc server :url "https://evil.example/mcp")])))))
    (is
     (mentions? "undeclared credential"
                (:errors (spec/parse-tenant {:mcp_servers [server]}))))))

(deftest platform-rules
  (testing "ids are unique"
    (is
     (mentions? "duplicate llm connection ids"
                (:errors (spec/parse-tenant {:llm_connections [{:id "a" :secret "K" :models ["m"]}
                                                               {:id "a" :secret "K" :models ["m"]}]})))))
  (testing "LLM secrets cannot be personal"
    (is
     (mentions? "must be provided_by [\"tenant\"]"
                (:errors (spec/parse-tenant {:llm_connections [{:id "a" :secret "K" :models ["m"]}]
                                             :credentials [{:id "K" :name "K" :provided_by ["tenant" "team"]}]})))))
  (testing "team ids can't name a page of the UI"
    (doseq [reserved ["runs" "sessions" "teams" "projects" "harnesses" "activities" "accounts" "admin"
                      "webhooks" "platform" "login" "setup" "api"]]
      (is
       (mentions? (str "team id " reserved " names a page of the UI (/<tenant>/" reserved ")")
                  (:errors (spec/parse-tenant {:teams [{:id reserved :name "T"
                                                        :repository {:url "https://g.example/x.git"}}]})))))
    (is
     (= []
        (:errors (spec/parse-tenant {:teams [{:id "runners" :name "T" :repository {:url "https://g.example/x.git"}}]})))))
  (testing "a tenant.json holds no member lists"
    (is
     (mentions? "admins: disallowed key" (:errors (spec/parse-tenant {:admins ["alice"]}))))
    (is
     (mentions? "teams.0.members: disallowed key"
                (:errors (spec/parse-tenant {:teams [{:id "t" :name "T" :repository {:url "https://g.example/x.git"}
                                                      :members ["bob"]}]})))))
  (testing "a credential test is a url or an MCP tool"
    (is
     (mentions? "set exactly one of url or mcp_tool"
                (:errors (spec/parse-tenant {:credentials [{:id "K" :name "K" :test {}}]})))))
  (testing "the $schema key is ignored"
    (is
     (= []
        (:errors (spec/parse-tenant {:$schema "x"}))))))

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
   (thrown-with-msg? clojure.lang.ExceptionInfo #"MANAGER_HTTP_GIT_HOSTS"
                     (git/check-url "http://example.com/x.git" ["/repos"])))
  (testing "plain http only to the hosts the operator allows"
    (is
     (nil?
      (git/check-url "http://gerrit:8080/a/demo" ["/repos"] ["gerrit"])))
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"only https://"
                       (git/check-url "ftp://gerrit/demo" ["/repos"] ["gerrit"]))))
  (is
   (nil?
    (git/check-url "file:///repos/x" ["/repos"]))))
