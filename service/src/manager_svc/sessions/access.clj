(ns manager-svc.sessions.access
  "Who may see, manage and work in a session.

   The checks take who asks: a caller map ([:meta :user] of an edd request)
   or an edge identity, both carrying the tenant and team roles their token
   was issued with. Nothing is visible across tenants.

   Sessions a platform run started work with their team's credentials, so
   only the team's members (and tenant admins) may work in them, however
   widely they are shared; others may look at them."
  (:require [clojure.tools.logging :as log]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.errors :as errors]
            [manager-svc.roles :as roles]
            [manager-svc.sessions.aggregate :as aggregate]
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
  "The person a check is about: a caller map ([:meta :user] of an edd
   request, keyed :id) or an edge identity (keyed :sub)."
  [who]
  (if (contains? who :username)
    who
    (assoc (select-keys who [:tenant :teams :tenant-admin])
           :username (or (:id who) (:sub who)))))

(defn platform-owned?
  [session]
  (auth/platform-user? (:sub session)))

(defn- owner?
  [{:keys [username]} session]
  (and (some? username) (= username (:sub session))))

(defn- role
  [viewer {:keys [tenant team-id]}]
  (when team-id
    (roles/team-role viewer tenant team-id)))

(defn- same-tenant?
  [viewer session]
  (roles/same-tenant? viewer (:tenant session)))

(defn can-view?
  "Nothing is visible across tenants."
  [_system session who]
  (let [viewer
        (viewer who)

        role
        (role viewer session)]
    (and (same-tenant? viewer session)
         (or (owner? viewer session)
             (= :tenant (:visibility session))
             (= :admin role)
             (and (some? role) (= :team (:visibility session)))))))

(defn visible-to
  "The sessions of rows who may see."
  [system who rows]
  (let [viewer
        (viewer who)]
    (filterv #(can-view? system % viewer) rows)))

(defn visible!
  "session when it exists and who may see it (the platform, nil, sees every
   session); 404 otherwise, so nobody learns it exists."
  [system session who]
  (when-not (and (aggregate/session? session)
                 (or (nil? who) (can-view? system session who)))
    (errors/not-found! "Session not found"))
  session)

(defn can-manage?
  [_system session who]
  (let [viewer
        (viewer who)]
    (and (same-tenant? viewer session)
         (or (owner? viewer session)
             (= :admin (role viewer session))))))

(defn- uses-personal?
  [system session]
  (try
    (boolean (credentials/uses-personal? system
                                         {:tenant (:tenant session) :team-id (:team-id session) :user (:sub session)}
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
       (nil? (role viewer session))))

(defn open-blocked
  "Why the viewer may not work in the live session, or nil when they may."
  [system session who]
  (let [viewer
        (viewer who)]
    (cond
      (not (same-tenant? viewer session)) :hidden
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
