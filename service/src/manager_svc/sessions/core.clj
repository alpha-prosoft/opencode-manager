(ns manager-svc.sessions.core
  "Domain entry point, see manager-svc.system for the contract."
  (:require [manager-svc.sessions.aggregate :as aggregate]
            [manager-svc.sessions.commands :as commands]
            [manager-svc.sessions.docker :as docker]
            [manager-svc.sessions.gateway :as gateway]
            [manager-svc.sessions.kubernetes :as kubernetes]
            [manager-svc.sessions.lifecycle :as lifecycle]
            [manager-svc.sessions.live :as live]
            [manager-svc.sessions.queries :as queries]
            [manager-svc.sessions.routing :as routing])
  (:import [java.util.concurrent Executors ThreadFactory]))

(defn register
  [ctx]
  (let [with-aggregate
        (aggregate/register ctx)

        with-commands
        (commands/register with-aggregate)]
    (queries/register with-commands)))

(defn create-backend
  [settings]
  (if (= "kubernetes" (:backend settings))
    (kubernetes/create settings)
    (docker/create settings)))

(defn- background-executor
  "Image pulls and activity writes, off request threads and the lock."
  []
  (Executors/newFixedThreadPool 2 (reify ThreadFactory
                                    (newThread [_ runnable]
                                      (doto (Thread. ^Runnable runnable "sessions-background")
                                        (.setDaemon true))))))

(defn components
  [settings]
  (let [backend
        (create-backend settings)]
    {:sessions/backend backend
     :sessions/routes (routing/create settings backend)
     :sessions/state (live/new-state)
     :sessions/lock (Object.)
     :sessions/background (background-executor)}))

(def routes
  gateway/routes)

(def host-handlers
  gateway/host-handlers)

(defn workers
  [system]
  (lifecycle/workers system))
