(ns manager-svc.test-support
  "Helpers for domain tests on edd's mock DAL."
  (:require [edd.test.fixture.dal :as mock]
            [manager-svc.config :as config]
            [manager-svc.directory :as directory])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def settings
  (assoc (config/load-settings {})
         :data-dir "target/test-data"
         :auth-mode "login"
         :dev-users-file "test/dev-users.edn"
         :secret-key "test-secret"
         :credentials-key "dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMTIzNDU="))

(defn ctx
  "mock/ctx with a system map and the directories; register the domain under
   test on it."
  ([]
   (ctx {}))
  ([system]
   (assoc (directory/register mock/ctx)
          :manager/system (merge {:settings settings} system)
          :auth {:default-realm :test})))

(def tenant
  "The tenant people of tests belong to unless they say otherwise."
  "acme")

(defn person
  "The [:meta :user] of a person: tenant acme, no teams unless opts say
   (:tenant :teams {team-id :admin|:member} :tenant-admin :operator)."
  ([username]
   (person username {}))
  ([username opts]
   (merge {:id username
           :email (str username "@users.local")
           :name username
           :tenant tenant
           :teams {}
           :tenant-admin false
           :operator false
           :role :user
           :roles [:user]}
          opts)))

(defn as-user
  ([ctx username]
   (as-user ctx username {}))
  ([ctx username opts]
   (assoc-in ctx [:meta :user] (person username opts))))

(defn as-platform
  [ctx]
  (assoc-in ctx [:meta :user] {:id "platform" :role :non-interactive :roles [:non-interactive]}))

(defn settle!
  "Runs the effects waiting in the mock DAL, and theirs, as the platform
   (effects carry the platform as their user)."
  [ctx]
  (mock/execute-fx-apply-all (as-platform ctx)))

(defn apply!
  "Runs a command, applies its events and runs its effects."
  [ctx cmd]
  (let [response
        (mock/apply-cmd ctx cmd)]
    (settle! ctx)
    response))

(defn temp-dir
  "A new empty directory (a File)."
  []
  (.toFile (Files/createTempDirectory "manager-test" (make-array FileAttribute 0))))
