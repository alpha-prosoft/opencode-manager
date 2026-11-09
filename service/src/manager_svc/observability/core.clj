(ns manager-svc.observability.core
  "Domain entry point, see manager-svc.system for the contract.

   LLM gateway, usage metering, telemetry, audit trail and metrics. Usage,
   telemetry and audit records are events on per-session aggregates (ids
   named usage:/telemetry:/audit:<session-id>) whose state holds only running
   totals; detail rows are read back from the event store by event-seq."
  (:require [manager-svc.observability.audit :as audit]
            [manager-svc.observability.collector :as collector]
            [manager-svc.observability.edge :as edge]
            [manager-svc.observability.metrics :as metrics]
            [manager-svc.observability.telemetry :as telemetry]
            [manager-svc.observability.usage :as usage]))

(defn register
  [ctx]
  (-> ctx
      usage/register
      audit/register
      telemetry/register
      metrics/register))

(defn components
  [_settings]
  {:observability/followers (atom {})})

(def routes
  edge/routes)

(def sandbox-routes
  edge/sandbox-routes)

(def host-handlers
  [])

(defn workers
  [system]
  [(collector/job system)])
