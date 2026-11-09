(ns manager-svc.opencode
  "The OpenCode server API of a session's sandbox. Every call authenticates
   with the session's Basic password."
  (:require [clojure.string :as string]
            [manager-svc.http :as http])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def username "opencode")

(defn basic-auth
  [password]
  (str "Basic " (.encodeToString (Base64/getEncoder)
                                 (.getBytes (str username ":" password) StandardCharsets/UTF_8))))

(defn web-path
  ([directory]
   (web-path directory nil))
  ([directory conversation]
   (let [encoded
         (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                          (.getBytes ^String directory StandardCharsets/UTF_8))]
     (if conversation
       (str "/" encoded "/session/" conversation)
       (str "/" encoded)))))

(defn- url
  [base-url path]
  (str (string/replace base-url #"/+$" "") path))

(defn request!
  "The response of an OpenCode call; throws unless it is 2xx."
  [base-url password method path & {:keys [json timeout stream?]}]
  (http/checked!
   (http/request! (cond-> {:method method
                           :url (url base-url path)
                           :headers {"Authorization" (basic-auth password)
                                     "Accept" "application/json"}
                           :timeout-seconds (or timeout 30)
                           :stream? stream?}
                    json (assoc :json json)))
   (str "OpenCode " path)))

(defn- json-of
  [base-url password method path & options]
  (http/json-body (apply request! base-url password method path options)))

(defn text
  "The body of a GET as text, for callers that parse foreign JSON themselves."
  [base-url password path]
  (:body (request! base-url password :get path)))

(defn healthy?
  [base-url password]
  (try
    (true? (:healthy (json-of base-url password :get "/global/health" :timeout 2)))
    (catch Exception _
      false)))

(defn session-statuses
  "{conversation-id {:type \"idle\"|\"busy\"|\"retry\" ...}} with string ids."
  [base-url password]
  (into {}
        (map (fn [[k v]] [(name k) v]))
        (json-of base-url password :get "/session/status" :timeout 10)))

(defn busy-count
  "Conversations of the server that are not idle; throws when unreachable."
  [base-url password]
  (count (remove #(= "idle" (:type %)) (vals (session-statuses base-url password)))))

(defn create-session!
  [base-url password title]
  (:id (json-of base-url password :post "/session" :json {:title title})))

(defn messages
  [base-url password conversation]
  (json-of base-url password :get (str "/session/" conversation "/message")))

(defn prompt-async!
  [base-url password conversation model text]
  (let [[provider-id model-id]
        (string/split model #"/" 2)]
    (request! base-url password :post (str "/session/" conversation "/prompt_async")
              :json {:model {:providerID provider-id :modelID model-id}
                     :parts [{:type "text" :text text}]})
    nil))

(defn abort!
  [base-url password conversation]
  (request! base-url password :post (str "/session/" conversation "/abort"))
  nil)

(defn last-reply
  "Text of the last completed assistant message; throws when OpenCode reports an error."
  [base-url password conversation]
  (some (fn [{:keys [info parts]}]
          (cond
            (and (= "assistant" (:role info)) (:error info))
            (throw (ex-info (str "OpenCode reported an error: " (http/write-json (:error info)))
                            {:opencode-error true}))

            (and (= "assistant" (:role info)) (get-in info [:time :completed]))
            (string/trim (string/join "\n" (keep #(when (= "text" (:type %)) (:text %)) parts)))

            :else nil))
        (reverse (messages base-url password conversation))))

(defn events
  "The /event stream response: {:status :body InputStream}."
  [base-url password]
  (http/request! {:method :get
                  :url (url base-url "/event")
                  :headers {"Authorization" (basic-auth password)
                            "Accept" "text/event-stream"}
                  :timeout-seconds 30
                  :stream? true}))
