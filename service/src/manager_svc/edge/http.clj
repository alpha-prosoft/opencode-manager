(ns manager-svc.edge.http
  "Requests and responses of the edge as data, read from and written to the
   exchange of a listener.

   request:  {:method :path :query :query-string :headers :cookies :host
              :remote-addr :forwarded-for :body (InputStream)}
   response: {:status :headers :body} where body is a string, bytes, an
             InputStream, or (fn [OutputStream]) for streaming; a header
             value may be a list, sent as one line each."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [lambda.util :as util]
            [manager-svc.cookies :as cookies])
  (:import [io.undertow.server HttpServerExchange]
           [io.undertow.util HeaderMap HeaderValues HttpString]
           [java.io InputStream OutputStream]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util Deque Map$Entry]))

(set! *warn-on-reflection* true)

(def base-headers
  {"X-Content-Type-Options" "nosniff"
   "Referrer-Policy" "same-origin"})

(defn- headers->map
  [^HeaderMap headers]
  (reduce
   (fn [acc ^HeaderValues values]
     (assoc acc
            (string/lower-case (str (.getHeaderName values)))
            (.getFirst values)))
   {}
   headers))

(defn- query->map
  [^HttpServerExchange exchange]
  (reduce
   (fn [acc ^Map$Entry entry]
     (assoc acc (keyword (.getKey entry)) (.peekFirst ^Deque (.getValue entry))))
   {}
   (.getQueryParameters exchange)))

(defn- remote-addr
  [^HttpServerExchange exchange]
  (let [^InetSocketAddress address
        (.getSourceAddress exchange)]
    (some-> address .getAddress .getHostAddress)))

(defn- forwarded-for
  "Every X-Forwarded-For line, joined in order."
  [^HeaderMap headers]
  (when-let [^HeaderValues lines
             (.get headers "X-Forwarded-For")]
    (string/join "," lines)))

(defn bearer
  "The token of an Authorization: Bearer header value."
  [authorization]
  (when (and authorization (string/starts-with? (string/lower-case authorization) "bearer "))
    (not-empty (string/trim (subs authorization 7)))))

(defn request
  "The request of an exchange, without its body: see with-body."
  [^HttpServerExchange exchange]
  (let [headers
        (headers->map (.getRequestHeaders exchange))]
    {:method (keyword (string/lower-case (str (.getRequestMethod exchange))))
     :path (.getRequestPath exchange)
     :query-string (.getQueryString exchange)
     :query (query->map exchange)
     :headers headers
     :cookies (cookies/parse (get headers "cookie"))
     :host (first (string/split (str (get headers "host")) #":"))
     :remote-addr (remote-addr exchange)
     :forwarded-for (forwarded-for (.getRequestHeaders exchange))
     :exchange exchange}))

(defn with-body
  "The request with its body stream. max-body-bytes, when given, replaces
   the listener's limit, which is why the stream is opened only now."
  [{:keys [^HttpServerExchange exchange] :as request} max-body-bytes]
  (if exchange
    (do
      (when max-body-bytes
        (.setMaxEntitySize exchange (long max-body-bytes)))
      (assoc request :body (.getInputStream exchange)))
    request))

(defn- write-body!
  [^OutputStream out body]
  (cond
    (nil? body) nil
    (string? body) (.write out (.getBytes ^String body StandardCharsets/UTF_8))
    (bytes? body) (.write out ^bytes body)
    (instance? InputStream body) (with-open [in ^InputStream body] (io/copy in out))
    (fn? body) (body out)
    :else (.write out (.getBytes (str body) StandardCharsets/UTF_8))))

(defn respond!
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
