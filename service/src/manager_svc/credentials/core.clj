(ns manager-svc.credentials.core
  "Domain entry point, see manager-svc.system for the contract."
  (:require [manager-svc.credentials.commands :as commands]
            [manager-svc.credentials.edge :as edge]
            [manager-svc.credentials.gateway :as gateway]
            [manager-svc.credentials.queries :as queries]))

(defn register
  [ctx]
  (queries/register (commands/register ctx)))

(defn components
  [_settings]
  {})

(def routes
  edge/routes)

(def sandbox-routes
  gateway/sandbox-routes)

(def host-handlers
  [])

(defn workers
  [_system]
  [])
