(ns manager-svc.platform.spec
  "Schemas of a tenant's onboarding repository (tenant.json), of a team's
   configuration (.platform/team.json) and of team definitions
   (.platform/{agents,harnesses,workflows}/<id>.json). Files use
   snake_case keys and string enums; decoded values use kebab-case keys and
   keyword enums. Keys of free-form maps (pricing, permissions, secret_hosts)
   are kept as written."
  (:require [clojure.string :as string]
            [jsonista.core :as json]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [manager-svc.git :as git]
            [manager-svc.names :as names]
            [manager-svc.rows :as rows])
  (:import [java.net URI]
           [java.util.regex Pattern PatternSyntaxException]))

(def default-image "ghcr.io/anomalyco/opencode:1.19.5")

(defn kebab
  [value]
  (keyword (string/replace (name value) "_" "-")))

(defn snake
  [value]
  (string/replace (name value) "-" "_"))

(defn- enum-value
  [value]
  (if (or (string? value) (keyword? value))
    (kebab value)
    value))

(defn enum
  [& values]
  (into [:enum {:decode/platform enum-value}] values))

(def identifier
  [:re {:error/message "should be lowercase letters, digits and dashes"} names/identifier-pattern])

(def tenant-page-names
  "Path segments naming a tenant's pages in the UI (/acme/runs): a team
   at /acme/<team> can't take one of them."
  #{"runs" "sessions" "teams" "projects" "harnesses" "activities" "accounts" "admin" "webhooks"})

(def reserved-tenant-names
  "Top-level paths of the UI (/login, /platform), of its earlier versions
   (/runs, /tenants) and of the edge (/hooks, /healthz, /metrics): a tenant
   at /<tenant> can't take one of them."
  #{"login" "setup" "platform" "api" "runs" "sessions" "teams" "harnesses" "activities" "accounts" "admin"
    "tenants" "hooks" "healthz" "metrics"})

