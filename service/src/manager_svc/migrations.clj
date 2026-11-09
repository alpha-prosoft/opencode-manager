(ns manager-svc.migrations
  "Creates the Postgres schemas edd-core expects: the event store in schema
   <realm> and the view store in schema <realm>-<service>. The SQL is
   edd-core's own (sql/files/edd and edd-core-view-store-postgres/migrations)."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [edd.db :as db])
  (:import [org.flywaydb.core Flyway]))

(def realm "main")

(defn- jdbc-url
  [{:keys [host port dbname]}]
  (str "jdbc:postgresql://" host ":" port "/" dbname))

(defn- migrate-schema!
  [spec schema location]
  (let [result
        (-> (Flyway/configure)
            (.dataSource (jdbc-url spec) (:username spec) (:password spec))
            (.schemas (into-array String [schema]))
            (.locations (into-array String [location]))
            (.placeholders {"historyPartitionCount" "4"})
            (.load)
            (.migrate))]
    (log/info "Migrated" schema {:applied (.migrationsExecuted result)})))

(defn view-store-schema
  [service-name]
  (string/replace (str realm "-" (name service-name)) "-" "_"))

(defn migrate!
  [{:keys [service-name] :as ctx}]
  (let [spec
        (db/init ctx)]
    (migrate-schema! spec realm "classpath:migrations/event-store")
    (migrate-schema! spec (view-store-schema service-name) "classpath:migrations/view-store")))
