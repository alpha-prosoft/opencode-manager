(ns manager-svc.runs.tools
  "The MCP server at /mcp/ through which an agent submits its run's result
   (submit_review, submit_implementation, submit_document). Streamable HTTP in
   stateless JSON-response mode: initialize, ping, tools/list and tools/call.
   A sandbox authenticates every request with the X-Platform-Key derived
   for its session (purpose \"mcp\"), checked before the body is read. The
   route is served on the sandbox listener only."
  (:require [clojure.tools.logging :as log]
            [manager-svc.client :as client]
            [manager-svc.edge.server :as server]
            [manager-svc.http :as http]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.edd :as edd]
            [manager-svc.runs.submissions :as submissions]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.workers :as workers]))

(def protocol-version "2025-06-18")

(def instructions "Submit the result of the current platform activity.")

(def accepted "Result accepted. The platform takes it from here; you are done.")

(def ^:private string-list
  {:type "array" :items {:type "string"} :default []})

(def finding-schema
  {:type "object"
   :properties {:severity {:type "string" :enum ["blocker" "major" "minor" "question" "nit"]}
                :title {:type "string" :maxLength 200 :description "One-line statement of the problem"}
                :body {:type "string" :maxLength 4000 :description "Explanation and the concrete fix, in Markdown"}
                :path {:type ["string" "null"] :description "Repository-relative path of the changed file"}
                :line {:type ["integer" "null"] :minimum 1
                       :description "Line number in the NEW version of the file, inside the diff"}
                :suggestion {:type ["string" "null"] :maxLength 4000
                             :description "Replacement code for that line, if any"}}
   :required ["severity" "title" "body"]})

(def tools
  [{:name "submit_review"
    :description "Submit the finished code review. Call exactly once. The platform decides what is posted."
    :inputSchema {:type "object"
                  :properties {:verdict {:type "string" :enum ["APPROVE" "APPROVE_WITH_NITS" "REQUEST_CHANGES"]}
                               :summary {:type "string"}
                               :findings {:type "array" :items finding-schema :default []}
                               :checked string-list}
                  :required ["verdict" "summary"]}}
   {:name "submit_implementation"
    :description (str "Submit the finished implementation. Call exactly once after the changes are in the working tree. "
                      "title: short imperative title (max 120 characters). "
                      "verified: true only if the tests or checks ran and passed.")
    :inputSchema {:type "object"
                  :properties {:title {:type "string"}
                               :summary {:type "string"}
                               :verified {:type "boolean"}
                               :changes string-list
                               :tests string-list
                               :notes {:type "string" :default ""}}
                  :required ["title" "summary" "verified"]}}
   {:name "submit_document"
    :description "Submit the finished document as Markdown. Call exactly once."
    :inputSchema {:type "object"
                  :properties {:title {:type "string"}
                               :markdown {:type "string"}}
                  :required ["title" "markdown"]}}])

(defn- tool-error
  [message]
  (ex-info message {:tool-error true}))

(defn authenticated-session
  "The session whose mcp key the request carries, or nil."
  [system request]
  (when-let [key
             (get-in request [:headers "x-platform-key"])]
    (sessions/session-by-key system key #{"mcp"})))

(defn- refusal
  "What the agent is told when the platform refuses a submission."
  [e]
  (let [{:keys [status message]}
        (ex-data e)]
    (if (and (int? status) (< status 500) message)
      message
      "The platform could not record the result; try again")))

(defn submit!
  "Validates and records a submission; returns the text answered to the agent."
  [system session tool arguments]
  (let [run-id
        (or (sessions/parse-id (:run-id session))
            (throw (tool-error "This session does not belong to a platform run")))

        found
        (or (edd/run-state system run-id)
            (throw (tool-error "This session does not belong to a platform run")))

        expected
        (:result-tool (platform/activity system (:activity found)))

        _
        (when (not= tool expected)
          (throw (tool-error (str "This run expects " expected ", not " tool))))

        submission
        (try
          (submissions/validate-submission tool arguments)
          (catch clojure.lang.ExceptionInfo e
            (throw (tool-error (ex-message e)))))]
    (try
      (client/command (:settings system) {:cmd-id :submit-run-result
                                          :id run-id
                                          :attrs {:tool tool :payload submission}})
      (catch Exception e
        (log/warn "Submission for run" run-id "refused:" (ex-message e))
        (throw (tool-error (refusal e)))))
    (workers/kick!)
    accepted))

(defn- text-result
  [text error?]
  {:content [{:type "text" :text text}]
   :isError error?})

(defn- call-tool
  [system session {:keys [name arguments]}]
  (if-not (some #(= name (:name %)) tools)
    (text-result (str "Unknown tool: " name) true)
    (try
      (text-result (submit! system session name (or arguments {})) false)
      (catch clojure.lang.ExceptionInfo e
        (if (:tool-error (ex-data e))
          (text-result (ex-message e) true)
          (throw e))))))

(defn- respond
  [system session {:keys [id method params]}]
  (let [result
        (case method
          "initialize" {:protocolVersion (or (:protocolVersion params) protocol-version)
                        :capabilities {:tools {:listChanged false}}
                        :serverInfo {:name "opencode-platform" :version "1.0.0"}
                        :instructions instructions}
          "ping" {}
          "tools/list" {:tools tools}
          "tools/call" (call-tool system session params)
          ::unknown)]
    (when (some? id)
      (if (= ::unknown result)
        {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "Method not found: " method)}}
        {:jsonrpc "2.0" :id id :result result}))))

(defn- json-response
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (http/write-json body)})

(defn- parsed
  [request]
  (let [text
        (server/body-text request)]
    (try
      (http/read-json text)
      (catch Exception _
        nil))))

(defn- answer
  [system session message]
  (cond
    (nil? message)
    (json-response 400 {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Parse error"}})

    (sequential? message)
    (let [responses (vec (keep #(respond system session %) message))]
      (if (seq responses)
        (json-response 200 responses)
        {:status 202}))

    :else
    (if-let [response (respond system session message)]
      (json-response 200 response)
      {:status 202})))

(defn handle
  "Sandbox handler for /mcp and /mcp/."
  [system request]
  (if-not (= :post (:method request))
    (json-response 405 {:jsonrpc "2.0" :id nil :error {:code -32000 :message "Method not allowed"}})
    (if-let [session
             (authenticated-session system request)]
      (answer system session (parsed request))
      (json-response 401 {:jsonrpc "2.0" :id nil :error {:code -32001 :message "Invalid platform key"}}))))

(def routes
  [{:method :any :path "/mcp" :handler handle}
   {:method :any :path "/mcp/" :handler handle}])
