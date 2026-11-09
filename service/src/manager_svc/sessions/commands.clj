(ns manager-svc.sessions.commands
  "Session commands. People change sharing, request start/stop/delete, lock and
   fork; the platform creates sessions for runs and records what the
   lifecycle worker did."
  (:require [edd.core :as edd]
            [edd.el.ctx :as el-ctx]
            [manager-svc.auth :as auth]
            [manager-svc.errors :as errors]
            [manager-svc.platform.api :as platform]
            [manager-svc.sessions.access :as access]
            [manager-svc.sessions.aggregate :as aggregate]
            [manager-svc.sessions.links :as links]
            [manager-svc.system :as system]
            [manager-svc.time :as time]))

(def Visibility
  [:enum :private :team :public])

(def name-max 100)

(defn- person
  "Username of the caller, nil when the platform calls."
  [ctx]
  (when-not (auth/platform? ctx)
    (auth/user-id ctx)))

(defn- current
  [ctx]
  (el-ctx/get-aggregate ctx))

(defn- visible!
  [ctx session]
  (let [username
        (person ctx)]
    (when (or (not (aggregate/session? session))
              (and username (not (access/can-view? (system/of ctx) session username))))
      (errors/not-found! "Session not found"))
    session))

(defn- managed!
  [ctx session]
  (let [username
        (person ctx)]
    (visible! ctx session)
    (when (and username (not (access/can-manage? (system/of ctx) session username)))
      (errors/forbidden! "Only the owner or a team admin can change this session"))
    session))

(defn- event
  ([event-id]
   {:event-id event-id :attrs {}})
  ([event-id attrs]
   {:event-id event-id :attrs attrs}))

(defn- truncate
  [text]
  (let [value
        (str text)]
    (subs value 0 (min name-max (count value)))))

(defn create-session
  [ctx {:keys [attrs]}]
  (when (current ctx)
    (errors/invalid! "Session already exists"))
  (let [now
        (time/now)

        session-name
        (truncate (:name attrs))]
    (cond-> [(event :session-created
                    {:sub (:sub attrs)
                     :email (:email attrs)
                     :name (if (seq session-name) session-name (str (:activity attrs)))
                     :description (or (:description attrs) "")
                     :visibility (or (:visibility attrs) :private)
                     :labels (or (:labels attrs) {})
                     :env (or (:env attrs) {})
                     :image (or (:image attrs) "")
                     :team-id (:team-id attrs)
                     :run-id (:run-id attrs)
                     :activity (:activity attrs)
                     :created-at now
                     :last-activity-at now})]
      (:start attrs) (conj (event :session-start-requested {:at now :by auth/platform-user})))))

(defn update-session
  [ctx {:keys [attrs]}]
  (managed! ctx (current ctx))
  (let [changes
        (into {} (remove (comp nil? val)) (select-keys attrs [:name :description :visibility]))]
    (when (and (contains? changes :name) (not (<= 1 (count (:name changes)) name-max)))
      (errors/invalid! "Name must be 1 to 100 characters"))
    (when (seq changes)
      (event :session-updated changes))))

(defn start-session
  [ctx _cmd]
  (let [session
        (visible! ctx (current ctx))

        username
        (person ctx)

        blocked
        (if username
          (access/open-blocked (system/of ctx) session username)
          (when (:locked-at session) :locked))]
    (when blocked
      (errors/fail! 409 (access/blocked-reasons blocked)))
    (when-not (= :start (:pending session))
      (event :session-start-requested {:at (time/now) :by (or username auth/platform-user)}))))

