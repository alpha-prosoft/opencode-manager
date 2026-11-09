(ns manager-svc.client
  "Calls the edd service over its internal HTTP port: the edge forwards the
   person's token, workers use the platform's service token.

   A failed call throws ex-info with {:status :message}: edd answers a
   handler's exception with :exception and a rejected request with :error."
  (:require [lambda.util :as util]
            [manager-svc.auth :as auth]
            [manager-svc.effects :as effects])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util UUID]))

(def ^:private http
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 5))
             (.build))))

(def platform-meta
  {:realm :main
   :user {:id auth/platform-user :role :non-interactive}})

(defn- edd-url
  [settings path]
  (str "http://127.0.0.1:" (:edd-port settings) path))

(defn post-raw
  "POSTs a JSON body to edd and returns {:status :body} with the body as text."
  [settings path token body]
  (let [request
        (-> (HttpRequest/newBuilder (URI/create (edd-url settings path)))
            (.timeout (Duration/ofSeconds 60))
            (.header "Content-Type" "application/json")
            (.header "X-Authorization" (str token))
            (.POST (HttpRequest$BodyPublishers/ofString body))
            (.build))

        response
        (.send ^HttpClient @http request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

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

(defn call
  "The result of an edd request sent with token; throws on failure."
  [settings path token request]
  (let [{:keys [body]}
        (post-raw settings path token (util/to-json (envelope request)))

        response
        (util/to-edn body)]
    (if-let [{:keys [status message]}
             (failure response)]
      (throw (ex-info message {:status status :message message}))
      (:result response))))

(defn query
  "Runs a query as the platform and returns its result."
  [settings query]
  (call settings "/query" (auth/service-token settings) {:query query :meta platform-meta}))

(defn command-no-settle
  "Runs commands as the platform without waiting for their effects, for
   callers that don't read their own writes."
  [settings & commands]
  (call settings "/command" (auth/service-token settings) {:commands (vec commands) :meta platform-meta}))

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
