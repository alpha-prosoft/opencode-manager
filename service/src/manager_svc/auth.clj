(ns manager-svc.auth
  "Who an edd request runs as. A request (manager-svc.client) carries a
   token the manager issued itself in X-Authorization: the service token is
   the platform (:non-interactive), a platform token a person (:user) with
   the identity it carries on [:meta :user]. edd never sees an identity
   provider's token (manager-svc.signin). The username \"platform\" is the
   platform itself and is never a person."
  (:require [clojure.string :as string]
            [lambda.http :as http]
            [manager-svc.names :as names]
            [manager-svc.tokens :as tokens]))

(def platform-user
  names/platform-user)

(def platform-user?
  names/platform-user?)

(def ^:private platform
  {:id platform-user
   :email "platform@internal"
   :role :non-interactive
   :roles [:non-interactive]
   :realm :main})

(defn- person
  [identity]
  (merge (select-keys identity [:email :name :tenant :teams :tenant-admin :operator])
         {:id (:sub identity)
          :role :user
          :roles [:user]
          :realm :main}))

(defn edd-user
  "The edd user of a token of the manager: the platform itself or a person;
   nil for any other token."
  [settings token]
  (if (tokens/service-token? settings token)
    platform
    (some-> (tokens/person settings token) person)))

(defn- header
  [req header-name]
  (some (fn [[k v]] (when (= header-name (string/lower-case (name k))) v))
        (:headers req)))

(defn resolve-user
  [settings]
  (fn [{:keys [req]}]
    (or (edd-user settings (header req "x-authorization"))
        (throw (ex-info "Not authenticated" {:status 401 :message "Not authenticated"})))))

(defn authenticate
  "Runtime filter: an HTTP request to edd runs as the person of its token."
  [settings]
  (http/authenticate (resolve-user settings)))

(defn user-id
  "Username of the person behind an edd request."
  [ctx]
  (get-in ctx [:meta :user :id]))

(defn platform?
  [ctx]
  (= :non-interactive (get-in ctx [:meta :user :role])))
