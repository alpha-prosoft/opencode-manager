(ns manager-svc.effects
  "Effects (commands from reg-event-fx and reg-fx, such as directory rows)
   and events are delivered to the service itself, without a router service:
   with the Postgres store from the outbox of edd.postgres.distributor,
   with the memory store through the memory distributor and a router running
   in-process. Callers that read their own writes right after a command (the
   UI, workers) wait for them with settle!; callers that don't read their
   writes use client/command-no-settle."
  (:require [clojure.tools.logging :as log]
            [edd.core :as edd]
            [edd.ctx :as edd-ctx]
            [edd.distribution :as distribution]
            [edd.distribution.memory :as memory]
            [edd.distribution.router :as router]
            [edd.postgres.distributor :as postgres-distributor]
            [lambda.util :as util]))

(defonce broker
  (atom {}))

(defonce ^:private waiting?
  (atom (constantly false)))

(defonce ^:private stop-router
  (atom nil))

(def settle-timeout-ms 3000)

(def ^:private settle-poll-ms 10)

(defn- broker-waiting?
  []
  (some (fn [[destination messages]]
          (and (seq messages) (not (.endsWith (str destination) ".dlq"))))
        (:distribution @broker)))

(defn- start-router!
  "Effects and events go to the router destination; routing them in-process
   moves them to this service's command and event destinations."
  [ctx]
  (reset! stop-router
          (distribution/start-consumer!
           ctx
           (distribution/response-destination ctx)
           (fn [record]
             (router/handle-record ctx (util/to-edn (:body record)))
             true)))
  ctx)

(defn register-memory
  [ctx]
  (reset! waiting? broker-waiting?)
  (edd/reg-feature (memory/register ctx broker)
                   :manager/in-process-router
                   :init start-router!))

(defn register-postgres
  [ctx]
  (let [registered
        (postgres-distributor/register ctx)]
    (reset! waiting? (fn []
                       (some #(postgres-distributor/pending? registered %)
                             (edd-ctx/realms registered))))
    registered))

(defn stop!
  []
  (when-let [stop @stop-router]
    (stop)
    (reset! stop-router nil)))

(defn settle!
  "Waits until every effect and event waiting for delivery was handled, at
   most settle-timeout-ms."
  []
  (let [deadline
        (+ (System/currentTimeMillis) settle-timeout-ms)]
    (loop []
      (when (@waiting?)
        (if (< (System/currentTimeMillis) deadline)
          (do
            (Thread/sleep (long settle-poll-ms))
            (recur))
          (log/warn "Effects did not settle within" settle-timeout-ms "ms"))))))
