(ns manager-svc.observability.edge
  "Edge routes: telemetry from the sandbox monitor plugin (sandbox listener),
   the audit export and /metrics (public listener)."
  (:require [clojure.tools.logging :as log]
            [manager-svc.client :as client]
            [manager-svc.edge.api :as edge-api]
            [manager-svc.edge.server :as server]
            [manager-svc.errors :as errors]
            [manager-svc.observability.api :as api]
            [manager-svc.observability.gateway :as gateway]
            [manager-svc.observability.json :as json]
            [manager-svc.observability.metrics :as metrics]
            [manager-svc.observability.telemetry :as telemetry]
            [manager-svc.sessions.api :as sessions])
  (:import [java.io OutputStream]
           [java.nio.charset StandardCharsets]))

(defn ingest-telemetry
  "POST /api/telemetry, authenticated by the session key in X-Platform-Key
   before the body is read."
  [system {:keys [headers] :as request}]
  (let [session
        (when-let [key (get headers "x-platform-key")]
          (sessions/session-by-key system key #{"telemetry"}))

        _
        (when-not session
          (errors/fail! 401 "Invalid telemetry key"))

        report
        (telemetry/parse-report (json/read-str (server/body-text request)))]
    (api/record-telemetry! system session report)
    (when (telemetry/tool-report? report)
      (try
        (sessions/touch! system (:id session))
        (catch Exception e
          (log/warn "Could not touch session" (:id session) (ex-message e)))))
    {:status 204}))

(def ^:private export-path
  #"^/api/sessions/([^/]+)/audit/export$")

(defn- audit-page
  [settings token session-id after]
  (client/query-as settings token {:query-id :list-audit-events
                                   :session-id session-id
                                   :after after
                                   :limit 1000
                                   :raw true}))

(defn export-line
  "An entry as one JSON line; the payload is the stored body its hash covers."
  [entry]
  (str "{\"seq\":" (:seq entry)
       ",\"ts\":" (json/write-str (:ts entry))
       ",\"type\":" (json/write-str (:type entry))
       ",\"key\":" (json/write-str (:key entry))
       ",\"hash\":" (json/write-str (:hash entry))
       ",\"payload\":" (:body entry)
       "}\n"))

(defn- write-entry!
  [^OutputStream out entry]
  (.write out (.getBytes ^String (export-line entry) StandardCharsets/UTF_8)))

(defn export-audit
  "GET /api/sessions/{id}/audit/export: the audit trail as JSON lines, with
   each payload exactly as its hash covers it."
  [{:keys [settings]} {:keys [path] :as request}]
  (let [session-id
        (second (re-find export-path path))

        token
        (or (edge-api/caller-token settings request)
            (errors/fail! 401 "Not authenticated"))

        first-page
        (audit-page settings token session-id 0)]
    {:status 200
     :headers {"Content-Type" "application/x-ndjson"
               "Content-Disposition" (str "attachment; filename=\"audit-" session-id ".jsonl\"")}
     :body (fn [^OutputStream out]
             (loop [entries first-page]
               (when (seq entries)
                 (doseq [entry entries]
                   (write-entry! out entry))
                 (.flush out)
                 (recur (audit-page settings token session-id (:seq (last entries)))))))}))

(def routes
  [{:method :get :prefix "/api/sessions/" :match #(re-find export-path (:path %)) :handler export-audit}
   {:method :get :path "/metrics" :handler metrics/handler}])

(def sandbox-routes
  (conj gateway/routes
        {:method :post :path "/api/telemetry" :handler ingest-telemetry}))
