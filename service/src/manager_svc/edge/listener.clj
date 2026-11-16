(ns manager-svc.edge.listener
  "What a listener answers. A listener is data,

     {:routes [...] :host-handlers [...] :origin \"https://manager.example\"}

   and so are its routes, which every domain contributes:

     {:method :get|:post|:put|:delete|:any
      :path   \"/exact\" | :prefix \"/starts-with/\"
      :match  (fn [request] boolean), optional extra condition
      :caller :person, optional: a person must be logged in
      :max-body-bytes n, optional, instead of the listener's limit
      :handler (fn [system request] response)}

   A request (manager-svc.edge.http) is answered in one order:

     1. a listener with :origin refuses state-changing requests that carry
        cookies unless they come from that origin (same-origin?);
     2. host handlers, (fn [system request] response-or-nil), for session
        subdomains; they get no :body;
     3. the first matching route, else 404;
     4. its :caller: the request gets the person's :identity and their
        platform :token (caller), or is answered 401, or 403 with the
        claims parser's reason;
     5. its body limit, then the handler, which reads the body.

   A handler refuses by throwing {:status :message} (manager-svc.errors),
   answered as that status with {:detail message}; anything else thrown is
   logged and answered 500 without its text."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.cookies :as cookies]
            [manager-svc.edge.http :as edge-http]
            [manager-svc.errors :as errors]
            [manager-svc.jwt :as jwt]
            [manager-svc.tokens :as tokens])
  (:import [io.undertow.server HttpServerExchange RequestTooBigException]))

(set! *warn-on-reflection* true)

(def read-timeout-ms 60000)

(def safe-methods
  #{:get :head :options})

(def csrf-header
  "Header the UI sends with every state-changing request; a page of another
   origin can't add it without a CORS preflight, which the edge never allows."
  "x-requested-with")

(defn same-origin?
  "True when a request comes from origin: the browser says so (Sec-Fetch-Site
   or Origin), or it carries the UI's custom header."
  [origin {:keys [headers]}]
  (or (some? (get headers csrf-header))
      (= "same-origin" (get headers "sec-fetch-site"))
      (= origin (get headers "origin"))))

(defn csrf-refused?
  "True for a state-changing request with cookies from another origin."
  [origin {:keys [method headers] :as request}]
  (and origin
       (not (contains? safe-methods method))
       (not (string/blank? (get headers "cookie")))
       (not (same-origin? origin request))))

(defn- matches?
  [{:keys [method path prefix match]} request]
  (and (or (nil? method) (= :any method) (= method (:method request)))
       (cond
         path (= path (:path request))
         prefix (string/starts-with? (:path request) prefix)
         :else false)
       (or (nil? match) (match request))))

(defn route
  [routes request]
  (first (filter #(matches? % request) routes)))

(defn caller
  "Who a request comes from: {:identity :token} of the platform token in
   its login cookie, else in jwt mode of the identity provider token it
   carries (bearer or the provider's cookie), exchanged here;
   {:refused message} when the claims parser refuses that one; nil for
   anyone else."
  [settings cookies authorization]
  (let [token
        (get cookies (cookies/login-cookie settings))]
    (if-let [identity
             (tokens/person settings token)]
      {:identity identity
       :token token}
      (jwt/exchange settings (or (edge-http/bearer authorization)
                                 (get cookies (:jwt-cookie settings)))))))

(defn- person!
  [settings {:keys [cookies headers]}]
  (let [{:keys [identity refused] :as known}
        (caller settings cookies (get headers "authorization"))]
    (cond
      identity known
      refused (errors/forbidden! refused)
      :else (errors/fail! 401 "Not authenticated"))))

(defn- serve
  [{:keys [settings] :as system} {:keys [handler max-body-bytes] :as route} request]
  (let [known
        (if (:caller route)
          (merge request (person! settings request))
          request)]
    (handler system (edge-http/with-body known max-body-bytes))))

(defn answer
  "The response of a listener to request; refusals are thrown."
  [system {:keys [routes host-handlers origin]} request]
  (if (csrf-refused? origin request)
    (edge-http/error 403 "Cross-origin request refused")
    (or (some (fn [host-handler] (host-handler system request)) host-handlers)
        (if-let [found
                 (route routes request)]
          (serve system found request)
          (edge-http/error 404 "Not found")))))

(defn- too-big?
  [e]
  (some #(instance? RequestTooBigException %) (take-while some? (iterate ex-cause e))))

(defn refusal
  "The response to a request that failed with e."
  [request e]
  (let [{:keys [status message]}
        (ex-data e)]
    (cond
      (too-big? e)
      (edge-http/error 413 "Request body too large")

      status
      (edge-http/error status (or message "Request failed"))

      :else
      (do
        (log/error e "Edge request failed" (:path request))
        (edge-http/error 500 "Internal error")))))

(defn handler
  "The listener as a handler of an edd Undertow listener."
  [system listener]
  (fn [_runtime ^HttpServerExchange exchange]
    (let [request
          (edge-http/request exchange)]
      (try
        (edge-http/respond! exchange (answer system listener request))
        (catch Exception e
          (if (.isResponseStarted exchange)
            (log/warn "Edge response failed" (:path request) (ex-message e))
            (edge-http/respond! exchange (refusal request e))))))))

(defn limits
  "Undertow options of a listener: the body limit and the time to read a
   request."
  [settings]
  {:max-entity-size (:max-body-bytes settings)
   :request-parse-timeout read-timeout-ms
   :no-request-timeout read-timeout-ms
   :read-timeout read-timeout-ms})
