(ns manager-svc.runs.sandbox
  "The OpenCode configuration and sandbox a workflow's session runs with.
   Sandboxes reach the platform on its sandbox listener
   (:sandbox-service-url) and authenticate with keys derived per session and
   purpose (manager-svc.auth/derived-key)."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [manager-svc.auth :as auth]
            [manager-svc.http :as http]
            [manager-svc.platform.api :as platform]
            [manager-svc.sessions.api :as sessions]))

(def provider-id "platform")

(def opencode-gitignore
  "node_modules\npackage.json\npackage-lock.json\nbun.lock\n.gitignore\n")

(def lockdown-env
  {"XDG_CONFIG_HOME" sessions/config-mount
   "OPENCODE_DISABLE_PROJECT_CONFIG" "1"
   "OPENCODE_DISABLE_DEFAULT_PLUGINS" "1"
   "OPENCODE_DISABLE_MODELS_FETCH" "1"
   "OPENCODE_DISABLE_AUTOUPDATE" "1"
   "OPENCODE_DISABLE_CLAUDE_CODE" "1"
   "OPENCODE_DISABLE_EXTERNAL_SKILLS" "1"
   "OPENCODE_DISABLE_LSP_DOWNLOAD" "1"})

(def base-permissions
  {"*" "allow" "external_directory" "allow" "webfetch" "deny" "websearch" "deny"})

(def monitor-plugin-template
  (delay (slurp (io/resource "manager_svc/runs/monitor_plugin.js"))))

(defn session-key
  [settings purpose session-id]
  (auth/derived-key settings purpose session-id))

(defn- tool-name
  [k]
  (string/replace (name k) "-" "_"))

(defn opencode-config
  [settings {:keys [session-id agent-id agent prompt harness interactive result-tool]}]
  (let [models
        (get-in harness [:llm :models])

        model
        (str provider-id "/" (first models))

        service-url
        (:sandbox-service-url settings)

        harness-permissions
        (into {} (map (fn [[k v]] [(tool-name k) (name v)])) (:permissions harness))

        with-network
        (if (= "internet" (:network harness))
          (merge {"webfetch" "allow" "websearch" "allow"} base-permissions harness-permissions)
          (merge base-permissions harness-permissions))

        permissions
        (cond-> with-network
          (not interactive) (assoc "question" "deny"))

        agent-config
        (cond-> {:description (or (:description agent) "")
                 :prompt prompt
                 :model model
                 :mode "primary"}
          (some? (:temperature agent)) (assoc :temperature (:temperature agent))
          (some? (:steps agent)) (assoc :steps (:steps agent)))

        platform-mcp
        (when result-tool
          {"platform" {:type "remote"
                       :url (str service-url "/mcp/")
                       :headers {"X-Platform-Key" (session-key settings "mcp" session-id)}
                       :oauth false
                       :enabled true}})

        gateway-mcp
        (into {}
              (for [server-id (:mcp harness)]
                [(name server-id) {:type "remote"
                                   :url (str service-url "/mcp-gw/" (name server-id))
                                   :headers {"X-Platform-Key" (session-key settings "tools" session-id)}
                                   :oauth false
                                   :enabled true}]))]
    {"$schema" "https://opencode.ai/config.json"
     :model model
     :small_model model
     :default_agent agent-id
     :enabled_providers [provider-id]
     :share "disabled"
     :autoupdate false
     :plugin []
     :permission permissions
     :agent {agent-id agent-config}
     :provider {provider-id {:npm "@ai-sdk/openai-compatible"
                             :name "Platform LLM gateway"
                             :options {:baseURL (str service-url "/llm/v1")
                                       :apiKey (session-key settings "llm" session-id)}
                             :models (into {} (for [m models] [m {:name m :tool_call true}]))}}
     :mcp (merge platform-mcp gateway-mcp)}))

(defn monitor-plugin
  [settings session-id harness]
  (let [policy
        {:deny_commands (vec (get-in harness [:policy :deny-commands]))
         :deny_paths (vec (get-in harness [:policy :deny-paths]))}]
    (-> @monitor-plugin-template
        (string/replace "__ENDPOINT__" (http/write-json (str (:sandbox-service-url settings) "/api/telemetry")))
        (string/replace "__KEY__" (http/write-json (session-key settings "telemetry" session-id)))
        (string/replace "__POLICY__" (http/write-json policy)))))

(defn idle-minutes
  "The harness's idle timeout, capped by the team's (team.json budget)."
  [harness team]
  (let [limit
        (get-in team [:budget :idle-minutes])]
    (if (and limit (:idle-minutes harness))
      (min limit (:idle-minutes harness))
      (or (:idle-minutes harness) limit))))

(defn sandbox
  "What sessions.api/create-session! takes as :sandbox; password is the
   session's secret key (OPENCODE_SERVER_PASSWORD)."
  [settings {:keys [session-id harness tenant team-id team run-id skills password] :as opts}]
  (let [config
        (opencode-config settings opts)]
    {:image (or (:image harness) (:session-image settings))
     :network (:network harness)
     :cpus (get-in harness [:resources :cpus])
     :memory-mb (get-in harness [:resources :memory-mb])
     :pids (get-in harness [:resources :pids])
     :llm-connection (get-in harness [:llm :connection])
     :llm-models (vec (get-in harness [:llm :models]))
     :tenant tenant
     :team-id team-id
     :run-id run-id
     :idle-minutes (idle-minutes harness team)
     :mcp-servers (mapv name (:mcp harness))
     :env (assoc lockdown-env "OPENCODE_SERVER_PASSWORD" (str password))
     :files (merge {"opencode/opencode.json" (http/write-json config)
                    "opencode/.gitignore" opencode-gitignore
                    "opencode/plugins/platform-monitor.js" (monitor-plugin settings session-id harness)}
                   (into {} (for [[path content] skills]
                              [(str "opencode/skills/" path) content])))}))

(defn session-sandbox
  "The sandbox of a session running a team's workflow with its agent and
   harness, the session's own key and the harness's skills. opts:
   {:session-id :tenant :team-id :workflow :agent :harness :team :prompt
    :interactive :result-tool :run-id}."
  [system {:keys [session-id tenant team-id workflow harness] :as opts}]
  (sandbox (:settings system)
           (merge (select-keys opts [:session-id :tenant :team-id :agent :harness :team :prompt
                                     :interactive :result-tool :run-id])
                  {:agent-id (:agent workflow)
                   :password (sessions/secret-key system session-id)
                   :skills (when (seq (:skills harness))
                             (platform/read-skills system tenant team-id (:skills harness)))})))
