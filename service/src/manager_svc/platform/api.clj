(ns manager-svc.platform.api
  "Facade other domains use to read the registry (onboarding spec, teams,
   definitions). Keys are kebab-case, enumerations keywords (:git, :admin,
   :agents)."
  (:require [clojure.tools.logging :as log]
            [manager-svc.auth :as auth]
            [manager-svc.errors :as errors]
            [manager-svc.platform.catalog :as catalog]
            [manager-svc.platform.registry :as registry]
            [manager-svc.platform.spec :as spec]
            [manager-svc.platform.state :as state]
            [manager-svc.system :as system]))

(defn platform-spec
  "The validated onboarding spec (platform.json): {:admins :llm-connections
   :allowed-images :teams :credentials :mcp-servers ...}, or an empty spec when
   not configured."
  [system]
  (or (get-in (state/snapshot system) [:platform :spec]) spec/empty-platform))

(defn configured?
  [system]
  (registry/configured? (:settings system)))

(defn status
  "{:commit :synced-at :error :configured}"
  [system]
  (let [platform
        (:platform (state/snapshot system))]
    {:commit (:commit platform)
     :synced-at (:synced-at platform)
     :error (:error platform)
     :configured (configured? system)}))

(defn- team-entry
  [team-state]
  (merge (:spec team-state)
         {:id (:team-id team-state)
          :commit (:commit team-state)
          :synced-at (:synced-at team-state)
          :status (:status team-state)
          :error (:error team-state)}))

