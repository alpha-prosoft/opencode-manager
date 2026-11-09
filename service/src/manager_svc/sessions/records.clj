(ns manager-svc.sessions.records
  "Session aggregates, directory rows and commands from outside edd (edge,
   workers, facade), as the platform."
  (:require [manager-svc.client :as client]
            [manager-svc.sessions.links :as links]
            [manager-svc.sessions.live :as live]
            [manager-svc.workers :as workers]))

(def persist-activity-seconds 300)
(def lifecycle-job :sessions-lifecycle)

(defn one
  [system session-id]
  (when-let [id
             (links/parse-id session-id)]
    (:session (client/query (:settings system) {:query-id :get-session-record :session-id id}))))

(defn rows
  "Directory rows of every session, of a tenant's or of a team's."
  ([system]
   (client/query (:settings system) {:query-id :list-session-rows}))
  ([system tenant]
   (client/query (:settings system) {:query-id :list-session-rows :tenant tenant}))
  ([system tenant team-id]
   (client/query (:settings system) {:query-id :list-session-rows :tenant tenant :team-id team-id})))

(defn command!
  [system cmd-id session-id & [attrs]]
  (client/command (:settings system) (cond-> {:cmd-id cmd-id :id session-id}
                                       attrs (assoc :attrs attrs))))

(defn record!
  "A command whose effects nobody waits for: the lifecycle worker sends its
   outcomes with it while it holds the sessions lock."
  [system cmd-id session-id & [attrs]]
  (client/command-no-settle (:settings system) (cond-> {:cmd-id cmd-id :id session-id}
                                                 attrs (assoc :attrs attrs))))

(defn kick-lifecycle!
  []
  (workers/kick! lifecycle-job))

(defn touch!
  "Records activity so the idle reaper keeps the session. The aggregate is
   written in the background, at most every persist-activity-seconds."
  [system session-id]
  (live/touch! system session-id)
  (when (live/persist-due? system session-id persist-activity-seconds)
    (live/run-later! system
                     #(client/command-no-settle (:settings system) {:cmd-id :touch-session :id session-id}))))
