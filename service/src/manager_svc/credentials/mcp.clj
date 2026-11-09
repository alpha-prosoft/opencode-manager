(ns manager-svc.credentials.mcp
  "Calls MCP servers from the platform itself: credential checks through a
   tool, and reachability for the admin view."
  (:require [clojure.string :as string]
            [manager-svc.http :as http])
  (:import [java.util Base64]))

(def accept "application/json, text/event-stream")

(defn authorization
  [scheme {:keys [secret username]}]
  (case scheme
    :basic (str "Basic " (.encodeToString (Base64/getEncoder)
                                          (.getBytes (str username ":" secret) "UTF-8")))
    :raw secret
    (str "Bearer " secret)))

(defn sse-data
  "JSON-RPC messages in the data: lines of an event stream."
  [text]
  (keep (fn [line]
          (when (string/starts-with? line "data:")
            (try
              (http/read-json (subs line 5))
              (catch Exception _ nil))))
        (string/split-lines text)))

(defn- payload
  [{:keys [headers body]}]
  (if (string/includes? (str (get headers "content-type")) "text/event-stream")
    (last (filter map? (sse-data body)))
    (http/read-json body)))

(defn- tool-failed!
  [message]
  (throw (ex-info message {:mcp/tool-failed true})))

(defn tool-failed?
  [e]
  (true? (:mcp/tool-failed (ex-data e))))

(defn- result-value
  [server tool {:keys [content isError structuredContent] :as result}]
  (let [text
        (apply str (keep #(when (= "text" (:type %)) (:text %)) content))]
    (cond
      isError (tool-failed! (if (string/blank? text) (str tool " failed on " (:name server)) text))
      (contains? result :structuredContent) (if (and (map? structuredContent) (= #{:result} (set (keys structuredContent))))
                                              (:result structuredContent)
                                              structuredContent)
      :else (try
              (http/read-json text)
              (catch Exception _ text)))))

(defn call-tool
  "Result of `tool` on the server: parsed JSON when the tool returned JSON,
   otherwise text. Throws (tool-failed? e) on any failure."
  [server credential tool arguments timeout-seconds]
  (let [headers
        (cond-> {"Accept" accept}
          credential (assoc (get-in server [:auth :header] "Authorization")
                            (authorization (get-in server [:auth :scheme]) credential)))

        response
        (try
          (http/request! {:method :post
                          :url (:url server)
                          :headers headers
                          :json {:jsonrpc "2.0" :id 1 :method "tools/call"
                                 :params {:name tool :arguments arguments}}
                          :timeout-seconds timeout-seconds})
          (catch Exception e
            (tool-failed! (str "could not reach the " (:name server) " MCP server (" (.getSimpleName (class e)) ")"))))

        parsed
        (when (<= 200 (:status response) 299)
          (try (payload response) (catch Exception _ nil)))]
    (cond
      (not (<= 200 (:status response) 299))
      (tool-failed! (str "could not reach the " (:name server) " MCP server (HTTP " (:status response) ")"))

      (not (map? parsed))
      (tool-failed! (str "could not reach the " (:name server) " MCP server (invalid response)"))

      (:error parsed)
      (tool-failed! (or (get-in parsed [:error :message]) "the MCP server returned an error"))

      :else
      (result-value server tool (or (:result parsed) {})))))

(defn ping
  "{:reachable :detail} of an MCP server."
  [server]
  (try
    (let [{:keys [status]}
          (http/request! {:method :post
                          :url (:url server)
                          :headers {"Accept" accept}
                          :json {:jsonrpc "2.0" :id 1 :method "ping"}
                          :timeout-seconds 5})]
      {:reachable true
       :detail (str "HTTP " status)})
    (catch Exception e
      {:reachable false
       :detail (.getSimpleName (class e))})))
