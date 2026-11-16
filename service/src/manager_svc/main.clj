(ns manager-svc.main
  (:gen-class)
  (:require [edd.core :as edd]
            [edd.undertow.core :as undertow]
            [manager-svc.auth :as auth]
            [manager-svc.client :as client]
            [manager-svc.config :as config]
            [manager-svc.edge.listener :as listener]
            [manager-svc.module :as module]
            [manager-svc.service :as service]
            [manager-svc.system :as system]
            [manager-svc.workers :as workers]))

(set! *warn-on-reflection* true)

(defn- public-listener
  "People and Traefik: the UI, its API, login and session forward-auth."
  [{:keys [settings] :as system}]
  (merge (listener/limits settings)
         {:host (:public-bind-host settings)
          :port (:public-port-internal settings)
          :handler (listener/handler system {:routes (module/routes)
                                             :host-handlers (module/host-handlers)
                                             :origin (config/public-url settings (:domain settings))})}))

(defn- sandbox-listener
  "Sandboxes: the LLM and MCP gateways, result tools and telemetry, nothing else."
  [{:keys [settings] :as system}]
  (merge (listener/limits settings)
         {:host (:sandbox-bind-host settings)
          :port (:sandbox-port settings)
          :handler (listener/handler system {:routes (module/sandbox-routes)})}))

(defn start
  "One edd runtime: edd answers on loopback only (manager-svc.client), its
   other listeners serve people and sandboxes."
  [ctx & opts]
  (let [{:keys [settings] :as system}
        (system/of ctx)]
    (apply undertow/start
           ctx
           edd/handler
           :filters [(auth/authenticate settings)]
           :listeners {:default {:host client/edd-host
                                 :port (:edd-port settings)}
                       :public (public-listener system)
                       :sandbox (sandbox-listener system)}
           opts)))

(defn -main
  [& _args]
  (let [ctx
        (service/init {})

        system
        (system/of ctx)]
    (start ctx)
    (workers/start system (module/workers system))))