(def reserved-team-ids
  (into tenant-page-names #{"login" "setup" "platform" "api"}))

(def secret-name
  [:re {:error/message "should be an uppercase secret name (A-Z, 0-9, _)"} names/secret-name-pattern])

(def username
  [:re {:error/message "should be a lowercase username"} names/username-pattern])

(def team-scope
  [:or [:= "*"] [:vector identifier]])

(defn- optional
  [k schema]
  [k {:optional true} [:maybe schema]])

(defn- defaulted
  [k default schema]
  [k {:default default} schema])

(defn- closed
  [& entries]
  (into [:map {:closed true}] entries))

(def scm-providers
  "The SCM providers pull requests (Gerrit: changes) come from. A hosted
   provider has a fixed API host and clone URL; a self-hosted one is reached
   at the source's url."
  {:bitbucket {:api-host "api.bitbucket.org" :clone-url "https://bitbucket.org/%s.git"}
   :github {:api-host "api.github.com" :clone-url "https://github.com/%s.git"}
   :gerrit {:self-hosted true}})

(def scm-provider
  (apply enum (keys scm-providers)))

(def repository-name
  [:re {:error/message "should be <workspace>/<repository> (Gerrit: the project name)"}
   #"^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$"])

(def http-url
  [:re {:error/message "should be an http(s):// URL"} #"^https?://[^\s/]+(/\S*)?$"])

(def branch-name
  [:re #"^[A-Za-z0-9._/-]{1,200}$"])

(defn self-hosted?
  [provider]
  (boolean (get-in scm-providers [(kebab provider) :self-hosted])))

(defn- scm-location-error
  [{:keys [provider repository url]}]
  (cond
    (and (self-hosted? provider) (nil? url))
    (str (name provider) " is self-hosted: set url")

    (and provider (not (self-hosted? provider)) url)
    (str (name provider) " is hosted: leave out url")

    (and provider (not (self-hosted? provider)) repository (not (string/includes? repository "/")))
    "repository should be <workspace>/<repository>"))

(defn scm-source
  "Where pull requests live: {:provider :repository :url? :web-url?
   :credentials}. :credentials-optional leaves the credential out of the
   required keys (team.json projects)."
  [& flags]
  (let [credentials
        (if (some #{:credentials-optional} flags)
          (optional :credentials secret-name)
          [:credentials secret-name])]
    [:and
     (closed
      [:provider scm-provider]
      [:repository repository-name]
      (optional :url http-url)
      (optional :web-url http-url)
      credentials)
     [:fn {:error/fn (fn [{:keys [value]} _] (scm-location-error value))}
      #(nil? (scm-location-error %))]]))

(defn scm-api-host
  "The host an SCM source's API calls go to."
  [{:keys [provider url]}]
  (if (self-hosted? provider)
    (some-> url URI. .getHost)
    (get-in scm-providers [(kebab provider) :api-host])))

(defn scm-clone-url
  "The git URL of an SCM source's repository; Gerrit's authenticated URL
   (/a/) when the source has a credential."
  [{:keys [provider repository url credentials]}]
  (if (self-hosted? provider)
    (str (string/replace url #"/+$" "") (when credentials "/a") "/" repository)
    (format (get-in scm-providers [(kebab provider) :clone-url]) repository)))

(defn git-url?
  "https, ssh and file URLs; http only for the hosts the operator allows
   (MANAGER_HTTP_GIT_HOSTS), which manager-svc.git/check-url enforces."
  [url]
  (boolean
   (and (string? url)
        (or (some #(string/starts-with? url %) ["https://" "http://" "file://" "ssh://"])
            (re-matches git/scp-like url)))))

(def git-source
  (closed
   (defaulted :type :git (enum :git))
   [:url [:fn {:error/message "use an https://, ssh (git@host:org/repo.git) or file:// repository URL"} git-url?]]
   (defaulted :branch "main" string?)
   (optional :credentials secret-name)
   (defaulted :username "x-token-auth" string?)
   (defaulted :path ".platform" string?)))

(def archive-source
  (closed
   [:type (enum :url)]
   [:url [:re {:error/message "should be an https:// URL"} #"^https://"]]
   (optional :credentials secret-name)
   (defaulted :path ".platform" string?)))

(def path-source
  (closed
   [:type (enum :path)]
   [:location string?]
   (defaulted :path ".platform" string?)))

(defn- dispatch-type
  [default]
  (fn [value]
    (when (map? value)
      (enum-value (or (:type value) default)))))

(def team-source
  [:multi {:dispatch (dispatch-type "git")}
   [:git git-source]
   [:url archive-source]
   [:path path-source]])

(def non-negative
  [:and number? [:>= 0]])

(def model-price
  (closed
   [:input non-negative]
   [:output non-negative]
   (optional :cached-input non-negative)))

(def llm-connection
  (closed
   [:id identifier]
   (defaulted :type :openai-compatible (enum :openai-compatible :github-copilot))
   (optional :base-url string?)
   [:secret secret-name]
   [:models [:vector {:min 1} string?]]
   (defaulted :teams "*" team-scope)
   (defaulted :pricing {} [:map-of :keyword model-price])))

(def credential-test
  (closed
   (optional :url [:re #"^https?://"])
   (optional :mcp-tool string?)))

(def scopes
  "Who may provide a credential's value, in the order values are resolved
   (most specific first)."
  [:user :team :tenant])

(def provided-by
  [:and
   [:vector {:min 1} (enum :tenant :team :user)]
   [:fn {:error/message "should not repeat a scope"} #(apply distinct? %)]])

(def credential-spec
  (closed
   [:id secret-name]
   [:name [:string {:min 1 :max 80}]]
   (defaulted :description "" string?)
   (defaulted :kind :token (enum :token :basic :ssh-key))
   (defaulted :hosts [] [:vector string?])
   (defaulted :provided-by [:tenant] provided-by)
   (defaulted :teams "*" team-scope)
   (defaulted :help "" string?)
   (defaulted :required false :boolean)
   (optional :test credential-test)))

(def mcp-auth
  (closed
   (defaulted :header "Authorization" [:re #"^[A-Za-z0-9-]{1,64}$"])
   (defaulted :scheme :bearer (enum :bearer :basic :raw))))

(def mcp-server
  (closed
   [:id identifier]
   [:name [:string {:min 1 :max 80}]]
   (defaulted :description "" string?)
   [:url [:re {:error/message "should be an http(s):// URL"} #"^https?://"]]
   (optional :credential secret-name)
   (defaulted :auth {} mcp-auth)
   (optional :tools [:vector string?])
   (defaulted :teams "*" team-scope)))

(def team-budget
  (closed
   (optional :daily-tokens [:int {:min 1000}])
   (optional :max-running-sessions [:int {:min 1 :max 1000}])
   (optional :idle-minutes [:int {:min 1 :max 1440}])))

(def team-notifications
  (closed
   (optional :webhook secret-name)
   (defaulted :on [:completed :failed] [:vector (enum :completed :failed)])))

(def team-entry
  (closed
   [:id identifier]
   [:name string?]
   (defaulted :description "" string?)
   [:repository team-source]
   (defaulted :secrets [] [:vector secret-name])
   (defaulted :notifications {} team-notifications)))

(def secret-key
  [:and :keyword [:fn {:error/message "should be an uppercase secret name"}
                  #(re-matches names/secret-name-pattern (name %))]])

(def tenant-spec
  (closed
   (defaulted :version 1 [:= 1])
   (defaulted :allowed-images [default-image] [:vector string?])
   (defaulted :llm-connections [] [:vector llm-connection])
   (defaulted :secret-hosts {} [:map-of secret-key [:vector string?]])
   (defaulted :credentials [] [:vector credential-spec])
   (defaulted :mcp-servers [] [:vector mcp-server])
   (defaulted :teams [] [:vector team-entry])))

(def team-llm-connection
  (closed
   [:id identifier]
   (optional :models [:vector {:min 1} string?])))

(def ci-project
  (closed
   [:mcp-server identifier]
   (defaulted :jobs [] [:vector [:string {:min 1}]])
   (optional :job [:string {:min 1}])))

(def project
  (closed
   [:id identifier]
   (optional :name string?)
   (optional :branch branch-name)
   [:scm (scm-source :credentials-optional)]
   (optional :ci ci-project)))

(def team-config
  "A team's own configuration (.platform/team.json). Leaving out
   llm_connections or mcp_servers means every one the tenant grants the team."
  (closed
   (defaulted :version 1 [:= 1])
   (defaulted :budget {} team-budget)
   (optional :llm-connections [:vector team-llm-connection])
   (optional :mcp-servers [:vector identifier])
   (defaulted :projects [] [:vector project])
   (defaulted :required-credentials [] [:vector secret-name])))

(def agent-spec
  (closed
   (defaulted :description "" string?)
   (optional :prompt string?)
   (optional :prompt-file string?)
   (optional :temperature [:and number? [:>= 0] [:<= 2]])
   (optional :steps [:int {:min 1 :max 500}])))

(defn- valid-regex?
  [pattern]
  (try
    (Pattern/compile pattern)
    true
    (catch PatternSyntaxException _
      false)))

(def regex-list
  [:vector [:and string? [:fn {:error/fn (fn [{:keys [value]} _] (str "invalid regular expression " (pr-str value)))}
                          valid-regex?]]])

(def tool-name
  [:and :keyword [:fn {:error/fn (fn [{:keys [value]} _] (str "invalid tool name " (pr-str (name value))))}
                  #(re-matches #"^[a-z_*]{1,40}$" (name %))]])

(def harness-spec
  (closed
   (defaulted :description "" string?)
   (optional :image string?)
   [:llm (closed
          [:connection identifier]
          [:models [:vector {:min 1} string?]])]
   (defaulted :network :none (enum :none :internet))
   (defaulted :permissions {} [:map-of tool-name (enum :allow :ask :deny)])
   (defaulted :resources {} (closed
                             (defaulted :cpus 1.0 [:and number? [:> 0] [:<= 8]])
                             (defaulted :memory-mb 2048 [:int {:min 256 :max 32768}])
                             (defaulted :pids 512 [:int {:min 64 :max 4096}])))
   (defaulted :idle-minutes 15 [:int {:min 1 :max 1440}])
   (defaulted :policy {} (closed
                          (defaulted :deny-commands [] regex-list)
                          (defaulted :deny-paths [] regex-list)))
   (defaulted :mcp [] [:vector identifier])
   (defaulted :skills [] [:vector string?])))

(def free-map
  [:map-of :keyword any?])

(def trigger
  [:multi {:dispatch (dispatch-type "manual")}
   [:manual (closed (defaulted :type :manual (enum :manual)))]
   [:schedule (closed
               [:type (enum :schedule)]
               [:every-minutes [:int {:min 5 :max (* 60 24 7)}]]
               (defaulted :inputs {} free-map))]
   [:pull-request (closed
                   [:type (enum :pull-request)]
                   (optional :provider scm-provider)
                   (optional :repository repository-name)
                   (optional :url http-url)
                   (optional :web-url http-url)
                   (optional :credentials secret-name)
                   (defaulted :target-branches [] [:vector string?])
                   (defaulted :poll-minutes 15 [:int {:min 1 :max 1440}])
                   (defaulted :backfill false boolean?))]])

(def workflow-spec
  (closed
   [:name string?]
   (defaulted :description "" string?)
   [:activity identifier]
   [:agent identifier]
   [:harness identifier]
   (optional :project identifier)
   (defaulted :trigger {:type :manual} trigger)
   (defaulted :options {} free-map)
   (defaulted :visibility :team (enum :team :tenant))))

(def definition-kinds
  {:agents agent-spec
   :harnesses harness-spec
   :workflows workflow-spec})

(def ^:private transformer
  (mt/transformer
   (mt/key-transformer {:decode kebab})
   {:name :platform}
   mt/default-value-transformer
   mt/json-transformer))

(defn- path-label
  [in]
  (if (empty? in)
    "file"
    (string/join "." (map #(if (keyword? %) (snake %) (str %)) in))))

(defn- explain
  [schema value]
  (when-let [explanation
             (m/explain schema value)]
    (mapv (fn [error]
            (str (path-label (:in error)) ": " (me/error-message error)))
          (:errors (me/with-spell-checking explanation)))))

(defn decode
  "Decodes raw (keyword keys as read from JSON) against schema:
   {:value decoded :errors [\"path: message\"]}. Decoding runs twice so that
   defaulted nested objects get their own defaults."
  [schema raw]
  (let [once
        (m/decode schema raw transformer)

        value
        (m/decode schema once transformer)

        errors
        (explain schema value)]
    {:value value
     :errors (vec errors)}))

(defn read-json
  [text]
  (json/read-value text json/keyword-keys-object-mapper))

(defn without-schema-key
  [raw]
  (if (map? raw)
    (dissoc raw :$schema)
    raw))

(defn- duplicates
  [ids]
  (sort (keep (fn [[id n]] (when (> n 1) id)) (frequencies ids))))

(defn in-scope?
  "True when a declaration's teams (\"*\" or ids) include team-id."
  [scope team-id]
  (or (= "*" scope) (boolean (some #{team-id} scope))))

(defn credential
  [platform credential-id]
  (rows/by-id (:credentials platform) credential-id))

(defn mcp-server-of
  [platform server-id]
  (rows/by-id (:mcp-servers platform) server-id))

(defn llm-connection-of
  [platform connection-id]
  (rows/by-id (:llm-connections platform) connection-id))

(defn credential-hosts
  [platform credential-id]
  (vec (distinct (concat (:hosts (credential platform credential-id))
                         (get-in platform [:secret-hosts (keyword credential-id)])))))

(defn provides?
  "True when scope (:tenant, :team or :user) may provide credential's value."
  [credential scope]
  (boolean (some #{scope} (:provided-by credential))))

(defn personal?
  "True for a credential people may provide themselves."
  [credential]
  (provides? credential :user))

(defn- url-host
  [url]
  (try
    (.getHost (URI. url))
    (catch Exception _
      nil)))

(defn- tenant-errors
  [platform]
  (let [duplicate-errors
        (for [[label ids] [["team" (map :id (:teams platform))]
                           ["llm connection" (map :id (:llm-connections platform))]
                           ["credential" (map :id (:credentials platform))]
                           ["MCP server" (map :id (:mcp-servers platform))]]
              :let [repeated (duplicates ids)]
              :when (seq repeated)]
          (str "duplicate " label " ids: " (vec repeated)))

        reserved-errors
        (for [team-id (map :id (:teams platform))
              :when (contains? reserved-team-ids team-id)]
          (str "team id " team-id " names a page of the UI (/<tenant>/" team-id "); choose another id. Reserved: "
               (string/join ", " (sort reserved-team-ids))))

        server-errors
        (for [server (:mcp-servers platform)
              :when (:credential server)
              :let [declared (credential platform (:credential server))
                    host (url-host (:url server))]
              :when (or (nil? declared) (not (some #{host} (:hosts declared))))]
          (if (nil? declared)
            (str "MCP server " (:id server) " uses undeclared credential " (:credential server))
            (str "MCP server " (:id server) ": credential " (:id declared) " may not be sent to " host
                 " (add it to its hosts)")))

        llm-secrets
        (set (map :secret (:llm-connections platform)))

        personal-errors
        (for [declared (:credentials platform)
              :when (and (not= [:tenant] (:provided-by declared)) (contains? llm-secrets (:id declared)))]
          (str "credential " (:id declared) " is used by an LLM connection and must be provided_by [\"tenant\"]"))

        required-errors
        (for [declared (:credentials platform)
              :when (and (:required declared) (not (personal? declared)))]
          (str "credential " (:id declared) " is required of people but they can't provide it: add \"user\" to its provided_by"))

        test-errors
        (for [[index declared] (map-indexed vector (:credentials platform))
              :let [test (:test declared)]
              :when (and test (= (boolean (:url test)) (boolean (:mcp-tool test))))]
          (str "credentials." index ".test: set exactly one of url or mcp_tool"))]
    (vec (concat test-errors duplicate-errors reserved-errors server-errors personal-errors required-errors))))

(defn- prompt-errors
  [agent]
  (when (= (boolean (not-empty (:prompt agent))) (boolean (not-empty (:prompt-file agent))))
    ["file: set exactly one of prompt or prompt_file"]))

(defn- model-errors
  [schema value]
  (cond
    (= schema tenant-spec) (tenant-errors value)
    (= schema agent-spec) (prompt-errors value)
    :else []))

(defn validate
  "Decodes and validates raw data against schema, including the cross-field
   rules: {:value v :errors [...]}; :value is nil when there are errors."
  [schema raw]
  (let [{:keys [value errors]}
        (decode schema (without-schema-key raw))

        all-errors
        (if (seq errors)
          errors
          (vec (model-errors schema value)))]
    {:value (when (empty? all-errors) value)
     :errors all-errors}))

(defn parse-tenant
  "tenant.json text or data → {:value spec :errors [...]}."
  [raw]
  (validate tenant-spec (if (string? raw) (read-json raw) raw)))

(def empty-tenant
  (:value (parse-tenant {})))

(defn- connection-errors
  [tenant team-id {:keys [id models]}]
  (let [connection
        (llm-connection-of tenant id)]
    (cond
      (nil? connection)
      [(str "llm_connections: connection " id " is not configured for the tenant")]

      (not (in-scope? (:teams connection) team-id))
      [(str "llm_connections: the tenant does not grant connection " id " to team " team-id)]

      :else
      (for [model models
            :when (not (some #{model} (:models connection)))]
        (str "llm_connections: connection " id " does not offer model " model)))))

(defn- server-errors
  [tenant team-id label server-id]
  (let [server
        (mcp-server-of tenant server-id)]
    (cond
      (nil? server)
      [(str label ": MCP server " server-id " is not configured for the tenant")]

      (not (in-scope? (:teams server) team-id))
      [(str label ": the tenant does not grant MCP server " server-id " to team " team-id)])))

(defn- required-credential-errors
  [tenant team-id credential-id]
  (let [declared
        (credential tenant credential-id)]
    (cond
      (nil? declared)
      [(str "required_credentials: credential " credential-id " is not declared by the tenant")]

      (not (personal? declared))
      [(str "required_credentials: people can't provide credential " credential-id
            " (its provided_by has no \"user\"), so it can't be required of them")]

      (not (in-scope? (:teams declared) team-id))
      [(str "required_credentials: the tenant does not grant credential " credential-id " to team " team-id)])))

(defn- team-config-errors
  [tenant team-id config]
  (vec (concat
        (mapcat #(connection-errors tenant team-id %) (:llm-connections config))
        (mapcat #(required-credential-errors tenant team-id %) (:required-credentials config))
        (mapcat #(server-errors tenant team-id "mcp_servers" %) (:mcp-servers config))
        (for [{:keys [id ci]} (:projects config)
              :when ci
              error (server-errors tenant team-id (str "projects." id ".ci") (:mcp-server ci))]
          error)
        (for [[label ids] [["llm connection" (map :id (:llm-connections config))]
                           ["MCP server" (:mcp-servers config)]
                           ["project" (map :id (:projects config))]
                           ["required credential" (:required-credentials config)]]
              :let [repeated (duplicates ids)]
              :when (seq repeated)]
          (str "duplicate " label " ids: " (vec repeated))))))

(defn parse-team-config
  "team.json text or data (nil when the team has none) → {:value config
   :errors [...]}, checked against what the tenant declares and grants
   team-id."
  [tenant team-id raw]
  (let [{:keys [value errors]}
        (validate team-config (cond
                                (nil? raw) {}
                                (string? raw) (read-json raw)
                                :else raw))

        cross-errors
        (when value (team-config-errors tenant team-id value))]
    (if (seq cross-errors)
      {:value nil :errors cross-errors}
      {:value value :errors errors})))

(defn team-llm-connections
  "[{:id :models}] the team may use: what its team.json lists (models
   default to all the connection offers), else every connection the tenant
   grants it."
  [tenant team-id config]
  (let [granted
        (filter #(in-scope? (:teams %) team-id) (:llm-connections tenant))]
    (if-let [chosen
             (:llm-connections config)]
      (vec (for [{:keys [id models]} chosen
                 :let [connection (rows/by-id granted id)]
                 :when connection]
             {:id id :models (vec (or models (:models connection)))}))
      (mapv #(select-keys % [:id :models]) granted))))

(defn team-mcp-servers
  "Ids of the MCP servers the team uses: its team.json's, else every one
   the tenant grants it."
  [tenant team-id config]
  (let [granted
        (set (map :id (filter #(in-scope? (:teams %) team-id) (:mcp-servers tenant))))]
    (if-let [chosen
             (:mcp-servers config)]
      (filterv granted chosen)
      (vec (sort granted)))))