(defn- team-states
  [system]
  (let [{:keys [platform teams]}
        (state/snapshot system)]
    (keep #(get teams %) (:team-ids platform))))

(defn teams
  "Team entries [{:id :name :description :repository :commit :synced-at :status :error :members :admins :secrets ...}]."
  [system]
  (vec (sort-by :id (map team-entry (team-states system)))))

(defn- team-state
  [system team-id]
  (let [{:keys [platform teams]}
        (state/snapshot system)]
    (when (some #{team-id} (:team-ids platform))
      (get teams team-id))))

(defn team
  [system team-id]
  (some-> (team-state system team-id) team-entry))

(defn platform-admin?
  [system username]
  (state/platform-admin? system username))

(defn require-admin!
  "Passes for the platform itself and platform administrators; fails with 403."
  [ctx]
  (when-not (or (auth/platform? ctx)
                (platform-admin? (system/of ctx) (auth/user-id ctx)))
    (errors/forbidden! "Platform administrators only")))

(defn role
  ":admin, :member or nil."
  [system username team-id]
  (if (platform-admin? system username)
    :admin
    (some #(when (= username (:username %)) (:role %))
          (:members (team-state system team-id)))))

(defn teams-of
  "Set of team ids the user belongs to."
  [system username]
  (if (platform-admin? system username)
    (set (map :team-id (team-states system)))
    (set (for [team (team-states system)
               :when (some #(= username (:username %)) (:members team))]
           (:team-id team)))))

(defn definitions
  "[{:kind :agents|:harnesses|:workflows :id :source :spec :errors :valid}] of a team, optionally of one kind."
  ([system team-id]
   (vec (:definitions (team-state system team-id))))
  ([system team-id kind]
   (filterv #(= (keyword kind) (:kind %)) (definitions system team-id))))

(defn definition
  "One valid definition ({:kind :id :source :spec ...}), or nil."
  [system team-id kind id]
  (some #(when (and (= id (:id %)) (:valid %)) %) (definitions system team-id kind)))

(defn workflows
  "Valid workflow definitions of a team."
  [system team-id]
  (filterv :valid (definitions system team-id :workflows)))

(defn team-dir
  "Directory of the team's definitions (.platform/ of its checkout), or nil."
  [system team-id]
  (registry/team-dir (:settings system) (team system team-id)))

(defn read-team-file
  "Text of a file in the team's synced repository (relative to .platform/).
   Fails with 404 for files outside it or missing."
  [system team-id relative]
  (or (registry/read-team-file (:settings system) (team system team-id) relative)
      (errors/not-found! (str relative " not found in the team definitions"))))

(defn read-skills
  "Text files of the given skill directories keyed by <skill>/<relative path>
   (SKILL.md and its resources), as registry.collect_skills."
  [system team-id skills]
  (if-let [base
           (team-dir system team-id)]
    (registry/collect-skills base skills)
    {}))

(defn harness-credentials
  "Credential ids a harness needs (through its MCP servers and secrets)."
  [system harness-spec]
  (registry/harness-credentials (platform-spec system) harness-spec))

(defn secret-host-allowed?
  [system secret url]
  (registry/secret-host-allowed? (platform-spec system) secret url))

(defn team-may-use?
  "True when the onboarding repository grants credential-id to the team."
  [system team-id credential-id]
  (boolean (when-let [entry (team system team-id)]
             (registry/team-may-use? (platform-spec system) entry credential-id))))

(def in-scope?
  spec/in-scope?)

(def credential-of
  "[platform-spec credential-id] the declared credential, with defaults."
  spec/credential)

(def mcp-server-of
  "[platform-spec server-id] the declared MCP server, with defaults."
  spec/mcp-server-of)

(def credential-hosts
  "[platform-spec credential-id] hosts the credential may be sent to."
  spec/credential-hosts)

(def personal?
  "True for a credential people provide themselves."
  spec/personal?)

(defn credential
  "Declared credential spec, or nil."
  [system credential-id]
  (spec/credential (platform-spec system) credential-id))

(defn mcp-server
  [system server-id]
  (spec/mcp-server-of (platform-spec system) server-id))

(defn llm-connection
  [system connection-id]
  (spec/llm-connection-of (platform-spec system) connection-id))

(defn activity
  "Marketplace activity {:id :name :interactive :result-tool ...}, or nil."
  [_system activity-id]
  (catalog/activity activity-id))

(defn validate-inputs
  "Run inputs decoded for a workflow's activity and options (snake_case or
   kebab-case keys): {:value inputs :errors [\"path: message\"]}."
  [_system activity-id inputs options]
  (catalog/validate-inputs activity-id inputs options))

(defn validate-options
  [_system activity-id options]
  (catalog/validate-options activity-id options))

(defn- gateway
  [system]
  (:platform/edd system))

(defn load-cache!
  "Rebuilds the cache from the aggregates."
  [system]
  (when-let [{:keys [query]}
             (gateway system)]
    (state/load! system (query {:query-id :get-registry}))))

(defn- secret-of
  "credentials.api reads the platform spec through this facade, so it is
   resolved when a sync runs instead of required."
  [system]
  (fn [secret-name]
    (try
      ((requiring-resolve 'manager-svc.credentials.api/secret) system secret-name)
      (catch Exception error
        (log/warn "Secret lookup failed" secret-name (ex-message error))
        nil))))

(defn- record-commands
  [result handled-request]
  (let [request
        (when handled-request {:handled-request handled-request})]
    (if (:error result)
      [{:cmd-id :record-platform-sync
        :attrs (merge {:error (:error result) :synced-at (:synced-at result)} request)}]
      (into [{:cmd-id :record-platform-sync
              :attrs (merge {:spec (:spec result)
                             :commit (:commit result)
                             :synced-at (:synced-at result)
                             :team-ids (mapv :team-id (:teams result))}
                            request)}]
            (map (fn [team] {:cmd-id :record-team-sync :attrs team}))
            (:teams result)))))

(defonce ^:private sync-lock
  (Object.))

(defn- sync-now!
  [system handled-request]
  (locking sync-lock
    (let [result
          (registry/run-sync {:settings (:settings system)
                              :secret (secret-of system)})]
      (when-not (:unconfigured result)
        (when-let [{:keys [command]}
                   (gateway system)]
          (apply command (record-commands result handled-request))
          (load-cache! system)))
      (when (:error result)
        (log/warn "Platform sync failed:" (:error result)))
      (registry/summary result))))

(defn sync!
  "Sync onboarding and team repositories now; returns the sync summary."
  [system]
  (sync-now! system nil))

(defn sync-requested!
  "Syncs when an admin requested it (:sync-platform) and it was not handled
   yet; returns the summary or nil."
  [system]
  (when-let [{:keys [query]}
             (gateway system)]
    (let [{:keys [requested-at handled-request]}
          (query {:query-id :get-platform-state})]
      (when (and requested-at (not= requested-at handled-request) (configured? system))
        (sync-now! system requested-at)))))
