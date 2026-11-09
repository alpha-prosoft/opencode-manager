(ns manager-svc.observability.metrics
  "Prometheus /metrics for platform operators, over every tenant."
  (:require [clojure.string :as string]
            [edd.core :as edd]
            [manager-svc.auth :as auth]
            [manager-svc.client :as client]
            [manager-svc.errors :as errors]
            [manager-svc.observability.telemetry :as telemetry]
            [manager-svc.observability.usage :as usage]
            [manager-svc.sessions.api :as sessions]))

(defn metrics-query
  [ctx _query]
  {:usage (vec (usage/group-rows (usage/rows-since ctx nil) [:tenant :team-id :model]))
   :tools (telemetry/metrics-rows ctx)})

(defn- label-value
  [value]
  (-> (str value)
      (string/replace "\\" "\\\\")
      (string/replace "\"" "\\\"")
      (string/replace "\n" "\\n")))

(defn- labels
  [& pairs]
  (string/join "," (for [[k v] (partition 2 pairs)]
                     (str (name k) "=\"" (label-value v) "\""))))

(defn- family
  "One metric family: its HELP and TYPE lines, then its samples."
  [metric kind help samples]
  (concat [(str "# HELP " metric " " help)
           (str "# TYPE " metric " " kind)]
          (for [[label-str value] samples]
            (str metric "{" label-str "} " (or value 0)))))

(defn render
  [{:keys [usage tools]} running]
  (let [tool-calls
        (filter #(= :tool.end (keyword (:kind %))) tools)

        blocked
        (filter #(= :tool.blocked (keyword (:kind %))) tools)

        per-model
        (fn [row] (labels :tenant (:tenant row) :team (:team-id row) :model (:model row)))

        per-tool
        (fn [row] (labels :tenant (:tenant row) :team (:team-id row) :tool (:tool row)))]
    (str
     (string/join
      "\n"
      (concat
       (family "opencode_llm_tokens_total" "counter" "Tokens processed through the LLM gateway."
               (for [row usage
                     [kind k] [["prompt" :prompt-tokens] ["completion" :completion-tokens]]]
                 [(labels :tenant (:tenant row) :team (:team-id row) :model (:model row) :type kind) (k row)]))
       (family "opencode_llm_requests_total" "counter" "LLM gateway requests."
               (for [row usage] [(per-model row) (:calls row)]))
       (family "opencode_llm_errors_total" "counter" "LLM gateway requests that failed."
               (for [row usage] [(per-model row) (:errors row)]))
       (family "opencode_tool_calls_total" "counter" "Tool calls reported by the session monitor."
               (for [row tool-calls] [(per-tool row) (:n row)]))
       (family "opencode_tool_blocked_total" "counter" "Tool calls the session policy blocked."
               (for [row blocked] [(per-tool row) (:n row)]))
       (family "opencode_tool_duration_ms_total" "counter" "Total tool execution time."
               (for [row tool-calls] [(per-tool row) (:total-ms row)]))
       ["# HELP opencode_sessions_running Running sessions."
        "# TYPE opencode_sessions_running gauge"
        (str "opencode_sessions_running " running)]))
     "\n")))

(defn handler
  [{:keys [settings] :as system} {:keys [cookies headers]}]
  (let [identity
        (auth/resolve-identity settings cookies (get headers "authorization"))]
    (cond
      (nil? identity) (errors/fail! 401 "Not authenticated")
      (not (:operator identity)) (errors/forbidden! "Platform operators only")
      :else {:status 200
             :headers {"Content-Type" "text/plain; version=0.0.4"}
             :body (render (client/query settings {:query-id :get-observability-metrics})
                           (count (sessions/running-session-ids system)))})))

(defn register
  [ctx]
  (edd/reg-query ctx :get-observability-metrics metrics-query
                 :auth {:role [:non-interactive]}))
