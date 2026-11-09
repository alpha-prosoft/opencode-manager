(ns manager-svc.runs.core
  "Domain entry point, see manager-svc.system for the contract."
  (:require [manager-svc.runs.executor :as executor]
            [manager-svc.runs.queries :as queries]
            [manager-svc.runs.run :as run]
            [manager-svc.runs.tools :as tools]
            [manager-svc.runs.triggers :as triggers])
  (:import [java.util.concurrent Executors ThreadFactory]))

(def executor-threads
  "Runs advanced at once; more wait for a free thread."
  8)

(defn register
  [ctx]
  (-> ctx
      (run/register)
      (queries/register)
      (triggers/register)))

(defn- daemon-threads
  []
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. ^Runnable runnable "run-executor")
        (.setDaemon true)))))

(defn components
  [_settings]
  {:runs/executor (Executors/newFixedThreadPool executor-threads (daemon-threads))})

(def routes
  [])

(def sandbox-routes
  tools/routes)

(def host-handlers
  [])

(defn workers
  [_system]
  [{:id :run-executor
    :interval-seconds 10
    :initial-delay-seconds 5
    :reactive true
    :run executor/execute!}
   {:id :pull-request-triggers
    :interval-seconds 60
    :initial-delay-seconds 20
    :run #(triggers/run-due! % #{"pull_request"})}
   {:id :run-schedules
    :interval-seconds 60
    :initial-delay-seconds 30
    :run #(triggers/run-due! % #{"schedule"})}])
