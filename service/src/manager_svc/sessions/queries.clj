(ns manager-svc.sessions.queries
  (:require [edd.common :as common]
            [edd.core :as edd]
            [manager-svc.auth :as auth]
            [manager-svc.directory :as directory]
            [manager-svc.errors :as errors]
            [manager-svc.observability.api :as observability]
            [manager-svc.sessions.access :as access]
            [manager-svc.sessions.aggregate :as aggregate]
            [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.links :as links]
            [manager-svc.sessions.live :as live]
            [manager-svc.sessions.views :as views]
            [manager-svc.system :as system]))

(def empty-usage
  {:calls 0 :prompt-tokens 0 :completion-tokens 0 :cached-tokens 0 :errors 0 :avg-latency-ms 0})

(defn session-record
  [ctx session-id]
  (when session-id
    (let [found
          (common/get-by-id ctx {:id session-id})]
      (when (aggregate/session? found)
        found))))

(defn- newest-first
  [sessions]
  (vec (sort-by :created-at #(compare %2 %1) sessions)))

(defn- all-directories
  "Every team directory, also of teams that left the registry, and the
   platform's, which lists sessions without a team."
  [ctx]
  (conj (directory/identities ctx "team") directory/platform))

(defn listed-sessions
  "Directory rows of the sessions username may see, newest first: their own,
   their teams' and the public ones of every team; every session for
   platform admins."
  [ctx username]
  (let [viewer
        (access/viewer (system/of ctx) username)

        rows
        (directory/rows-of ctx (cons (directory/user username) (all-directories ctx)) :sessions)]
    (newest-first (filter #(access/can-view? (system/of ctx) % viewer) rows))))

(defn- visible!
  [ctx session-id]
  (let [session
        (session-record ctx session-id)]
    (when-not (and session (access/can-view? (system/of ctx) session (auth/user-id ctx)))
      (errors/not-found! "Session not found"))
    session))

(defn list-sessions
  [ctx _query]
  (let [system
        (system/of ctx)

        username
        (auth/user-id ctx)

        viewer
        (access/viewer system username)

        statuses
        (backend/statuses (live/backend-of system))]
    (mapv #(views/session-view system statuses % viewer)
          (listed-sessions ctx username))))

(defn get-session
  [ctx {:keys [session-id]}]
  (let [system
        (system/of ctx)

        session
        (visible! ctx session-id)

        status
        (backend/status (live/backend-of system) session-id)]
    (assoc (views/session-view system {session-id status} session (auth/user-id ctx))
           :usage (merge empty-usage (access/guarded {} observability/usage-for-session system session-id))
           :audit-events (or (access/guarded 0 observability/audit-count system session-id) 0))))

(defn list-conversations
  [ctx {:keys [session-id]}]
  (visible! ctx session-id)
  (vec (for [conversation (access/guarded [] observability/audit-conversations (system/of ctx) session-id)]
         (assoc conversation :open-path (links/open-path {} (:id conversation))))))

(defn get-session-record
  "{:session record}: edd answers a query result holding :error (a failed
   start) as an error, so the record is wrapped."
  [ctx {:keys [session-id]}]
  {:session (session-record ctx session-id)})

(defn list-session-rows
  "Directory rows of every session, or of a team's sessions."
  [ctx {:keys [team-id]}]
  (directory/rows-of ctx
                     (if team-id [(directory/team team-id)] (all-directories ctx))
                     :sessions))

(defn count-running-sessions
  "{:count n}: running sessions of a team, for a command's :deps (it reads
   the team's directory in this request, without calling edd again)."
  [ctx {:keys [team-id]}]
  {:count (count (live/running-ids (system/of ctx) (list-session-rows ctx {:team-id team-id})))})

(defn get-idle-stop-request
  [ctx _query]
  {:requested-at (:requested-at (common/get-by-id ctx {:id aggregate/reaper-id}))})

(def ^:private SessionId
  [:map [:session-id uuid?]])

(defn register
  [ctx]
  (-> ctx
      (edd/reg-query :list-sessions list-sessions
                     :auth {:role [:user]})
      (edd/reg-query :get-session get-session
                     :auth {:role [:user]}
                     :consumes SessionId)
      (edd/reg-query :list-conversations list-conversations
                     :auth {:role [:user]}
                     :consumes SessionId)
      (edd/reg-query :get-session-record get-session-record
                     :auth {:role [:non-interactive]}
                     :consumes SessionId)
      (edd/reg-query :list-session-rows list-session-rows
                     :auth {:role [:non-interactive]}
                     :consumes [:map [:team-id {:optional true} string?]])
      (edd/reg-query :count-running-sessions count-running-sessions
                     :auth {:role [:non-interactive]}
                     :consumes [:map [:team-id string?]])
      (edd/reg-query :get-idle-stop-request get-idle-stop-request
                     :auth {:role [:non-interactive]})))
