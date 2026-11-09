(ns manager-svc.observability.gateway
  "OpenAI-compatible LLM gateway for sandboxes, served on the sandbox
   listener.

   A sandbox authenticates with its session key (sessions.api/session-by-key
   knows no key of a stopped or locked session); the gateway enforces the harness
   model allow-list and the team's daily token budget, adds the provider
   credential (manager-svc.observability.upstream), relays the upstream
   response (SSE streams chunk by chunk, unbuffered) and meters usage from
   the usage block."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.edge.server :as server]
            [manager-svc.errors :as errors]
            [manager-svc.observability.api :as api]
            [manager-svc.observability.json :as json]
            [manager-svc.observability.upstream :as upstream]
            [manager-svc.platform.api :as platform]
            [manager-svc.sessions.api :as sessions])
  (:import [java.io InputStream OutputStream]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

(def max-body-bytes
  "LLM requests carry whole conversations."
  33554432)

(defn sandbox
  "The LLM part of a session's sandbox: {:llm-connection :llm-models :tenant :team-id :run-id}."
  [session]
  (merge (select-keys session [:tenant :team-id :run-id :llm-connection :llm-models])
         (:sandbox session)))

(defn session-of-key!
  "The live session a gateway key belongs to; 401 for any other key."
  [system key purposes]
  (or (when key
        (sessions/session-by-key system key purposes))
      (errors/fail! 401 "Invalid gateway key")))

(defn- context
  [system request]
  (let [session
        (session-of-key! system (auth/bearer (get-in request [:headers "authorization"])) #{"llm"})

        box
        (sandbox session)

        connection-id
        (some-> (:llm-connection box) name)

        connection
        (platform/llm-connection system (:tenant box) connection-id)]
    (if connection
      {:session session :sandbox box :connection connection}
      (errors/fail! 503 (str "LLM connection " connection-id " is not configured")))))

(defn models
  [system request]
  (let [{:keys [sandbox]}
        (context system request)]
    (server/json {:object "list"
                  :data (mapv (fn [model] {:id model :object "model" :owned_by "platform"})
                              (:llm-models sandbox))})))

(defn usage-of
  "[prompt completion cached] from an OpenAI payload (string keys)."
  [payload]
  (let [block
        (or (get payload "usage") {})]
    [(or (get block "prompt_tokens") 0)
     (or (get block "completion_tokens") 0)
     (or (get-in block ["prompt_tokens_details" "cached_tokens"]) 0)]))

(defn check-budget!
  "Refuses a call when the team used its daily budget (its team.json), and
   when the usage can't be read (fails closed)."
  [system tenant team-id]
  (let [daily
        (when team-id
          (get-in (platform/team system tenant team-id) [:budget :daily-tokens]))

        used
        (when (and daily (pos? daily))
          (try
            (api/tokens-today system tenant team-id)
            (catch Exception e
              (log/error e "Could not read the token budget of" team-id)
              (errors/fail! 503 "The token budget can't be checked right now"))))]
    (when (and used (>= used daily))
      (errors/fail! 429 (str "Team " team-id " has used its daily token budget")))))

(defn- validated-body!
  [request sandbox]
  (let [body
        (json/read-str (server/body-text request))

        model
        (get body "model")]
    (when-not (map? body)
      (errors/fail! 400 "The request must be a JSON object"))
    (when-not (some #{model} (:llm-models sandbox))
      (errors/fail! 403 (str "Model " model " is not allowed for this session")))
    body))

(defn- usage-line
  "Usage totals from one SSE data line (\"data:\" with or without a space)
   when it carries a non-empty usage block."
  [^String line]
  (when (and (string/starts-with? line "data:") (string/includes? line "\"usage\""))
    (let [payload
          (json/read-str (subs line 5))

          totals
          (when (map? payload)
            (usage-of payload))]
      (when (and totals (some pos? totals))
        totals))))

(defn relay!
  "Copies the upstream stream to out chunk by chunk, flushing each, and
   returns the last usage totals seen in its SSE lines."
  [^InputStream in ^OutputStream out]
  (let [buffer
        (byte-array 8192)

        pending
        (StringBuilder.)]
    (loop [totals [0 0 0]]
      (let [n
            (.read in buffer)]
        (if (neg? n)
          (or (usage-line (str pending)) totals)
          (do
            (.write out buffer 0 n)
            (.flush out)
            (.append pending (String. buffer 0 n StandardCharsets/UTF_8))
            (let [text
                  (str pending)

                  cut
                  (.lastIndexOf text "\n")

                  lines
                  (when (>= cut 0) (string/split-lines (subs text 0 cut)))]
              (when (>= cut 0)
                (.setLength pending 0)
                (.append pending (subs text (inc cut))))
              (recur (or (last (keep usage-line lines)) totals)))))))))

(defn- secret!
  [system tenant connection]
  (let [secret
        (credentials/secret system tenant (:secret connection))]
    (when (string/blank? secret)
      (errors/fail! 503 (str "Secret " (:secret connection) " is not configured")))
    secret))

(defn- usage-recorder
  [system session sandbox connection model status started]
  (fn [[prompt completion cached]]
    (try
      (api/record-usage! system {:session-id (:id session)
                                 :username (:sub session)
                                 :run-id (:run-id sandbox)
                                 :tenant (:tenant sandbox)
                                 :team-id (:team-id sandbox)
                                 :connection (:id connection)
                                 :model model
                                 :status status
                                 :prompt-tokens prompt
                                 :completion-tokens completion
                                 :cached-tokens cached
                                 :latency-ms (quot (- (System/nanoTime) started) 1000000)})
      (catch Exception e
        (log/error e "Could not record LLM usage" (:id session))))))

(defn chat-completions
  [system request]
  (let [{:keys [session sandbox connection]}
        (context system request)

        body
        (validated-body! request sandbox)

        _
        (check-budget! system (:tenant sandbox) (:team-id sandbox))

        streaming?
        (boolean (get body "stream"))

        upstream-body
        (if streaming?
          (assoc body "stream_options" (assoc (or (get body "stream_options") {}) "include_usage" true))
          body)

        started
        (System/nanoTime)

        response
        (upstream/call! connection (secret! system (:tenant sandbox) connection) (get-in request [:headers "accept"]) upstream-body)

        status
        (:status response)

        record!
        (usage-recorder system session sandbox connection (get body "model") status started)]
    (if (or (not streaming?) (>= status 400))
      (let [content
            (with-open [in ^InputStream (:body response)]
              (slurp in :encoding "UTF-8"))

            payload
            (or (json/read-str content)
                {"error" {"message" (subs content 0 (min 2000 (count content)))}})]
        (record! (if (< status 400) (usage-of payload) [0 0 0]))
        {:status status
         :headers {"Content-Type" "application/json"}
         :body (json/write-str payload)})
      {:status status
       :headers {"Content-Type" (or (get-in response [:headers "content-type"]) "text/event-stream")
                 "Cache-Control" "no-cache"}
       :body (fn [out]
               (let [totals
                     (volatile! [0 0 0])]
                 (try
                   (with-open [in ^InputStream (:body response)]
                     (vreset! totals (relay! in out)))
                   (finally
                     (record! @totals)))))})))

(def routes
  [{:method :get :path "/llm/v1/models" :handler models}
   {:method :post :path "/llm/v1/chat/completions" :max-body-bytes max-body-bytes :handler chat-completions}])
