(ns manager-svc.credentials.fixtures
  (:require [edd.security :as security]
            [manager-svc.credentials.core :as core]
            [manager-svc.platform.api :as platform-api]
            [manager-svc.platform.spec :as spec]
            [manager-svc.test-support :as support]))

(def settings
  (assoc support/settings
         :credentials-key "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
         :secrets-dir "target/test-data/no-secrets"))

(def raw-platform-spec
  {:credentials [{:id "github"
                  :name "GitHub"
                  :kind "token"
                  :provided-by "platform_or_user"
                  :hosts ["api.github.com"]
                  :test {:url "https://api.github.com/user"}}
                 {:id "jira"
                  :name "Jira"
                  :kind :basic
                  :provided-by :user
                  :teams ["alpha"]}
                 {:id "sonar"
                  :name "Sonar"
                  :provided-by :user
                  :teams ["beta"]}
                 {:id "llm-key"
                  :name "LLM key"}]
   :llm-connections [{:id "main" :secret "llm-key"}]
   :mcp-servers [{:id "jira"
                  :name "Jira MCP"
                  :url "http://127.0.0.1:1/mcp"
                  :credential "jira"
                  :auth {:header "Authorization" :scheme "basic"}
                  :tools ["search" "get_issue"]}
                 {:id "github"
                  :name "GitHub MCP"
                  :url "http://127.0.0.1:1/mcp"
                  :credential "github"}]
   :teams [{:id "alpha" :secrets ["deploy-token"]}]})

(def platform-spec
  "The fixture as the platform hands it out: decoded with every default."
  (:value (spec/decode spec/platform-spec raw-platform-spec)))

(defn ctx
  []
  (security/register (core/register (support/ctx {:settings settings}))))

(defn with-platform*
  [{:keys [admins teams] :or {admins #{"root"} teams {"alice" #{"alpha"}}}} f]
  (with-redefs [platform-api/platform-spec (constantly platform-spec)
                platform-api/platform-admin? (fn [_ user] (contains? admins user))
                platform-api/teams-of (fn [_ user] (get teams user #{}))
                platform-api/definitions (fn [_ team-id _kind]
                                           (when (= "alpha" team-id)
                                             [{:id "coder" :spec {:mcp ["jira"]}}]))]
    (f)))

(defmacro with-platform
  [opts & body]
  `(with-platform* ~opts (fn [] ~@body)))
