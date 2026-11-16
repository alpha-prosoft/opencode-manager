(ns manager-svc.service
  (:require [clojure.tools.logging :as log]
            [edd.flyway :as flyway]
            [edd.memory.event-store :as memory-event-store]
            [edd.memory.view-store :as memory-view-store]
            [edd.postgres.event-store :as postgres-event-store]
            [edd.security :as security]
            [edd.view-store.postgres.view-store :as postgres-view-store]
            [lambda.test.fixture.state :as state]
            [manager-svc.config :as config]
            [manager-svc.effects :as effects]
            [manager-svc.jwt :as jwt]
            [manager-svc.module :as module]
            [manager-svc.oidc :as oidc]
            [manager-svc.platform.tenants :as tenants]
            [manager-svc.signin :as signin]))

(def service-name :manager-svc)

(def realm :main)

(defn- register-postgres
  "The database is the service's own: edd migrates it at startup."
  [ctx]
  (postgres-view-store/register
   (postgres-event-store/register
    (effects/register-postgres
     (flyway/register ctx {:placeholders {"historyPartitionCount" "4"}})))))

(defn- register-memory
  [ctx]
  (alter-var-root #'state/*dal-state* (constantly (atom {})))
  (memory-view-store/register
   (memory-event-store/register
    (effects/register-memory ctx))))

(defn register
  "The domain with its authorization; what tests register on mock/ctx."
  [ctx]
  (security/register (module/register ctx)))

(defn- check-settings!
  [settings]
  (signin/check-settings! settings)
  (jwt/check-settings! settings)
  (oidc/check-settings! settings)
  (tenants/check-settings! settings))

(defn init
  "Service ctx: distribution to the service itself, stores and the domain.
   The system map of settings is kept on ctx as :manager/system."
  ([ctx]
   (init ctx (config/load-settings)))
  ([ctx settings]
   (check-settings! settings)
   (log/info "Starting manager" (select-keys settings [:backend :router :store :domain :auth-mode]))
   (let [named
         (assoc ctx
                :service-name service-name
                :hosted-zone-name (:domain settings)
                :environment-name-lower "local"
                :manager/system (module/build settings)
                :realms [realm]
                :auth {:default-realm realm})

         with-stores
         (if (= "postgres" (:store settings))
           (register-postgres named)
           (register-memory named))]
     (register with-stores))))
