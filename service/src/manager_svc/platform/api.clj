(ns manager-svc.platform.api
  "Facade other domains use to read the registry: tenants, their
   tenant.json, teams (with their team.json) and definitions. Everything is
   read within one tenant. Keys are kebab-case, enumerations keywords (:git,
   :admin, :agents)."
  (:require [clojure.tools.logging :as log]
            [manager-svc.directory :as directory]
            [manager-svc.errors :as errors]
            [manager-svc.platform.catalog :as catalog]
            [manager-svc.platform.registry :as registry]
            [manager-svc.platform.spec :as spec]
            [manager-svc.platform.state :as state]
            [manager-svc.roles :as roles])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.function Function]))

(defn tenants
  "[{:name :source :origin :config|:registered}] of every tenant."
  [system]
  (state/known-tenants system))

(defn tenant-known?
  [system tenant]
  (some? (state/known-tenant system tenant)))

(defn- tenant-state
  [system tenant]
  (get-in (state/snapshot system) [:tenants tenant]))

(defn tenant-spec
  "The validated tenant.json of tenant: {:llm-connections :allowed-images
   :teams :credentials :mcp-servers ...}, or an empty spec when it was never
   synced."
  [system tenant]
  (or (:spec (tenant-state system tenant)) spec/empty-tenant))

(defn status
  "{:commit :synced-at :error :configured} of tenant's sync."
  [system tenant]
  (let [state
        (tenant-state system tenant)]
    {:commit (:commit state)
     :synced-at (:synced-at state)
     :error (:error state)
     :configured (tenant-known? system tenant)}))

(defn- team-entry
  [system tenant team-state]
  (merge (registry/team-entry tenant (tenant-spec system tenant) (:spec team-state) (:config team-state))
         {:id (:team-id team-state)
          :commit (:commit team-state)
          :synced-at (:synced-at team-state)
          :status (:status team-state)
          :error (:error team-state)}))

