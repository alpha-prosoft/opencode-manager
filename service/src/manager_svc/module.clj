(ns manager-svc.module
  "The domains of the service, defined once for edd registration and for the
   system map, edge listeners and workers (contract in manager-svc.system)."
  (:require [manager-svc.credentials.core]
            [manager-svc.directory :as directory]
            [manager-svc.edge.api :as edge-api]
            [manager-svc.edge.static :as static]
            [manager-svc.observability.core]
            [manager-svc.platform.core]
            [manager-svc.runs.core]
            [manager-svc.sessions.core]
            [manager-svc.webhooks.core]))

(defn- entry
  [ns-sym]
  (let [public
        (fn [var-name]
          (some-> (ns-resolve ns-sym var-name) deref))]
    {:register (public 'register)
     :components (public 'components)
     :routes (public 'routes)
     :sandbox-routes (public 'sandbox-routes)
     :host-handlers (public 'host-handlers)
     :workers (public 'workers)}))

(def domains
  (mapv entry ['manager-svc.platform.core
               'manager-svc.sessions.core
               'manager-svc.runs.core
               'manager-svc.credentials.core
               'manager-svc.observability.core
               'manager-svc.webhooks.core]))

(defn register
  [ctx]
  (reduce (fn [acc register-domain] (register-domain acc))
          (directory/register ctx)
          (keep :register domains)))

(defn build
  "The system map: settings and every domain's components."
  [settings]
  (reduce (fn [system components] (merge system (components settings)))
          {:settings settings}
          (keep :components domains)))

(defn routes
  []
  (vec (concat edge-api/routes
               (mapcat :routes domains)
               static/routes)))

(defn sandbox-routes
  []
  (vec (mapcat :sandbox-routes domains)))

(defn host-handlers
  []
  (vec (mapcat :host-handlers domains)))

(defn workers
  [system]
  (vec (mapcat #(% system) (keep :workers domains))))
