(ns manager-svc.sessions.views
  "What the UI sees of a session (Session in manager/ui/src/api.ts)."
  (:require [manager-svc.sessions.access :as access]
            [manager-svc.sessions.aggregate :as aggregate]
            [manager-svc.sessions.links :as links]
            [manager-svc.sessions.live :as live]))

(defn session-view
  "The session as who (a caller map or an edge identity) sees it."
  [system statuses session who]
  (let [viewer
        (access/viewer who)

        blocked
        (access/open-blocked system session viewer)]
    (merge (zipmap aggregate/record-keys (repeat nil))
           {:labels {} :description ""}
           (select-keys session aggregate/record-keys)
           {:last-activity-at (live/last-activity system session)
            :status (live/status statuses session)
            :url (links/session-url (:settings system) (:id session))
            :open-url (links/entry-path session)
            :is-owner (= (:username viewer) (:sub session))
            :can-manage (access/can-manage? system session viewer)
            :can-open (nil? blocked)
            :open-blocked blocked
            :personal (access/personal? system session)
            :team-session (access/platform-owned? session)})))
