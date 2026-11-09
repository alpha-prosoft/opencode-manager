(ns manager-svc.credentials.edge
  "PUT /api/credentials/{id} (the caller's own value) and
   PUT /api/admin/credentials/{id} (the platform value). The plaintext is
   sealed here, bound to the credential and its owner, so edd and its
   request log only ever see the sealed value. edd opens it to check it
   against its service and to authorize the change, as for any command."
  (:require [clojure.string :as string]
            [lambda.util :as util]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.vault :as vault]
            [manager-svc.edge.api :as edge-api]
            [manager-svc.edge.server :as server]
            [manager-svc.errors :as errors]))

(def max-body-chars 65536)

(def ^:private scopes
  {:user {:pattern #"^/api/credentials/([A-Za-z0-9._-]{1,128})$"
          :cmd-id :set-my-credential}
   :platform {:pattern #"^/api/admin/credentials/([A-Za-z0-9._-]{1,128})$"
              :cmd-id :set-platform-credential}})

(defn- credential-id
  "The id in a path Undertow already decoded."
  [scope path]
  (second (re-find (get-in scopes [scope :pattern]) path)))

(defn- read-value
  [request]
  (let [text
        (server/body-text request)

        parsed
        (when (and (not (string/blank? text)) (<= (count text) max-body-chars))
          (util/to-edn text))]
    (when-not (map? parsed)
      (errors/invalid! "Send {\"secret\": ..., \"username\": ...}"))
    {:secret (str (:secret parsed))
     :username (some-> (:username parsed) str)}))

(defn sealed-command
  "The command that stores value for username, carrying only the sealed value."
  [settings scope credential-id username value]
  (let [owner
        (if (= :platform scope) vault/platform-owner username)]
    {:cmd-id (get-in scopes [scope :cmd-id])
     :attrs {:credential-id credential-id
             :sealed (vault/seal settings credential-id owner value)}}))

(defn- set-value
  [scope]
  (fn [{:keys [settings]} request]
    (let [token
          (or (edge-api/caller-token settings request)
              (errors/fail! 401 "Not authenticated"))

          username
          (:sub (auth/person settings token))

          id
          (credential-id scope (:path request))

          command
          (sealed-command settings scope id username (read-value request))

          response
          (edge-api/send! settings "/command" token {:commands [command]})]
      (edge-api/command-done!)
      response)))

(def routes
  [{:method :put :prefix "/api/credentials/" :match #(credential-id :user (:path %)) :handler (set-value :user)}
   {:method :put :prefix "/api/admin/credentials/" :match #(credential-id :platform (:path %)) :handler (set-value :platform)}])
