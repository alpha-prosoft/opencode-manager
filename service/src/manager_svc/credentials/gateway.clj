(ns manager-svc.credentials.gateway
  "MCP gateway: sandboxes reach MCP servers only through /mcp-gw/<server-id>.
   The sandbox authenticates with its session key; the harness's MCP server
   allow-list and the server's tool allow-list are enforced, the server is
   looked up in the session's tenant, and the right credential is added on
   the way out. Responses stream through."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.credentials.api :as credentials-api]
            [manager-svc.credentials.mcp :as mcp]
            [manager-svc.credentials.spec :as spec]
            [manager-svc.edge.server :as server]
            [manager-svc.errors :as errors]
            [manager-svc.http :as http]
            [manager-svc.observability.api :as observability-api]
            [manager-svc.sessions.api :as sessions-api])
  (:import [java.io InputStream OutputStream]
           [java.util UUID]))

(def prefix "/mcp-gw/")
(def request-headers ["content-type" "accept" "mcp-session-id" "mcp-protocol-version" "last-event-id"])
(def response-headers ["content-type" "mcp-session-id"])

(defn server-id-of
  [path]
  (let [rest
        (string/replace (subs path (count prefix)) #"/+$" "")]
    (when (and (seq rest) (not (string/includes? rest "/")))
      rest)))

(defn messages
  [body]
  (if (string/blank? body)
    []
    (let [parsed
          (try
            (http/read-json body)
            (catch Exception _
              (errors/fail! 400 "MCP requests must be JSON-RPC")))]
      (filterv map? (if (sequential? parsed) parsed [parsed])))))

(defn rpc-error
  "JSON-RPC errors for every request message, 403 when there is none."
  [messages message]
  (let [errors
        (for [m messages
              :when (contains? m :id)]
          {:jsonrpc "2.0" :id (:id m) :error {:code -32001 :message message}})]
    (server/json (if (seq errors) 200 403)
                 (if (= 1 (count errors)) (first errors) (vec errors)))))

(defn- filter-tools
  [payload allowed]
  (if (sequential? (get-in payload [:result :tools]))
    (update-in payload [:result :tools] (fn [tools] (filterv #(contains? allowed (:name %)) tools)))
    payload))

(defn filter-body
  [body content-type allowed]
  (if (string/includes? (str content-type) "application/json")
    (let [parsed
          (http/read-json body)]
      (http/write-json (if (sequential? parsed)
                         (mapv #(filter-tools % allowed) parsed)
                         (filter-tools parsed allowed))))
    (string/join "\n"
                 (map (fn [line]
                        (if (string/starts-with? line "data:")
                          (try
                            (str "data: " (http/write-json (filter-tools (http/read-json (subs line 5)) allowed)))
                            (catch Exception _ line))
                          line))
                      (string/split body #"\n" -1)))))

(defn- stream-to
  [^InputStream in]
  (fn [^OutputStream out]
    (with-open [in in]
      (let [buffer
            (byte-array 8192)]
        (loop []
          (let [n
                (.read in buffer)]
            (when (pos? n)
              (.write out buffer 0 n)
              (.flush out)
              (recur))))))))

(defn- record-audit!
  "Records a tool call in the session's audit trail; a call that can't be
   recorded is not forwarded."
  [system session-id payload]
  (try
    (observability-api/append-audit! system session-id "mcp"
                                     (str "mcp:" (string/replace (str (UUID/randomUUID)) "-" ""))
                                     payload)
    true
    (catch Exception e
      (log/error e "Could not record an MCP call in the audit trail" session-id)
      false)))

(defn- authenticate!
  [system request server-id]
  (let [key
        (get-in request [:headers "x-platform-key"])

        session
        (when-not (string/blank? key)
          (sessions-api/session-by-key system key #{"tools"}))

        allowed-servers
        (when session
          (set (map name (sessions-api/mcp-servers system (:id session)))))]
    (cond
      (nil? session) (errors/fail! 401 "Invalid gateway key")
      (not (contains? allowed-servers server-id)) (errors/forbidden! (str "This harness may not use MCP server " server-id))
      :else session)))

(defn- available-server!
  [system session server-id]
  (let [mcp-server
        (spec/mcp-server (spec/spec system (:tenant session)) server-id)]
    (if (and mcp-server
             (spec/for-teams? (:teams mcp-server) (keep identity [(:team-id session)])))
      mcp-server
      (errors/not-found! (str "MCP server " server-id " is not available")))))

(defn- owner-of
  "Whose values a session uses: its tenant's, its team's and, unless the
   platform owns it (team and tenant values only), its owner's."
  [session]
  {:tenant (:tenant session)
   :team-id (:team-id session)
   :user (when-not (sessions-api/platform-owned? session)
           (:sub session))})

(defn- upstream-request
  [request mcp-server body credential-header]
  (let [forwarded
        (into {}
              (keep (fn [header-name]
                      (when-let [value (get-in request [:headers header-name])]
                        [header-name value])))
              request-headers)]
    {:method (:method request)
     :url (:url mcp-server)
     :headers (merge forwarded credential-header)
     :body (when (seq body) body)
     :stream? true}))

(defn- passthrough-headers
  [headers]
  (into {} (keep (fn [k] (when-let [v (get headers k)] [k v]))) response-headers))

(defn forward
  [system request]
  (let [server-id
        (or (server-id-of (:path request))
            (errors/not-found! "Not found"))

        session
        (authenticate! system request server-id)

        mcp-server
        (available-server! system session server-id)

        body
        (server/body-text request)

        rpc
        (if (= :post (:method request)) (messages body) [])

        calls
        (filterv #(= "tools/call" (:method %)) rpc)

        allowed
        (some->> (:tools mcp-server) (map name) set)

        blocked
        (when allowed
          (first (remove #(contains? allowed (get-in % [:params :name])) calls)))

        resolved
        (when (and (nil? blocked) (:credential mcp-server))
          (credentials-api/resolve-credential system (owner-of session) (:credential mcp-server)))]
    (cond
      blocked
      (rpc-error rpc (str "Tool " (get-in blocked [:params :name]) " is not allowed on " (:name mcp-server)))

      (and (:credential mcp-server) (nil? resolved))
      (let [message
            (str (:name mcp-server) " needs your " (:credential mcp-server)
                 " credential. Add it under Accounts in the platform.")]
        (if (seq rpc)
          (rpc-error rpc message)
          (errors/forbidden! message)))

      (not (every? (fn [call]
                     (record-audit! system (:id session)
                                    {:server (:id mcp-server)
                                     :tool (get-in call [:params :name])
                                     :arguments (get-in call [:params :arguments])
                                     :credential (:credential mcp-server)
                                     :source (:source resolved)}))
                   calls))
      (rpc-error rpc "The platform could not record this call in the audit trail; try again")

      :else
      (let [credential-header
            (when resolved
              {(get-in mcp-server [:auth :header] "Authorization")
               (mcp/authorization (get-in mcp-server [:auth :scheme]) (:value resolved))})

            upstream
            (try
              (http/request! (upstream-request request mcp-server body credential-header))
              (catch java.io.IOException e
                (log/warn "MCP server unreachable" (:id mcp-server) (ex-message e))
                nil))]
        (cond
          (nil? upstream)
          (if (seq rpc)
            (rpc-error rpc (str (:name mcp-server) " is unreachable right now"))
            (errors/fail! 502 (str (:name mcp-server) " is unreachable")))

          (and allowed (some #(= "tools/list" (:method %)) rpc))
          (let [content
                (with-open [in ^InputStream (:body upstream)]
                  (slurp in :encoding "UTF-8"))]
            {:status (:status upstream)
             :headers (passthrough-headers (:headers upstream))
             :body (filter-body content (get-in upstream [:headers "content-type"]) allowed)})

          :else
          {:status (:status upstream)
           :headers (passthrough-headers (:headers upstream))
           :body (stream-to (:body upstream))})))))

(defn- handler
  [system request]
  (if (contains? #{:get :post :delete} (:method request))
    (forward system request)
    (server/error 405 "Method not allowed")))

(def sandbox-routes
  [{:method :any :prefix prefix :handler handler}])
