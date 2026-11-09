(ns manager-svc.main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [edd.core :as edd]
            [edd.ctx :as edd-ctx]
            [edd.distribution :as distribution]
            [edd.distribution.router :as router]
            [edd.undertow.core :as undertow]
            [lambda.ctx :as lambda-ctx]
            [lambda.util :as util]
            [manager-svc.auth :as auth]
            [manager-svc.config :as config]
            [manager-svc.edge.server :as edge]
            [manager-svc.module :as module]
            [manager-svc.service :as service]
            [manager-svc.workers :as workers])
  (:import [java.util.concurrent ExecutorService]))

(set! *warn-on-reflection* true)

(defn- start-router!
  "Effects and events go to the router destination; routing them in-process
   moves them to this service's command and event destinations."
  [ctx]
  (distribution/start-consumer!
   ctx
   (distribution/response-destination ctx)
   (fn [record]
     (router/handle-record ctx (util/to-edn (:body record)))
     true)))

(defn public-listener
  "People and Traefik: the UI, its API, login and session forward-auth."
  [settings]
  {:name :public
   :routes (module/routes)
   :host-handlers (module/host-handlers)
   :host (:public-bind-host settings)
   :port (:public-port-internal settings)
   :max-body-bytes (:max-body-bytes settings)
   :origin (config/public-url settings (:domain settings))})

(defn sandbox-listener
  "Sandboxes: the LLM and MCP gateways, result tools and telemetry, nothing else."
  [settings]
  {:name :sandbox
   :routes (module/sandbox-routes)
   :host (:sandbox-bind-host settings)
   :port (:sandbox-port settings)
   :max-body-bytes (:max-body-bytes settings)})

(defn start
  [settings & {:keys [workers?] :or {workers? true}}]
  (auth/check-settings! settings)
  (let [system
        (module/build settings)

        ctx
        (service/init {} system)

        runtime
        (undertow/start ctx edd/handler
                        :port (:edd-port settings)
                        :host "127.0.0.1")

        stop-router
        (start-router! (edd-ctx/init-features (lambda-ctx/init ctx)))

        edge-servers
        [(edge/start system (public-listener settings))
         (edge/start system (sandbox-listener settings))]

        pool
        (when workers?
          (workers/start system (module/workers system)))]
    {:system system
     :runtime runtime
     :stop-router stop-router
     :edge edge-servers
     :workers pool}))

(defn stop
  "Stops jobs first, then every executor a domain owns, then the listeners."
  [{:keys [system runtime stop-router edge workers]}]
  (when workers (workers/stop workers))
  (doseq [component (vals system)
          :when (instance? ExecutorService component)]
    (.shutdownNow ^ExecutorService component))
  (run! edge/stop edge)
  (stop-router)
  (undertow/stop runtime))

(defn -main
  [& _args]
  (let [settings
        (config/load-settings)]
    (log/info "Starting manager" (select-keys settings [:backend :router :store :domain :auth-mode]))
    (start settings)))
