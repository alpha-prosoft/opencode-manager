(ns manager-svc.service
  (:require [edd.distribution.memory :as distributor]
            [edd.memory.event-store :as memory-event-store]
            [edd.memory.view-store :as memory-view-store]
            [edd.postgres.event-store :as postgres-event-store]
            [edd.security :as security]
            [edd.view-store.postgres.view-store :as postgres-view-store]
            [lambda.test.fixture.state :as state]
            [manager-svc.auth :as auth]
            [manager-svc.effects :as effects]
            [manager-svc.migrations :as migrations]
            [manager-svc.module :as module]))

(def service-name :manager-svc)

(defn- register-stores
  [ctx {:keys [store]}]
  (if (= "postgres" store)
    (do
      (migrations/migrate! ctx)
      (postgres-view-store/register (postgres-event-store/register ctx)))
    (do
      (alter-var-root #'state/*dal-state* (constantly (atom {})))
      (memory-view-store/register (memory-event-store/register ctx)))))

(defn register
  "The domain with its authorization; what tests register on mock/ctx."
  [ctx]
  (security/register (module/register ctx)))

(defn init
  "Service ctx: in-process distribution, stores, authentication and the domain.
   :manager/system is kept on ctx for handlers."
  [ctx {:keys [settings] :as system}]
  (let [broker
        effects/broker

        named
        (assoc ctx
               :service-name service-name
               :hosted-zone-name (:domain settings)
               :environment-name-lower "local"
               :manager/system system
               :manager/broker broker
               :auth {:default-realm :main})

        with-distributor
        (distributor/register named broker)

        with-stores
        (register-stores with-distributor settings)

        with-auth
        (auth/register with-stores settings)]
    (register with-auth)))
