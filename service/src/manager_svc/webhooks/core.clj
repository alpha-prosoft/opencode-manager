(ns manager-svc.webhooks.core
  "Domain entry point, see manager-svc.system for the contract. Inbound
   webhooks of a tenant, team or project that start a team workflow."
  (:require [manager-svc.webhooks.commands :as commands]
            [manager-svc.webhooks.edge :as edge]
            [manager-svc.webhooks.limiter :as limiter]
            [manager-svc.webhooks.queries :as queries]
            [manager-svc.webhooks.secrets :as secrets]))

(defn register
  [ctx]
  (queries/register (commands/register ctx)))

(defn components
  [_settings]
  {:webhooks/limits (limiter/limits)
   :webhooks/proxy-lookups (atom {})
   :webhooks/test-token (secrets/generate)})

(def routes
  edge/routes)

(def sandbox-routes
  [])

(def host-handlers
  [])

(defn workers
  [_system]
  [])