(defn- team-states
  [system tenant]
  (let [state
        (tenant-state system tenant)]
    (keep #(get-in state [:teams %]) (:team-ids state))))

(defn teams
  "Team entries of tenant [{:id :tenant :name :description :repository
   :secrets :notifications :budget :llm-connections :mcp-servers :projects
   :required-credentials :commit :synced-at :status :error}]."
  [system tenant]
  (vec (sort-by :id (map #(team-entry system tenant %) (team-states system tenant)))))

(defn- team-state
  [system tenant team-id]
  (let [state
        (tenant-state system tenant)]
    (when (some #{team-id} (:team-ids state))
      (get-in state [:teams team-id]))))

(defn team
  [system tenant team-id]
  (some->> (team-state system tenant team-id) (team-entry system tenant)))

(defn team-name
  "What people call a team: its name, else its id."
  [team]
  (or (:name team) (:id team)))

(defn teams-of
  "Ids of the teams of the caller's tenant they belong to: every team for a
   tenant admin."
  [system caller]
  (let [tenant
        (:tenant caller)

        ids
        (map :team-id (team-states system tenant))]
    (if (roles/tenant-admin? caller tenant)
      (set ids)
      (set (filter (roles/claimed-team-ids caller tenant) ids)))))

(defn role
  ":admin, :member or nil: the caller's role in a team of their tenant."
  [_system caller team-id]
  (roles/team-role caller (:tenant caller) team-id))

(defn definitions
  "[{:kind :agents|:harnesses|:workflows :id :source :spec :errors :valid}] of a team, optionally of one kind."
  ([system tenant team-id]
   (vec (:definitions (team-state system tenant team-id))))
  ([system tenant team-id kind]
   (filterv #(= (keyword kind) (:kind %)) (definitions system tenant team-id))))

(defn definition
  "One valid definition ({:kind :id :source :spec ...}), or nil."
  [system tenant team-id kind id]
  (some #(when (and (= id (:id %)) (:valid %)) %) (definitions system tenant team-id kind)))

(defn workflows
  "Valid workflow definitions of a team."
  [system tenant team-id]
  (filterv :valid (definitions system tenant team-id :workflows)))

(defn team-dir
  "Directory of the team's definitions (.platform/ of its checkout), or nil."
  [system tenant team-id]
  (registry/team-dir (:settings system) (team system tenant team-id)))

(defn read-team-file
  "Text of a file in the team's synced repository (relative to .platform/).
   Fails with 404 for files outside it or missing."
  [system tenant team-id relative]
  (or (registry/read-team-file (:settings system) (team system tenant team-id) relative)
      (errors/not-found! (str relative " not found in the team definitions"))))

(defn read-skills
  "Text files of the given skill directories keyed by <skill>/<relative path>
   (SKILL.md and its resources)."
  [system tenant team-id skills]
  (if-let [base
           (team-dir system tenant team-id)]
    (registry/collect-skills base skills)
    {}))

(defn harness-credentials
  "Credential ids a harness needs (through its MCP servers)."
  [system tenant harness-spec]
  (registry/harness-credentials (tenant-spec system tenant) harness-spec))

(defn secret-host-allowed?
  [system tenant secret url]
  (registry/secret-host-allowed? (tenant-spec system tenant) secret url))

(defn team-may-use?
  "True when the tenant grants credential-id to the team."
  [system tenant team-id credential-id]
  (boolean (when-let [entry (team system tenant team-id)]
             (registry/team-may-use? (tenant-spec system tenant) entry credential-id))))

(def in-scope?
  spec/in-scope?)

(def credential-of
  "[tenant-spec credential-id] the declared credential, with defaults."
  spec/credential)

(def mcp-server-of
  "[tenant-spec server-id] the declared MCP server, with defaults."
  spec/mcp-server-of)

(def credential-hosts
  "[tenant-spec credential-id] hosts the credential may be sent to."
  spec/credential-hosts)

(def provides?
  "[credential scope] true when :tenant, :team or :user may provide its value."
  spec/provides?)

(def personal?
  "True for a credential people may provide themselves."
  spec/personal?)

(def credential-scopes
  "Scopes in resolution order: [:user :team :tenant]."
  spec/scopes)

(defn credential
  "Declared credential spec, or nil."
  [system tenant credential-id]
  (spec/credential (tenant-spec system tenant) credential-id))

(defn mcp-server
  [system tenant server-id]
  (spec/mcp-server-of (tenant-spec system tenant) server-id))

(defn llm-connection
  [system tenant connection-id]
  (spec/llm-connection-of (tenant-spec system tenant) connection-id))

(defn activity
  "Marketplace activity {:id :name :interactive :result-tool ...}, or nil."
  [_system activity-id]
  (catalog/activity activity-id))

(defn validate-inputs
  "Run inputs decoded for a workflow's activity and options (snake_case or
   kebab-case keys): {:value inputs :errors [\"path: message\"]}."
  [_system activity-id inputs options]
  (catalog/validate-inputs activity-id inputs options))

(defn inputs-schema
  "JSON Schema of the inputs a run of a workflow with this activity and
   options takes."
  [_system activity-id options]
  (catalog/inputs-schema activity-id options))

(defn validate-options
  [_system activity-id options]
  (catalog/validate-options activity-id options))

(defn- gateway
  [system]
  (:platform/edd system))

(defonce ^:private cache-lock
  (Object.))

(defn load-cache!
  "Rebuilds the cache from the aggregates. One load at a time, so a
   snapshot read earlier never replaces a later one."
  [system]
  (when-let [{:keys [query]}
             (gateway system)]
    (locking cache-lock
      (state/load! system (query {:query-id :get-registry})))))

(defn- secret-of
  "credentials.api reads tenant specs through this facade, so it is
   resolved when a sync runs instead of required."
  [system tenant]
  (fn [secret-name]
    (try
      ((requiring-resolve 'manager-svc.credentials.api/secret) system tenant secret-name)
      (catch Exception error
        (log/warn "Secret lookup failed" tenant secret-name (ex-message error))
        nil))))

(defn record-commands
  [result handled-request]
  (let [tenant
        (:tenant result)

        request
        (when handled-request {:handled-request handled-request})

        identity
        (directory/tenant tenant)]
    (if (:error result)
      [{:cmd-id :record-tenant-sync
        :attrs (merge {:identity identity :tenant tenant :error (:error result) :synced-at (:synced-at result)} request)}]
      (into [{:cmd-id :record-tenant-sync
              :attrs (merge {:identity identity
                             :tenant tenant
                             :spec (:spec result)
                             :commit (:commit result)
                             :synced-at (:synced-at result)
                             :team-ids (mapv :team-id (:teams result))}
                            request)}]
            (map (fn [team]
                   {:cmd-id :record-team-sync
                    :attrs (assoc team :identity (directory/team tenant (:team-id team)))}))
            (:teams result)))))

(defonce ^:private locks
  (ConcurrentHashMap.))

(defn- lock-of
  [tenant]
  (.computeIfAbsent ^ConcurrentHashMap locks tenant (reify Function (apply [_ _] (Object.)))))

(defn sync-tenant!
  "Syncs one tenant's onboarding and team repositories now; returns its
   summary. Tenants sync independently: one tenant's failure or slow
   repository never holds up another."
  ([system tenant]
   (sync-tenant! system tenant nil))
  ([system tenant handled-request]
   (when-let [known
              (state/known-tenant system tenant)]
     (locking (lock-of tenant)
       (let [result
             (registry/run-sync {:settings (:settings system)
                                 :secret (secret-of system tenant)}
                                known)]
         (when-let [{:keys [command]}
                    (gateway system)]
           (apply command (record-commands result handled-request))
           (load-cache! system))
         (when (:error result)
           (log/warn "Sync of tenant" tenant "failed:" (:error result)))
         (registry/summary result))))))

(defn- each-tenant
  "{tenant (f tenant)} run concurrently, one worker per tenant."
  [tenants f]
  (let [futures
        (into {} (map (fn [tenant] [tenant (future (f tenant))])) tenants)]
    (into (sorted-map)
          (map (fn [[tenant result]]
                 [tenant (try
                           @result
                           (catch Exception e
                             (log/warn e "Sync of tenant failed" tenant)
                             {:error (ex-message e)}))]))
          futures)))

(defn sync!
  "Syncs every tenant; returns {tenant summary}."
  [system]
  (each-tenant (map :name (tenants system)) #(sync-tenant! system %)))

(defn- new-unknown-requests!
  "True when a request names a tenant the cache doesn't know and that was
   not seen before: a tenant registered since the cache was loaded. Requests
   for tenants that stay unknown are remembered, so they reload nothing."
  [system unhandled]
  (let [unknown
        (set (for [{:keys [tenant requested-at]} unhandled
                   :when (not (tenant-known? system tenant))]
               [tenant requested-at]))

        seen
        (:platform/unknown-sync-requests system)

        before
        (if seen (first (reset-vals! seen unknown)) #{})]
    (boolean (seq (remove before unknown)))))

(defn sync-requested!
  "Syncs the tenants whose admins requested it (:sync-tenant) and that were
   not handled yet; returns {tenant summary}."
  [system]
  (when-let [{:keys [query]}
             (gateway system)]
    (let [unhandled
          (filter #(and (:requested-at %) (not= (:requested-at %) (:handled-request %)))
                  (query {:query-id :get-sync-requests}))

          _
          (when (new-unknown-requests! system unhandled)
            (load-cache! system))

          pending
          (for [{:keys [tenant requested-at]} unhandled
                :when (tenant-known? system tenant)]
            [tenant requested-at])

          requested
          (into {} pending)]
      (each-tenant (keys requested) #(sync-tenant! system % (get requested %))))))
