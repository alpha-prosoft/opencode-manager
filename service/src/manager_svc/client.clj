(ns manager-svc.client
  "Sends requests to the edd service of the process, over HTTP on its
   loopback listener (manager-svc.main): the public listener as the person
   its token belongs to, workers and facades with the platform's service
   token.

   A failed call throws ex-info with {:status :message}: edd answers a
   handler's exception with :exception and a rejected request with :error."
  (:require [clojure.tools.logging :as log]
            [lambda.util :as util]
            [manager-svc.effects :as effects]
            [manager-svc.http :as http]
            [manager-svc.names :as names]
            [manager-svc.signing :as signing]
            [manager-svc.tokens :as tokens])
  (:import [java.util UUID]))

(def platform-meta
  {:realm :main
   :user {:id names/platform-user :role :non-interactive}})

(def edd-host
  "127.0.0.1")

(def ^:private internal-error
  {:status 500
   :body "{\"exception\":\"Internal error\"}"})

(defn request-raw
  "Sends a JSON edd request as the one token belongs to and returns
   {:status :body} with the body as text. What edd doesn't answer itself is
   logged and answered as an internal error."
  [settings path token body]
  (try
    (let [response
          (http/request! {:method :post
                          :url (str "http://" edd-host ":" (:edd-port settings) path)
                          :headers {"X-Authorization" (str token)
                                    "Content-Type" "application/json"}
                          :body body})]
      (select-keys response [:status :body]))
    (catch Exception e
      (log/error e "edd request failed" path)
      internal-error)))

(defn envelope
  "An edd request of one :query or :commands, with fresh ids."
  [request]
  (merge {:request-id (UUID/randomUUID)
          :interaction-id (UUID/randomUUID)}
         (select-keys request [:query :commands :meta])))

(defn failure
  "{:status :message} of a failed edd response, nil when it succeeded. Only
   a domain error's message and status (manager-svc.errors) reach the
   caller; anything else is an internal error."
  [response]
  (let [raised
        (or (:exception response) (:error response))

        problem
        (if (map? (:message raised)) (:message raised) raised)

        message
        (cond
          (string? (:message problem)) (:message problem)
          (string? problem) problem
          :else nil)

        status
        (or (:status problem)
            (when (= "Forbidden" message) 403))]
    (cond
      (nil? raised) nil
      (and (int? status) message) {:status status :message message}
      (or (:error response) (and (map? problem) (nil? message))) {:status 400 :message (or message "Invalid request")}
      :else {:status 500 :message "Internal error"})))

(defn- call
  "The result of an edd request run with token; throws on failure."
  [settings path token request]
  (let [{:keys [body]}
        (request-raw settings path token (util/to-json (envelope request)))

        response
        (util/to-edn body)]
    (if-let [{:keys [status message]}
             (failure response)]
      (throw (ex-info message {:status status :message message}))
      (:result response))))

(defonce ^:private held-service-token
  (atom nil))

(defn- current
  "held while it was signed with signing-key and less than half its life is
   over, else a new service token."
  [held settings signing-key now]
  (if (and (= signing-key (:signing-key held)) (< now (:renew-at held)))
    held
    {:token (tokens/service-token settings)
     :signing-key signing-key
     :renew-at (+ now (quot tokens/service-ttl-seconds 2))}))

(defn- service-token
  "The platform's token for edd: one is held and renewed before it expires."
  [settings]
  (:token (swap! held-service-token current settings (signing/signing-key settings) (tokens/now-seconds))))

(defn query
  "Runs a query as the platform and returns its result."
  [settings query]
  (call settings "/query" (service-token settings) {:query query :meta platform-meta}))

(defn command-no-settle
  "Runs commands as the platform without waiting for their effects, for
   callers that don't read their own writes."
  [settings & commands]
  (call settings "/command" (service-token settings) {:commands (vec commands) :meta platform-meta}))

(defn command
  "Runs commands as the platform and waits for their effects."
  [settings & commands]
  (let [result
        (apply command-no-settle settings commands)]
    (effects/settle!)
    result))

(defn query-as
  "Runs a query as the person token belongs to."
  [settings token query]
  (call settings "/query" token {:query query}))
