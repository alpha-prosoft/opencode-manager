(ns manager-svc.runs.edd
  "Reading a run from outside edd (workers, the /mcp route)."
  (:require [manager-svc.client :as client]))

(defn run-state
  "The run aggregate, or nil. Read wrapped in {:run}: edd answers a query
   result holding :error (a failed run) as an error."
  [system run-id]
  (:run (client/query (:settings system) {:query-id :get-run-source :run-id run-id})))
