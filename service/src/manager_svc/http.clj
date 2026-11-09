(ns manager-svc.http
  "Outgoing HTTP to systems outside the process (OpenCode, MCP servers, SCM
   providers, LLM providers, credential checks). Every request has a connect
   timeout and a request timeout; redirects are not followed unless asked,
   so credentials never travel to a host the caller did not choose."
  (:require [clojure.string :as string]
            [jsonista.core :as json])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpRequest
            HttpRequest$BodyPublishers HttpRequest$Builder HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(set! *warn-on-reflection* true)

(def default-timeout-seconds 60)

(defn- client
  [redirect]
  (-> (HttpClient/newBuilder)
      (.version HttpClient$Version/HTTP_1_1)
      (.followRedirects redirect)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(def ^:private clients
  (delay {:never (client HttpClient$Redirect/NEVER)
          :normal (client HttpClient$Redirect/NORMAL)}))

(defn write-json
  ^String [value]
  (json/write-value-as-string value))

(defn read-json
  "JSON with keyword keys, nil for a blank text."
  [text]
  (when-not (string/blank? text)
    (json/read-value text json/keyword-keys-object-mapper)))

(defn query-string
  [params]
  (string/join "&"
               (for [[k v] params
                     :when (some? v)]
                 (str (name k) "=" (URLEncoder/encode (str v) StandardCharsets/UTF_8)))))

(defn- response-headers
  [^HttpResponse response]
  (into {}
        (map (fn [[k values]] [(string/lower-case k) (first values)]))
        (.map (.headers response))))

(defn- body-of
  [{:keys [body] :as opts}]
  (if (contains? opts :json)
    (write-json (:json opts))
    body))

(defn- body-publisher
  [body]
  (cond
    (nil? body) (HttpRequest$BodyPublishers/noBody)
    (bytes? body) (HttpRequest$BodyPublishers/ofByteArray ^bytes body)
    :else (HttpRequest$BodyPublishers/ofString (str body))))

(defn- build-request
  ^HttpRequest [{:keys [method url headers timeout-seconds] :as opts}]
  (let [all-headers
        (merge (when (contains? opts :json) {"Content-Type" "application/json"})
               headers)

        builder
        (-> (HttpRequest/newBuilder (URI/create url))
            (.timeout (Duration/ofSeconds (or timeout-seconds default-timeout-seconds)))
            (.method (string/upper-case (name (or method :get))) (body-publisher (body-of opts))))]
    (doseq [[k v] all-headers]
      (.header ^HttpRequest$Builder builder (name k) (str v)))
    (.build builder)))

(defn request!
  "{:method :url :headers :body | :json :timeout-seconds :stream? :follow-redirects?}
   -> {:status :headers :body}; body is a String, or an InputStream when
   :stream? (the timeout then covers the wait for the response headers).
   Throws IOException when the server can't be reached or times out."
  [{:keys [stream? follow-redirects?] :as opts}]
  (let [response
        (.send ^HttpClient (get @clients (if follow-redirects? :normal :never))
               (build-request opts)
               (if stream?
                 (HttpResponse$BodyHandlers/ofInputStream)
                 (HttpResponse$BodyHandlers/ofString)))]
    {:status (.statusCode response)
     :headers (response-headers response)
     :body (.body response)}))

(defn ok?
  [{:keys [status]}]
  (<= 200 status 299))

(defn checked!
  "The response, or an ex-info with the status when it is not 2xx."
  [{:keys [status body] :as response} what]
  (if (ok? response)
    response
    (throw (ex-info (str what " failed with HTTP " status)
                    {:http-status status
                     :body (when (string? body) (subs body 0 (min 500 (count body))))}))))

(defn json-body
  [response]
  (read-json (:body response)))
