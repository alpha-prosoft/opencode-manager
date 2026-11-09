(ns manager-svc.errors
  "Domain errors. Thrown from a command or query they reach the UI as
   {:exception {:message ... :status ...}}; from an edge handler they become
   the HTTP status with {:detail message}.")

(defn fail!
  ([status message]
   (fail! status message {}))
  ([status message data]
   (throw (ex-info message (merge data {:status status :message message})))))

(defn not-found!
  [message]
  (fail! 404 message))

(defn forbidden!
  [message]
  (fail! 403 message))

(defn invalid!
  [message]
  (fail! 422 message))
