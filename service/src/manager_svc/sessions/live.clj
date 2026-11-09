(ns manager-svc.sessions.live
  "Live state of sandboxes, held in memory (:sessions/state): backend
   status, readiness, recent activity, images being pulled and short-lived
   caches of access decisions.

   Activity is recorded here on every request to a session host; the
   aggregate's :last-activity-at is only persisted every few minutes, so
   the last activity of a session is the later of the two."
  (:require [clojure.tools.logging :as log]
            [manager-svc.opencode :as opencode]
            [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.links :as links]
            [manager-svc.sessions.storage :as storage]
            [manager-svc.time :as time])
  (:import [java.time Instant]
           [java.util.concurrent ExecutorService]))

(def ready-ttl-ms 15000)
(def personal-ttl-ms 10000)
(def decision-ttl-ms 5000)
(def cache-limit 1000)

(defn new-state
  []
  (atom {:ready {} :activity {} :persisted {} :cache {} :pulling #{} :pull-errors {}}))

(defn- state
  [system]
  (:sessions/state system))

(defn- now-ms
  []
  (System/currentTimeMillis))

(defn backend-of
  [system]
  (:sessions/backend system))

(defn run-later!
  "Runs f on the sessions' background executor (inline without one), logging
   its failure."
  [system f]
  (let [task
        (fn []
          (try
            (f)
            (catch Exception e
              (log/warn e "Background session task failed"))))]
    (if-let [executor
             (:sessions/background system)]
      (.submit ^ExecutorService executor ^Runnable task)
      (task))))

(defn- fresh
  [entries ttl-ms now]
  (into {} (filter (fn [[_ [at]]] (< (- now at) ttl-ms))) entries))

(defn cached!
  "(compute) cached under k for ttl-ms; a nil result is not cached."
  [system k ttl-ms compute]
  (let [now
        (now-ms)

        [at value]
        (get-in @(state system) [:cache k])]
    (if (and at (< (- now at) ttl-ms))
      value
      (let [value
            (compute)]
        (when (some? value)
          (swap! (state system) update :cache
                 (fn [cache]
                   (assoc (if (> (count cache) cache-limit) (fresh cache ttl-ms now) cache)
                          k [now value]))))
        value))))

(defn status
  "Backend status, shown as :starting while a requested start is pending."
  [statuses session]
  (let [actual
        (get statuses (:id session) :stopped)]
    (if (and (= :start (:pending session)) (= :stopped actual))
      :starting
      actual)))

(defn known-ready?
  "True when the session's OpenCode answered within ready-ttl-ms."
  [system session-id]
  (when-let [at
             (get-in @(state system) [:ready session-id])]
    (< (- (now-ms) at) ready-ttl-ms)))

(defn forget-ready!
  [system session-id]
  (swap! (state system) update :ready dissoc session-id))

(defn keep-ready!
  "Forgets readiness of every session not in active-ids."
  [system active-ids]
  (swap! (state system) update :ready select-keys active-ids))

(defn ready?
  [system session-id]
  (or (known-ready? system session-id)
      (when (opencode/healthy? (backend/service-url (backend-of system) session-id)
                               (links/secret-key (:settings system) session-id))
        (swap! (state system) assoc-in [:ready session-id] (now-ms))
        true)
      false))

(defn touch!
  "Records activity of a session now."
  [system session-id]
  (swap! (state system) assoc-in [:activity session-id] (now-ms)))

(defn persist-due?
  "True at most once per period per session: when its activity should be
   written to the aggregate."
  [system session-id period-seconds]
  (let [now
        (now-ms)

        [before after]
        (swap-vals! (state system)
                    (fn [current]
                      (if (< (- now (get-in current [:persisted session-id] 0)) (* 1000 period-seconds))
                        current
                        (assoc-in current [:persisted session-id] now))))]
    (not= (get-in before [:persisted session-id])
          (get-in after [:persisted session-id]))))

(defn last-activity
  "The later of the session's persisted :last-activity-at and its activity
   recorded in memory, as an ISO-8601 string."
  [system {:keys [id last-activity-at]}]
  (let [recent
        (some-> (get-in @(state system) [:activity id]) Instant/ofEpochMilli str)]
    (cond
      (nil? recent) last-activity-at
      (nil? last-activity-at) recent
      (time/after? recent last-activity-at) recent
      :else last-activity-at)))

(defn image-available!
  "True when the backend has image. Otherwise starts pulling it in the
   background and returns false; a failed pull throws once."
  [system image]
  (let [backend
        (backend-of system)

        failure
        (get-in @(state system) [:pull-errors image])]
    (cond
      failure
      (do
        (swap! (state system) update :pull-errors dissoc image)
        (throw (ex-info (str "Could not pull image " image ": " failure) {})))

      (backend/image-ready? backend image)
      true

      :else
      (let [[before _]
            (swap-vals! (state system) update :pulling conj image)]
        (when-not (contains? (:pulling before) image)
          (run-later! system
                      (fn []
                        (try
                          (backend/pull-image! backend image)
                          (catch Exception e
                            (swap! (state system) assoc-in [:pull-errors image] (or (ex-message e) "failed")))
                          (finally
                            (swap! (state system) update :pulling disj image))))))
        false))))

(defn running-ids
  [system sessions]
  (let [statuses
        (backend/statuses (backend-of system))]
    (vec (for [{:keys [id]} sessions
               :when (= :running (get statuses id))]
           id))))

(defn done?
  "Idle past its grace period and no conversation busy."
  [system {:keys [id] :as session}]
  (let [settings
        (:settings system)

        sandbox
        (storage/load-sandbox settings id)

        grace
        (if sandbox
          (* 60 (or (:idle-minutes sandbox) 15))
          (:idle-grace-seconds settings))

        idle
        (or (time/seconds-since (last-activity system session)) Long/MAX_VALUE)]
    (and (>= idle grace)
         (try
           (zero? (opencode/busy-count (backend/service-url (backend-of system) id)
                                       (links/secret-key settings id)))
           (catch Exception e
             (log/warn "Could not read busy state of session" id (ex-message e))
             false)))))

(defn idle-ids
  [system sessions]
  (let [by-id
        (into {} (map (juxt :id identity)) sessions)]
    (vec (filter #(done? system (get by-id %)) (running-ids system sessions)))))
