(ns manager-svc.sessions.access
  "Who may see, manage and work in a session.

   The checks take a viewer: a username, or (viewer system username) built
   once for many sessions, which asks the platform about the person once
   and about each team once.

   Sessions a platform run started work with their team's credentials, so
   only the team's members (and platform admins) may work in them, however
   widely they are shared; others may look at them."
  (:require [clojure.tools.logging :as log]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.platform.api :as platform]
            [manager-svc.sessions.live :as live]
            [manager-svc.sessions.storage :as storage]))

(def blocked-reasons
  {:locked "This session is locked. Fork it to continue."
   :personal "This session uses its owner's personal credentials. Fork it to continue with yours."
   :private "This session is private to its owner. Fork it to continue."
   :team-only "This session works with its team's credentials, so only team members can work in it."
   :hidden "You can't open this session."})

(defn guarded
  "Calls another domain's facade; logs a failure and returns fallback."
  [fallback f & args]
  (try
    (apply f args)
    (catch Exception e
      (log/warn e "Facade call failed")
      fallback)))

(defn viewer
  [system username]
  {:username username
   :admin? (boolean (guarded false platform/platform-admin? system username))
   :role (memoize (fn [team-id]
                    (some-> (guarded nil platform/role system username team-id) keyword)))})

(defn- viewer-of
  [system who]
  (if (map? who) who (viewer system who)))

(defn platform-owned?
  [session]
  (auth/platform-user? (:sub session)))

(defn- owner?
  [{:keys [username]} session]
  (and (some? username) (= username (:sub session))))

(defn- role
  [viewer {:keys [team-id]}]
  (when team-id
    ((:role viewer) team-id)))

(defn can-view?
  [system session who]
  (let [viewer
        (viewer-of system who)

        role
        (role viewer session)]
    (or (owner? viewer session)
        (= :public (:visibility session))
        (:admin? viewer)
        (= :admin role)
        (and (some? role) (= :team (:visibility session))))))

(defn can-manage?
  [system session who]
  (let [viewer
        (viewer-of system who)]
    (or (owner? viewer session)
        (:admin? viewer)
        (= :admin (role viewer session)))))

(defn member?
  "True when username belongs to team-id."
  [system username team-id]
  (contains? (set (guarded #{} platform/teams-of system username)) team-id))

(defn- uses-personal?
  [system session]
  (try
    (boolean (credentials/uses-personal? system (:sub session)
                                         (storage/mcp-servers (:settings system) (:id session))))
    (catch Exception e
      (log/warn e "Could not tell whether session uses personal credentials" (:id session))
      nil)))

(defn personal?
  "True when the session works with its owner's personal credentials. When
   that can't be found out, it counts as personal."
  [system session]
  (and (not (platform-owned? session))
       (not (false? (live/cached! system [:personal (:id session)] live/personal-ttl-ms
                                  #(uses-personal? system session))))))

(defn- team-only?
  [viewer session]
  (and (platform-owned? session)
       (:team-id session)
       (not (:admin? viewer))
       (nil? (role viewer session))))

(defn open-blocked
  "Why the viewer may not work in the live session, or nil when they may."
  [system session who]
  (let [viewer
        (viewer-of system who)]
    (cond
      (:locked-at session) :locked
      (owner? viewer session) nil
      (team-only? viewer session) :team-only
      (personal? system session) :personal
      (= :private (:visibility session)) :private
      (can-view? system session viewer) nil
      :else :hidden)))

(defn can-open?
  [system session who]
  (nil? (open-blocked system session who)))
