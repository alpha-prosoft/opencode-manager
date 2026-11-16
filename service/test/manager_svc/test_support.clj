(ns manager-svc.test-support
  "Helpers for domain tests on edd's mock DAL."
  (:require [edd.test.fixture.dal :as mock]
            [manager-svc.config :as config]
            [manager-svc.directory :as directory]
            [manager-svc.tokens :as tokens])
  (:import [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]
           [java.util Date]))

(def settings
  (assoc (config/load-settings {})
         :data-dir "target/test-data"
         :auth-mode "login"
         :dev-users-file "test/dev-users.edn"
         :secret-key "test-secret"
         :credentials-key "dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktMTIzNDU="))

(defn platform-token
  "The platform token of identity, as a login issues it."
  ([identity]
   (platform-token settings identity))
  ([token-settings identity]
   (tokens/platform-token token-settings identity 3600)))

(defn platform-tokens-in
  "The platform tokens anywhere in data, such as a request the manager sends
   out: whatever in its text reads as a person's token."
  ([data]
   (platform-tokens-in settings data))
  ([token-settings data]
   (filterv #(tokens/person token-settings %)
            (re-seq #"[\w-]+\.[\w-]+\.[\w-]+" (pr-str data)))))

(defn provider-token
  "A token of the identity provider https://idp.example for the audience
   manager, signed with secret and good for ten minutes; claims with string
   keys replace those."
  [secret claims]
  (-> (JWT/create)
      (.withIssuer "https://idp.example")
      (.withAudience (into-array String ["manager"]))
      (.withExpiresAt (Date/from (.plusSeconds (Instant/now) 600)))
      (.withPayload ^java.util.Map claims)
      (.sign (Algorithm/HMAC256 ^String secret))))

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
