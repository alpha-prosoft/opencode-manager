(ns manager-svc.effects
  "Effects (commands from reg-event-fx and reg-fx, such as directory rows)
   travel in-process through the memory distributor: the router moves them
   from the response destination to the command destination, where edd
   handles them. Callers that read their own writes right after a command
   (the UI, workers) wait for them with settle!; callers that don't read
   their writes use client/command-no-settle."
  (:require [clojure.tools.logging :as log]))

(defonce broker
  (atom {}))

(def settle-timeout-ms 3000)

(defn- waiting?
  [state]
  (some (fn [[destination messages]]
          (and (seq messages) (not (.endsWith (str destination) ".dlq"))))
        (:distribution state)))

(defn settle!
  "Waits until every effect and event waiting in the broker was handled, at
   most settle-timeout-ms."
  []
  (let [deadline
        (+ (System/currentTimeMillis) settle-timeout-ms)]
    (loop []
      (when (waiting? @broker)
        (if (< (System/currentTimeMillis) deadline)
          (do
            (Thread/sleep 5)
            (recur))
          (log/warn "Effects did not settle within" settle-timeout-ms "ms"))))))