(defn stop-session
  [ctx _cmd]
  (let [session
        (managed! ctx (current ctx))]
    (when-not (#{:stop :delete} (:pending session))
      (event :session-stop-requested {:at (time/now)}))))

(defn delete-session
  [ctx _cmd]
  (let [session
        (managed! ctx (current ctx))]
    (when-not (= :delete (:pending session))
      (event :session-delete-requested {:at (time/now)}))))

(defn lock-session
  [ctx _cmd]
  (let [session
        (visible! ctx (current ctx))

        username
        (person ctx)]
    (when (not= username (:sub session))
      (errors/forbidden! "Only the owner can lock a session"))
    (when (:locked-at session)
      (errors/fail! 409 "The session is already locked"))
    (event :session-locked {:by username :at (time/now)})))

(defn source-dep
  [_deps cmd]
  {:query-id :get-session-record
   :session-id (get-in cmd [:attrs :source-id])})

(defn fork-session
  [ctx {:keys [attrs]}]
  (let [username
        (person ctx)

        source
        (visible! ctx (get-in ctx [:source :session]))

        team-id
        (:team-id source)

        workflow
        (get-in source [:labels :workflow])

        now
        (time/now)]
    (when (current ctx)
      (errors/invalid! "Session already exists"))
    (when-not (and team-id workflow)
      (errors/invalid! "This session was not started from a workflow, so it can't be forked"))
    (when-not (access/member? (system/of ctx) username team-id)
      (errors/forbidden! (str "Only members of team " team-id " can fork this session")))
    [(event :session-created
            {:sub username
             :email (get-in ctx [:meta :user :email])
             :name (truncate (str (:name source) " (fork)"))
             :description (or (:description source) "")
             :visibility :private
             :labels (merge (select-keys (:labels source) [:workflow links/conversation-label])
                            {:workflow workflow})
             :env {}
             :image ""
             :team-id team-id
             :activity "session"
             :forked-from (:source-id attrs)
             :fork-pending true
             :created-at now
             :last-activity-at now})
     (event :session-start-requested {:at now :by username})]))

(defn touch-session
  [ctx _cmd]
  (when (aggregate/session? (current ctx))
    (event :session-touched {:at (time/now)})))

(defn label-session
  [ctx {:keys [attrs]}]
  (visible! ctx (current ctx))
  (event :session-labelled (select-keys attrs [:key :value])))

(defn- recorded
  "Records what the lifecycle worker did about action. An outcome for an
   action that is no longer pending (the person asked for something else
   meanwhile) is dropped, so the newer request stays pending."
  [event-id action]
  (fn [ctx {:keys [attrs]}]
    (let [session
          (current ctx)]
      (when (and (= :session (:kind session))
                 (= action (:pending session)))
        (event event-id (merge {:at (time/now)} attrs))))))

(defn record-fork-prepared
  [ctx _cmd]
  (when (:fork-pending (current ctx))
    (event :session-fork-prepared {:at (time/now)})))

(defn record-failed
  "Records a failed action: preparing a fork (:fork) or a pending action."
  [ctx {:keys [attrs]}]
  (let [session
        (current ctx)

        action
        (:action attrs)]
    (when (and (= :session (:kind session))
               (if (= :fork action)
                 (:fork-pending session)
                 (= action (:pending session))))
      (event :session-action-failed {:at (time/now) :error (:error attrs) :action action}))))

(defn stop-idle-sessions
  [ctx _cmd]
  (platform/require-admin! ctx)
  (event :idle-sessions-stop-requested {:at (time/now) :by (auth/user-id ctx)}))

(def ^:private people-and-platform
  {:role [:user :non-interactive]})

(def ^:private platform-only
  {:role [:non-interactive]})

(def ^:private Id
  [:map [:id uuid?]])

(defn register
  [ctx]
  (-> ctx
      (edd/reg-cmd :create-session create-session
                   :auth platform-only
                   :consumes [:map [:attrs [:map [:sub string?]]]])
      (edd/reg-cmd :update-session update-session
                   :auth people-and-platform
                   :consumes [:map [:attrs [:map
                                            [:name {:optional true} [:maybe string?]]
                                            [:description {:optional true} [:maybe string?]]
                                            [:visibility {:optional true} [:maybe Visibility]]]]])
      (edd/reg-cmd :start-session start-session
                   :auth people-and-platform
                   :consumes Id)
      (edd/reg-cmd :stop-session stop-session
                   :auth people-and-platform
                   :consumes Id)
      (edd/reg-cmd :delete-session delete-session
                   :auth people-and-platform
                   :consumes Id)
      (edd/reg-cmd :lock-session lock-session
                   :auth {:role [:user]}
                   :consumes Id)
      (edd/reg-cmd :fork-session fork-session
                   :auth {:role [:user]}
                   :deps [:source source-dep]
                   :consumes [:map [:attrs [:map [:source-id uuid?]]]])
      (edd/reg-cmd :touch-session touch-session
                   :auth platform-only
                   :consumes Id)
      (edd/reg-cmd :label-session label-session
                   :auth platform-only
                   :consumes [:map [:attrs [:map [:key [:or string? keyword?]] [:value string?]]]])
      (edd/reg-cmd :record-session-started (recorded :session-started :start)
                   :auth platform-only
                   :consumes Id)
      (edd/reg-cmd :record-session-stopped (recorded :session-stopped :stop)
                   :auth platform-only
                   :consumes Id)
      (edd/reg-cmd :record-session-deleted (recorded :session-deleted :delete)
                   :auth platform-only
                   :consumes Id)
      (edd/reg-cmd :record-session-fork-prepared record-fork-prepared
                   :auth platform-only
                   :consumes Id)
      (edd/reg-cmd :record-session-failed record-failed
                   :auth platform-only
                   :consumes [:map [:attrs [:map
                                            [:error string?]
                                            [:action [:enum :fork :start :stop :delete]]]]])
      (edd/reg-cmd :stop-idle-sessions stop-idle-sessions
                   :auth people-and-platform
                   :id-fn (constantly aggregate/reaper-id))))
