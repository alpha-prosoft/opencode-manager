(ns manager-svc.observability.access
  "Who may see a session's activity, audit trail and usage: whoever may see
   the session (sessions.api/can-view?)."
  (:require [manager-svc.auth :as auth]
            [manager-svc.errors :as errors]
            [manager-svc.roles :as roles]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.system :as system]))

(defn visible-session!
  "The session when the caller may see it; otherwise 404 as for a missing
   one, which is what another tenant's session is."
  [ctx session-id]
  (let [system
        (system/of ctx)

        session
        (sessions/get-session system session-id)]
    (if (and session
             (or (auth/platform? ctx)
                 (and (roles/same-tenant? (roles/caller ctx) (:tenant session))
                      (sessions/can-view? system session (roles/caller ctx)))))
      session
      (errors/not-found! "Session not found"))))
