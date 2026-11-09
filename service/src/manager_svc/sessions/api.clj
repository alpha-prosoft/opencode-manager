(ns manager-svc.sessions.api
  "Facade other domains use for sessions (sandboxed OpenCode instances).
   Session maps are the aggregate: {:id (uuid) :sub :email :name
   :description :visibility :labels :env :image :team-id :run-id :activity
   :created-at :last-activity-at :stopped-at :locked-by :locked-at
   :forked-from}."
  (:require [lambda.uuid :as uuid]
            [manager-svc.auth :as auth]
            [manager-svc.sessions.access :as access]
            [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.links :as links]
            [manager-svc.sessions.live :as live]
            [manager-svc.sessions.records :as records]
            [manager-svc.sessions.storage :as storage]
            [manager-svc.sessions.views :as views]))

(defn get-session
  "The session aggregate with its :secret-key and :sandbox config (without
   files), or nil. Never hand this map to the UI."
  [system session-id]
  (when-let [session
             (records/one system session-id)]
    (assoc session
           :secret-key (links/secret-key (:settings system) (:id session))
           :sandbox (storage/load-sandbox (:settings system) (:id session)))))

(def config-mount
  "Where a sandbox finds its config files (read-only)."
  backend/config-mount)

(defn platform-owned?
  "True for a session a platform run started: it works with team credentials."
  [session]
  (access/platform-owned? session))

(defn can-view?
  [system session username]
  (access/can-view? system session username))

(defn describe-session
  "What username sees of a session, the Session shape :get-session returns
   (without usage and audit counts)."
  [system session username]
  (let [status
        (backend/status (live/backend-of system) (:id session))]
    (views/session-view system {(:id session) status} session username)))

(defn session-count
  [system]
  (count (records/rows system)))

(defn create-session!
  "Creates and starts a session, as SessionService.create. opts:
   {:identity {:sub :email :name} :name :description :visibility :labels :env
    :image :team-id :run-id :activity :sandbox (what storage.write_sandbox gets)
    :start? (default true)}.
   Returns the session map."
  [system {:keys [id identity sandbox start?] :or {start? true} :as opts}]
  (let [session-id
        (or id (uuid/gen))

        settings
        (:settings system)]
    (storage/prepare! settings session-id)
    (when sandbox
      (storage/write-sandbox! settings session-id sandbox))
    (records/command! system :create-session session-id
                      (merge (select-keys opts [:name :description :visibility :labels :env :image
                                                :team-id :run-id :activity])
                             {:sub (:sub identity)
                              :email (:email identity)
                              :start start?}))
    (records/kick-lifecycle!)
    (get-session system session-id)))

(defn start-session!
  [system session-id]
  (records/command! system :start-session session-id)
  (records/kick-lifecycle!))

(defn stop-session!
  [system session-id]
  (records/command! system :stop-session session-id)
  (records/kick-lifecycle!))

(defn ready?
  "True when the session's OpenCode server answers."
  [system session-id]
  (live/ready? system session-id))

(defn running-session-ids
  [system]
  (live/running-ids system (records/rows system)))

(defn running-count
  "Running sessions of a team: its directory rows against one backend status read."
  [system team-id]
  (count (live/running-ids system (records/rows system team-id))))

(defn label!
  [system session-id key value]
  (records/command! system :label-session session-id {:key (name key) :value (str value)}))

(defn touch!
  [system session-id]
  (records/touch! system session-id))

(defn secret-key
  "The session's OpenCode server password, which its sandbox also presents to
   the LLM and MCP gateways. Put it in the sandbox env as OPENCODE_SERVER_PASSWORD."
  [system session-id]
  (links/secret-key (:settings system) session-id))

(defn session-base-url
  "Internal URL of the session's OpenCode server (for the runner and collectors)."
  [system session-id]
  (backend/service-url (live/backend-of system) session-id))

(defn entry-url
  "Public URL a person opens to enter the session."
  [system session]
  (links/entry-url (:settings system) session))

(defn workspace-dir
  "Path of the session's workspace on the shared mount."
  [system session-id]
  (str (storage/workspace (:settings system) session-id)))

(defn hand-over!
  "Gives files written into the session (e.g. a cloned repository) to the sandbox user."
  [system session-id]
  (storage/hand-over! (:settings system) session-id))

(defn mcp-servers
  "MCP server ids the session's sandbox config allows."
  [system session-id]
  (storage/mcp-servers (:settings system) session-id))

(defn session-by-key
  "The session a sandbox key belongs to, for one of purposes (\"llm\",
   \"mcp\", \"tools\", \"telemetry\"), or nil. Keys of a stopped or
   locked session are refused."
  [system key purposes]
  (when-let [session-id
             (auth/derived-key-owner (:settings system) key purposes)]
    (when-let [session
               (get-session system session-id)]
      (when-not (or (:stopped-at session) (:locked-at session))
        session))))
