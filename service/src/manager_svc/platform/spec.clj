(ns manager-svc.platform.spec
  "Schemas of the onboarding repository (platform.json) and of team
   definitions (.platform/{agents,harnesses,workflows}/<id>.json). Files use
   snake_case keys and string enums; decoded values use kebab-case keys and
   keyword enums. Keys of free-form maps (pricing, permissions, secret_hosts)
   are kept as written."
  (:require [clojure.string :as string]
            [jsonista.core :as json]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [manager-svc.git :as git])
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
  [:re {:error/message "should be lowercase letters, digits and dashes"} #"^[a-z0-9][a-z0-9-]{0,62}$"])

(def secret-name
  [:re {:error/message "should be an uppercase secret name (A-Z, 0-9, _)"} #"^[A-Z][A-Z0-9_]{0,63}$"])

(def username
  [:re {:error/message "should be a lowercase username"} #"^[a-z0-9][a-z0-9._-]{0,63}$"])

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

(defn git-url?
  [url]
  (boolean
   (and (string? url)
        (or (some #(string/starts-with? url %) ["https://" "file://" "ssh://"])
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

(def credential-spec
  (closed
   [:id secret-name]
   [:name [:string {:min 1 :max 80}]]
   (defaulted :description "" string?)
   (defaulted :kind :token (enum :token :basic :ssh-key))
   (defaulted :hosts [] [:vector string?])
   (defaulted :provided-by :platform (enum :platform :platform-or-user :user))
   (defaulted :teams "*" team-scope)
   (defaulted :help "" string?)
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
   (optional :max-running-sessions [:int {:min 1 :max 1000}])))

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
   (defaulted :members [] [:vector username])
   (defaulted :admins [] [:vector username])
   (defaulted :secrets [] [:vector secret-name])
   (defaulted :budget {} team-budget)
   (defaulted :notifications {} team-notifications)))

(def secret-key
  [:and :keyword [:fn {:error/message "should be an uppercase secret name"}
                  #(re-matches #"^[A-Z][A-Z0-9_]{0,63}$" (name %))]])

(def platform-spec
  (closed
   (defaulted :version 1 [:= 1])
   (defaulted :admins [] [:vector username])
   (defaulted :allowed-images [default-image] [:vector string?])
   (defaulted :llm-connections [] [:vector llm-connection])
   (defaulted :secret-hosts {} [:map-of secret-key [:vector string?]])
   (defaulted :credentials [] [:vector credential-spec])
   (defaulted :mcp-servers [] [:vector mcp-server])
   (defaulted :teams [] [:vector team-entry])))

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
                   [:provider (enum :bitbucket :github)]
                   [:repository [:re #"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"]]
                   [:credentials secret-name]
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
   (defaulted :trigger {:type :manual} trigger)
   (defaulted :options {} free-map)
   (defaulted :visibility :team (enum :team :public))))

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
  (some #(when (= credential-id (:id %)) %) (:credentials platform)))

(defn mcp-server-of
  [platform server-id]
  (some #(when (= server-id (:id %)) %) (:mcp-servers platform)))

(defn llm-connection-of
  [platform connection-id]
  (some #(when (= connection-id (:id %)) %) (:llm-connections platform)))

(defn credential-hosts
  [platform credential-id]
  (vec (distinct (concat (:hosts (credential platform credential-id))
                         (get-in platform [:secret-hosts (keyword credential-id)])))))

(defn personal?
  [credential]
  (not= :platform (:provided-by credential)))

(defn- url-host
  [url]
  (try
    (.getHost (URI. url))
    (catch Exception _
      nil)))

(defn- platform-errors
  [platform]
  (let [duplicate-errors
        (for [[label ids] [["team" (map :id (:teams platform))]
                           ["llm connection" (map :id (:llm-connections platform))]
                           ["credential" (map :id (:credentials platform))]
                           ["MCP server" (map :id (:mcp-servers platform))]]
              :let [repeated (duplicates ids)]
              :when (seq repeated)]
          (str "duplicate " label " ids: " (vec repeated)))

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
              :when (and (personal? declared) (contains? llm-secrets (:id declared)))]
          (str "credential " (:id declared) " is used by an LLM connection and must be provided_by platform"))

        test-errors
        (for [[index declared] (map-indexed vector (:credentials platform))
              :let [test (:test declared)]
              :when (and test (= (boolean (:url test)) (boolean (:mcp-tool test))))]
          (str "credentials." index ".test: set exactly one of url or mcp_tool"))]
    (vec (concat test-errors duplicate-errors server-errors personal-errors))))

(defn- prompt-errors
  [agent]
  (when (= (boolean (not-empty (:prompt agent))) (boolean (not-empty (:prompt-file agent))))
    ["file: set exactly one of prompt or prompt_file"]))

(defn- model-errors
  [schema value]
  (cond
    (= schema platform-spec) (platform-errors value)
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

(defn parse-platform
  "platform.json text or data → {:value spec :errors [...]}."
  [raw]
  (validate platform-spec (if (string? raw) (read-json raw) raw)))

(def empty-platform
  (:value (parse-platform {})))
