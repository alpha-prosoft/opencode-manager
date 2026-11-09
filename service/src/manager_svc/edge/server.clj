(ns manager-svc.edge.server
  "HTTP listeners of the edge. Routes are data from every domain:

     {:method :get|:post|:put|:delete|:any
      :path   \"/exact\" | :prefix \"/starts-with/\"
      :match  (fn [request] boolean), optional extra condition
      :max-body-bytes n, optional, above the listener's limit
      :handler (fn [system request] response)}

   request:  {:method :path :query :headers :cookies :host :body (InputStream) :exchange}
   response: {:status :headers :body} where body is a string, bytes, an
             InputStream, or (fn [OutputStream]) for streaming.

   Host handlers are functions (fn [system request] response-or-nil) that
   run before path routes, for session subdomains; they get no :body.

   Every listener limits request bodies and the time to read a request.
   A listener with :origin refuses state-changing requests that carry
   cookies unless they come from that origin (see same-origin?)."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [lambda.util :as util]
            [manager-svc.auth :as auth])
  (:import [io.undertow Undertow UndertowOptions]
           [io.undertow.server HttpHandler HttpServerExchange RequestTooBigException]
           [io.undertow.util HeaderValues HttpString]
           [java.io InputStream OutputStream]
           [java.nio.charset StandardCharsets]
           [java.util Deque Map$Entry]
           [org.xnio Options]))

(set! *warn-on-reflection* true)

(def read-timeout-ms 60000)

(def base-headers
  {"X-Content-Type-Options" "nosniff"
   "Referrer-Policy" "same-origin"})

(defn- headers->map
  [^HttpServerExchange exchange]
  (reduce
   (fn [acc ^HeaderValues values]
     (assoc acc
            (string/lower-case (str (.getHeaderName values)))
            (.getFirst values)))
   {}
   (.getRequestHeaders exchange)))

(defn- query->map
  [^HttpServerExchange exchange]
  (reduce
   (fn [acc ^Map$Entry entry]
     (assoc acc (keyword (.getKey entry)) (.peekFirst ^Deque (.getValue entry))))
   {}
   (.getQueryParameters exchange)))

(defn ->request
  "The request without its body: a route may raise the body limit before
   the body stream is opened."
  [^HttpServerExchange exchange]
  (let [headers
        (headers->map exchange)]
    {:method (keyword (string/lower-case (str (.getRequestMethod exchange))))
     :path (.getRequestPath exchange)
     :query-string (.getQueryString exchange)
     :query (query->map exchange)
     :headers headers
     :cookies (auth/parse-cookies (get headers "cookie"))
     :host (first (string/split (str (get headers "host")) #":"))
     :exchange exchange}))

(defn- with-body
  [request ^HttpServerExchange exchange]
  (assoc request :body (.getInputStream exchange)))

(defn- write-body!
  [^OutputStream out body]
  (cond
    (nil? body) nil
    (string? body) (.write out (.getBytes ^String body StandardCharsets/UTF_8))
    (bytes? body) (.write out ^bytes body)
    (instance? InputStream body) (with-open [in ^InputStream body] (io/copy in out))
    (fn? body) (body out)
    :else (.write out (.getBytes (str body) StandardCharsets/UTF_8))))

(defn write-response!
  [^HttpServerExchange exchange {:keys [status headers body]}]
  (.setStatusCode exchange (int (or status 200)))
  (let [response-headers
        (.getResponseHeaders exchange)]
    (doseq [[k v] (merge base-headers headers)]
      (if (sequential? v)
        (doseq [value v]
          (.add response-headers (HttpString. (name k)) (str value)))
        (.put response-headers (HttpString. (name k)) (str v)))))
  (with-open [out (.getOutputStream exchange)]
    (write-body! out body)))

(defn json
  ([body]
   (json 200 body))
  ([status body]
   {:status status
    :headers {"Content-Type" "application/json"}
    :body (util/to-json body)}))

(defn error
  [status message]
  (json status {:detail message}))

(defn body-text
  "The request body as text; the listener's size limit applies."
  [{:keys [body]}]
  (slurp body :encoding "UTF-8"))

(defn read-json
  [request]
  (let [text
        (body-text request)]
    (when-not (string/blank? text)
      (util/to-edn text))))

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

(defn- dispatch
  [system {:keys [routes host-handlers origin]} ^HttpServerExchange exchange request]
  (if (csrf-refused? origin request)
    (error 403 "Cross-origin request refused")
    (or (some (fn [host-handler] (host-handler system request)) host-handlers)
        (if-let [{:keys [handler max-body-bytes]}
                 (route routes request)]
          (do
            (when max-body-bytes
              (.setMaxEntitySize exchange (long max-body-bytes)))
            (handler system (with-body request exchange)))
          (error 404 "Not found")))))

(defn- too-big?
  [e]
  (some #(instance? RequestTooBigException %) (take-while some? (iterate ex-cause e))))

(defn- handle
  [system listener ^HttpServerExchange exchange]
  (try
    (write-response! exchange (dispatch system listener exchange (->request exchange)))
    (catch Exception e
      (let [{:keys [status message]}
            (ex-data e)]
        (cond
          (.isResponseStarted exchange)
          (log/warn "Edge response failed" (.getRequestPath exchange) (ex-message e))

          (too-big? e)
          (write-response! exchange (error 413 "Request body too large"))

          status
          (write-response! exchange (error status (or message "Request failed")))

          :else
          (do
            (log/error e "Edge request failed" (.getRequestPath exchange))
            (write-response! exchange (error 500 "Internal error"))))))))

(defn handler
  ^HttpHandler [system listener]
  (reify HttpHandler
    (handleRequest [this exchange]
      (if (.isInIoThread exchange)
        (.dispatch exchange ^HttpHandler this)
        (do
          (.startBlocking exchange)
          (handle system listener exchange))))))

(defn start
  "Starts a listener {:name :routes :host-handlers :port :host :max-body-bytes :origin}."
  [system {:keys [name routes port host max-body-bytes] :as listener}]
  (let [server
        (-> (Undertow/builder)
            (.addHttpListener (int port) ^String (or host "0.0.0.0"))
            (.setServerOption UndertowOptions/MAX_ENTITY_SIZE (long max-body-bytes))
            (.setServerOption UndertowOptions/REQUEST_PARSE_TIMEOUT (int read-timeout-ms))
            (.setServerOption UndertowOptions/NO_REQUEST_TIMEOUT (int read-timeout-ms))
            (.setSocketOption Options/READ_TIMEOUT (int read-timeout-ms))
            (.setHandler (handler system listener))
            (.build))]
    (.start server)
    (log/info "Edge listening" {:listener name :host host :port port :routes (count routes)})
    server))

(defn stop
  [^Undertow server]
  (.stop server))
