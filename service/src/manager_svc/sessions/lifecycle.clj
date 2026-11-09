(ns manager-svc.sessions.lifecycle
  "Workers that carry out what session aggregates ask for: prepare forks,
   start, stop and delete sandboxes, keep proxy routes in sync, and stop
   sessions that went idle.

   Directory rows tell which sessions have something pending; only those
   aggregates are read. Images are pulled in the background, never under
   the sessions lock: a start waits in :pending until its image is there."
  (:require [clojure.tools.logging :as log]
            [manager-svc.client :as client]
            [manager-svc.effects :as effects]
            [manager-svc.observability.api :as observability]
            [manager-svc.runs.api :as runs]
            [manager-svc.sessions.access :as access]
            [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.live :as live]
            [manager-svc.sessions.records :as records]
            [manager-svc.sessions.routing :as routing]
            [manager-svc.sessions.storage :as storage]))

(def route-resync-ms 60000)

(defn- audit-fork!
  [system {:keys [id forked-from sub]}]
  (access/guarded nil observability/append-audit! system forked-from "fork" (str "fork:" id)
                  {:forked-to (str id) :by sub}))

(defn prepare-fork!
  "Copies the source's files into the fork and writes the fork's own sandbox
   (its keys are derived from the fork's id), built from the source's workflow."
  [system {:keys [id forked-from] :as fork}]
  (let [settings
        (:settings system)

        source
        (or (records/one system forked-from)
            (throw (ex-info (str "The forked session " forked-from " no longer exists") {:session-id id})))

        sandbox
        (or (runs/fork-sandbox system source fork)
            (throw (ex-info "The workflow of the forked session is missing or invalid" {:session-id id})))]
    (storage/prepare! settings id)
    (storage/copy-session! settings forked-from id)
    (storage/write-sandbox! settings id sandbox)
    (records/record! system :record-session-fork-prepared id)
    (audit-fork! system fork)))

(defn- start!
  [system {:keys [id locked-at] :as session}]
  (let [sandbox
        (storage/load-sandbox (:settings system) id)

        backend
        (live/backend-of system)]
    (cond
      locked-at
      (throw (ex-info (str "Session " id " is locked") {}))

      (nil? sandbox)
      (throw (ex-info (str "Session " id " has no sandbox configuration") {}))

      (backend/active? backend id)
      (records/record! system :record-session-started id)

      (not (live/image-available! system (:image sandbox)))
      (log/info "Session waits for its image" id (:image sandbox))

      :else
      (do
        (log/info "Starting session" id)
        (live/forget-ready! system id)
        (backend/start! backend session sandbox)
        (records/record! system :record-session-started id)))))

(defn- stop!
  [system {:keys [id]}]
  (log/info "Stopping session" id)
  (live/forget-ready! system id)
  (backend/stop! (live/backend-of system) id)
  (records/record! system :record-session-stopped id))

(defn- delete!
  [system {:keys [id]}]
  (log/info "Deleting session" id)
  (live/forget-ready! system id)
  (backend/delete! (live/backend-of system) id)
  (storage/remove! (:settings system) id)
  (records/record! system :record-session-deleted id))

(def ^:private actions
  {:start start!
   :stop stop!
   :delete delete!})

(defn- attempt!
  [system session action f]
  (try
    (f system session)
    (catch Exception e
      (log/error e "Session action failed" (:id session) action)
      (try
        (records/record! system :record-session-failed (:id session)
                         {:error (or (ex-message e) "failed") :action action})
        (catch Exception record-error
          (log/error record-error "Could not record the failure of session" (:id session)))))))

(defn sync-routes!
  "Makes the proxy route the sessions of rows when their ids changed, and
   at least every route-resync-ms."
  [system rows]
  (let [ids
        (set (map :id rows))

        state
        (:sessions/state system)

        {:keys [synced-ids synced-at]}
        (:routes @state)

        now
        (System/currentTimeMillis)]
    (when (or (not= ids synced-ids)
              (nil? synced-at)
              (> (- now synced-at) route-resync-ms))
      (routing/sync-routes! (:sessions/routes system) (sort-by str (map (fn [id] {:id id}) ids)))
      (swap! state assoc :routes {:synced-ids ids :synced-at now}))))

(defn- pending?
  [row]
  (or (:pending row) (:fork-pending row)))

(defn- load-pending
  [system rows]
  (vec (keep #(records/one system (:id %)) (filter pending? rows))))

(defn- prepare-forks!
  [system rows]
  (doseq [fork (filter :fork-pending (load-pending system rows))]
    (attempt! system fork :fork prepare-fork!)))

(defn- carry-out!
  "Carries out pending actions; a fork prepared in this pass is read again,
   so it starts now."
  [system rows]
  (doseq [session (load-pending system rows)
          :let [action (:pending session)
                f (get actions action)]
          :when (and f (or (not (:fork-pending session)) (= :delete action)))]
    (attempt! system session action f)))

(defn reconcile!
  [system]
  (locking (:sessions/lock system)
    (let [rows
          (records/rows system)]
      (prepare-forks! system rows)
      (carry-out! system rows)))
  (effects/settle!)
  (locking (:sessions/lock system)
    (let [rows
          (records/rows system)]
      (live/keep-ready! system (live/running-ids system rows))
      (sync-routes! system rows))))

(defn reap!
  "Stops the idle sessions of every tenant, or of one."
  ([system]
   (reap! system nil))
  ([system tenant]
   (let [idle
         (live/idle-ids system (if tenant (records/rows system tenant) (records/rows system)))]
     (doseq [session-id idle]
       (log/info "Stopping idle session" session-id)
       (records/command! system :stop-session session-id))
     (when (seq idle)
       (reconcile! system))
     idle)))

(defn reap-requested!
  "Reaps the tenants whose administrators asked for it since the last time;
   the ids of the sessions stopped, or nil when nobody asked."
  [system]
  (let [{:keys [requests]}
        (client/query (:settings system) {:query-id :get-idle-stop-request})

        state
        (:sessions/state system)

        due
        (filter (fn [{:keys [tenant requested-at]}]
                  (not= requested-at (get-in @state [:reaped-request tenant])))
                requests)]
    (when (seq due)
      (vec (mapcat (fn [{:keys [tenant requested-at]}]
                     (let [idle
                           (reap! system tenant)]
                       (swap! state assoc-in [:reaped-request tenant] requested-at)
                       idle))
                   due)))))

(defn prepare-backend!
  [system]
  (backend/prepare! (live/backend-of system)))

(defn workers
  [system]
  (let [reap-interval
        (get-in system [:settings :reap-interval-seconds])]
    [{:id records/lifecycle-job
      :interval-seconds 10
      :initial-delay-seconds 1
      :reactive true
      :run reconcile!}
     {:id :sessions-reaper
      :interval-seconds reap-interval
      :initial-delay-seconds reap-interval
      :run (fn [system] (reap! system))}
     {:id :sessions-reap-requests
      :interval-seconds 60
      :initial-delay-seconds 15
      :reactive true
      :run reap-requested!}
     {:id :sessions-prepare-backend
      :interval-seconds (* 24 3600)
      :initial-delay-seconds 1
      :run prepare-backend!}]))
