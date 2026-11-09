(ns manager-svc.observability.upstream
  "How the platform calls an LLM connection: the gateway relays sandbox
   calls through it and the admin connection test uses the same path.

   Both connection types send the secret as a Bearer token; github-copilot
   defaults its base URL to the Copilot API. The OAuth token OpenCode stores
   is accepted there directly, as the previous manager used it."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.errors :as errors]
            [manager-svc.http :as http]
            [manager-svc.observability.json :as json]))

(def copilot-default-url "https://api.githubcopilot.com")
(def chat-timeout-seconds 600)

(defn connection-type
  [connection]
  (some-> (:type connection) name))

(defn upstream
  "{:url :headers} for a chat completion call on the connection."
  [connection secret]
  (let [base-url
        (some-> (:base-url connection) (string/replace #"/+$" ""))]
    (cond
      base-url
      {:url (str base-url "/chat/completions")
       :headers {"Authorization" (str "Bearer " secret)}}

      (= "github-copilot" (connection-type connection))
      {:url (str copilot-default-url "/chat/completions")
       :headers {"Authorization" (str "Bearer " secret)}}

      :else
      (errors/fail! 503 (str "LLM connection " (:id connection) " needs base_url")))))

(defn call!
  "POSTs a chat completion body (string keys) to the connection; the response
   body is an InputStream. Fails with 502 when the connection can't be reached,
   without internal details."
  [connection secret accept body]
  (let [{:keys [url headers]}
        (upstream connection secret)]
    (try
      (http/request! {:method :post
                      :url url
                      :headers (merge {"Content-Type" "application/json"
                                       "Accept" (or accept "*/*")}
                                      headers)
                      :body (json/write-str body)
                      :timeout-seconds chat-timeout-seconds
                      :stream? true})
      (catch java.io.IOException e
        (log/warn "LLM connection unreachable" (:id connection) (ex-message e))
        (errors/fail! 502 (str "Could not reach LLM connection " (:id connection)))))))

(defn test-connection
  "Sends a tiny chat completion through the connection the way the gateway
   does: {:ok true :model :latency-ms} or {:ok false :message [:status :latency-ms]}."
  [connection secret]
  (let [model
        (first (:models connection))

        started
        (System/nanoTime)

        outcome
        (try
          (let [{:keys [status body]}
                (call! connection secret "application/json"
                       {"model" model
                        "messages" [{"role" "user" "content" "Reply with OK"}]
                        "max_tokens" 5})]
            {:status status
             :body (with-open [in ^java.io.InputStream body]
                     (slurp in :encoding "UTF-8"))})
          (catch Exception e
            {:error (or (:message (ex-data e)) "The connection test failed")}))

        latency
        (quot (- (System/nanoTime) started) 1000000)]
    (cond
      (:error outcome)
      {:ok false :message (:error outcome)}

      (>= (:status outcome) 400)
      {:ok false
       :status (:status outcome)
       :message (subs (:body outcome) 0 (min 300 (count (:body outcome))))
       :latency-ms latency}

      :else
      {:ok true :model model :latency-ms latency})))
