(ns manager-svc.credentials.fixtures
  (:require [clojure.string :as string]
            [edd.security :as security]
            [edd.test.fixture.dal :as mock]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.core :as core]
            [manager-svc.credentials.edge :as edge]
            [manager-svc.platform.api :as platform-api]
            [manager-svc.platform.spec :as spec]
            [manager-svc.roles :as roles]
            [manager-svc.test-support :as support]))

(def settings
  (assoc support/settings
         :credentials-key "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
         :secrets-dir "target/test-data/no-secrets"))

(def raw-tenant-spec
  {:credentials [{:id "github"
                  :name "GitHub"
                  :kind "token"
                  :provided-by ["tenant" "user"]
                  :hosts ["api.github.com"]
                  :test {:url "https://api.github.com/user"}}
                 {:id "jira"
                  :name "Jira"
                  :kind :basic
                  :provided-by ["user"]
                  :teams ["alpha"]}
                 {:id "sonar"
                  :name "Sonar"
                  :provided-by ["user"]
                  :teams ["beta"]}
                 {:id "bitbucket"
                  :name "Bitbucket"
                  :provided-by ["tenant" "team" "user"]}
                 {:id "nexus"
                  :name "Nexus"
                  :provided-by ["team"]
                  :teams ["alpha"]}
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
   :teams [{:id "alpha" :secrets ["deploy-token"]}
           {:id "beta"}]})

(def tenant-spec
  "The fixture as the platform hands it out: decoded with every default."
  (:value (spec/decode spec/tenant-spec raw-tenant-spec)))

(def other-tenant-spec
  "globex declares the same ids, so a leak between tenants would show."
  tenant-spec)

(def people
  "Who is who: acme has teams alpha and beta, globex its own alpha."
  {"root" {:tenant-admin true}
   "alice" {:teams {"alpha" :member}}
   "bob" {:teams {"alpha" :member}}
   "carol" {:teams {"alpha" :member}}
   "dave" {:teams {"alpha" :admin}}
   "erin" {:teams {"beta" :admin}}
   "gina" {:tenant "globex" :tenant-admin true :teams {"alpha" :admin}}
   "hank" {:tenant "globex" :teams {"alpha" :member}}})

(def tenant-teams
  {"acme" #{"alpha" "beta"}
   "globex" #{"alpha"}})

(defn ctx
  []
  (security/register (core/register (support/ctx {:settings settings}))))

(defn as
  [username]
  (support/as-user (ctx) username (get people username {})))

(defn platform-ctx
  []
  (support/as-platform (ctx)))

(defn identity-of
  "What the edge knows of the caller of ctx."
  [ctx]
  {:sub (auth/user-id ctx) :tenant (roles/tenant-of ctx)})

(defn sealed
  "The attrs of a set command as the edge sends them: sealed for the caller."
  [ctx scope attrs]
  (:attrs (edge/sealed-command settings scope (:credential-id attrs) (identity-of ctx)
                               (select-keys attrs [:secret :username]) (:team-id attrs))))

(def ^:private value-scopes
  {:set-my-credential :user
   :set-team-credential :team
   :set-tenant-credential :tenant})

(defn execute
  "Runs a command as the edge would send it; {:success true} or {:error :status}."
  [ctx cmd-id attrs]
  (try
    (let [sent
          (if-let [scope
                   (value-scopes cmd-id)]
            (sealed ctx scope attrs)
            attrs)

          response
          (support/apply! ctx {:cmd-id cmd-id :id (random-uuid) :attrs sent})]
      (if (:error response)
        {:error (:error response)}
        {:success true}))
    (catch clojure.lang.ExceptionInfo e
      {:error (ex-message e) :status (:status (ex-data e))})))

(defn query-edd
  [_system credential-id owner]
  (mock/query (platform-ctx) {:query-id :get-stored-credential
                              :credential-id credential-id
                              :owner owner}))

(def ^:dynamic *required-credentials*
  "{team-id [credential-id]}: what each acme team's team.json requires."
  {})

(defn- team-entry
  [tenant team-id]
  {:id team-id
   :tenant tenant
   :name (string/capitalize team-id)
   :required-credentials (if (= "acme" tenant) (get *required-credentials* team-id []) [])})

(defn- spec-of
  [tenant]
  (case tenant
    "acme" tenant-spec
    "globex" other-tenant-spec
    spec/empty-tenant))

(defn with-platform*
  [f]
  (with-redefs [platform-api/tenant-spec (fn [_ tenant] (spec-of tenant))
                platform-api/tenants (fn [_] [{:name "acme" :source {:type :git :url "https://git.example.com/acme"}}
                                              {:name "globex" :source {:type :git :url "https://git.example.com/globex"}}])
                platform-api/teams-of (fn [_ caller]
                                        (let [teams (get tenant-teams (:tenant caller) #{})]
                                          (if (:tenant-admin caller)
                                            teams
                                            (set (filter (roles/claimed-team-ids caller (:tenant caller)) teams)))))
                platform-api/team (fn [_ tenant team-id]
                                    (when (contains? (get tenant-teams tenant) team-id)
                                      (team-entry tenant team-id)))
                platform-api/teams (fn [_ tenant]
                                     (mapv #(team-entry tenant %) (sort (get tenant-teams tenant))))
                platform-api/definitions (fn [_ tenant team-id _kind]
                                           (when (and (= "acme" tenant) (= "alpha" team-id))
                                             [{:id "coder" :spec {:mcp ["jira"]}}]))]
    (f)))

(defmacro with-platform
  [& body]
  `(with-platform* (fn [] ~@body)))
