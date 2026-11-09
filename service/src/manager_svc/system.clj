(ns manager-svc.system
  "The process-wide system map handed to edd handlers (as :manager/system on
   ctx), edge handlers and workers. Every domain namespace
   manager-svc.<domain>.core provides (manager-svc.module lists them):

     (register ctx)         edd commands, events and queries
     (components settings)  stateful components merged into the system map,
                            keyed :<domain>/<name>
     routes                 public edge routes (manager-svc.edge.server)
     sandbox-routes         routes of the sandbox listener (gateways sandboxes call)
     host-handlers          public edge host handlers
     (workers system)       background jobs (manager-svc.workers)")

(defn of
  "The system map of an edd ctx."
  [ctx]
  (:manager/system ctx))

(defn settings
  [ctx]
  (get-in ctx [:manager/system :settings]))
