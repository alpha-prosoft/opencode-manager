(ns manager-svc.sessions.aggregate
  "The session aggregate. Requested lifecycle changes wait in :pending
   (:start, :stop or :delete) until the lifecycle worker carries them out and
   records the outcome. Deleted sessions keep their history with :deleted-at.
   Touches only keep :last-activity-at roughly current for restarts; they
   change no directory row.

   A tenant's reaper aggregate (reaper-id) holds its administrators' last
   request to stop the tenant's idle sessions, which the reaper carries out."
  (:require [edd.core :as edd]
            [lambda.uuid :as uuid]
            [manager-svc.auth :as auth]
            [manager-svc.directory :as directory]))

(defn reaper-id
  [tenant]
  (uuid/named (str "sessions:reaper:" tenant)))

(defn session?
  [aggregate]
  (and (= :session (:kind aggregate))
       (nil? (:deleted-at aggregate))))

(defn- created
  [aggregate {:keys [attrs]}]
  (merge aggregate (into {} (remove (comp nil? val)) attrs) {:kind :session}))

(defn- updated
  [aggregate {:keys [attrs]}]
  (merge aggregate attrs))

(defn- labelled
  [aggregate {:keys [attrs]}]
  (assoc-in aggregate [:labels (keyword (name (:key attrs)))] (:value attrs)))

(defn- pending
  [action]
  (fn [aggregate _event]
    (assoc (dissoc aggregate :error) :pending action)))

(defn- started
  [aggregate {:keys [attrs]}]
  (assoc (dissoc aggregate :pending :stopped-at) :last-activity-at (:at attrs)))

(defn- stopped
  [aggregate {:keys [attrs]}]
  (assoc (dissoc aggregate :pending) :stopped-at (:at attrs)))

(defn- locked
  [aggregate {:keys [attrs]}]
  (assoc aggregate
         :locked-by (:by attrs)
         :locked-at (:at attrs)
         :pending (if (= :delete (:pending aggregate)) :delete :stop)))

(defn- deleted
  [aggregate {:keys [attrs]}]
  (assoc (dissoc aggregate :pending) :deleted-at (:at attrs)))

(defn- touched
  [aggregate {:keys [attrs]}]
  (assoc aggregate :last-activity-at (:at attrs)))

(defn- fork-prepared
  [aggregate _event]
  (dissoc aggregate :fork-pending))

(defn- failed
  [aggregate {:keys [attrs]}]
  (assoc (dissoc aggregate :pending :fork-pending) :error (:error attrs)))

(defn- idle-stop-requested
  [aggregate {:keys [attrs]}]
  (assoc aggregate :kind :session-reaper :tenant (:tenant attrs) :requested-at (:at attrs) :requested-by (:by attrs)))

(def record-keys
  "What the UI sees of a session record."
  [:id :sub :email :name :description :visibility :labels :image :tenant :team-id :project :run-id :activity
   :locked-by :locked-at :forked-from :created-at :last-activity-at :stopped-at])

(def row-keys
  (into record-keys [:pending :fork-pending :error]))

(defn directories
  "Identities of the directories listing a session: its team's and, when a
   person owns it, that person's."
  [{:keys [sub tenant team-id]}]
  (cond-> [(directory/team tenant team-id)]
    (not (auth/platform-user? sub)) (conj (directory/user sub))))

(defn directory-rows
  [aggregate]
  (cond
    (session? aggregate)
    (let [row
          (assoc (select-keys aggregate row-keys) :version (:version aggregate))]
      (mapv #(directory/upsert % :sessions row) (directories aggregate)))

    (and (= :session (:kind aggregate)) (:deleted-at aggregate))
    (mapv #(directory/removal % :sessions (:id aggregate) (:version aggregate)) (directories aggregate))

    :else
    []))

(def event-handlers
  {:session-created created
   :session-updated updated
   :session-labelled labelled
   :session-start-requested (pending :start)
   :session-started started
   :session-stop-requested (pending :stop)
   :session-stopped stopped
   :session-locked locked
   :session-delete-requested (pending :delete)
   :session-deleted deleted
   :session-touched touched
   :session-fork-prepared fork-prepared
   :session-action-failed failed
   :idle-sessions-stop-requested idle-stop-requested})

(defn register
  [ctx]
  (directory/reg-rows
   (reduce (fn [acc [event-id handler]] (edd/reg-event acc event-id handler))
           ctx
           event-handlers)
   (disj (set (keys event-handlers)) :idle-sessions-stop-requested :session-touched)
   directory-rows))
