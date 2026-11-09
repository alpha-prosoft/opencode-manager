(ns manager-svc.names
  "The shapes of the names the platform accepts, and its own name.")

(def username-pattern
  #"^[a-z0-9][a-z0-9._-]{0,63}$")

(def identifier-pattern
  "Tenant names, team ids and the ids of what they declare."
  #"^[a-z0-9][a-z0-9-]{0,62}$")

(def secret-name-pattern
  #"^[A-Z][A-Z0-9_]{0,63}$")

(def platform-user
  "The platform's own username: owner of what platform runs start."
  "platform")

(defn platform-user?
  [username]
  (= platform-user username))
