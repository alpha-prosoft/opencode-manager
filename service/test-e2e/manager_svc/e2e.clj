(ns manager-svc.e2e
  "End-to-end checks of a running platform (docker compose up after
   scripts/seed-local-repos.sh), ported from tests/e2e.py.

     cd service && MANAGER_URL=http://localhost:8080 clojure -M:e2e

   Runs use the real LLM. The code review reviews the e2e-review branch of
   demo-service; local/secrets/BITBUCKET_TOKEN enables the credential and MCP
   gateway checks."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]
            [jsonista.core :as jsonista]
            [lambda.util :as util]
            [lambda.uuid :as uuid])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util UUID]))

(def base-url
  (or (System/getenv "MANAGER_URL") "http://localhost:8080"))

(def run-timeout-ms
  (* 15 60 1000))

(def results
  (atom []))

(def token-file
  (io/file ".." "local" "secrets" "BITBUCKET_TOKEN"))

(defn check
  ([check-name ok]
   (check check-name ok ""))
  ([check-name ok detail]
   (swap! results conj [check-name (boolean ok)])
   (println (str "  " (if ok "PASS" "FAIL") "  " check-name
                 (when (and (not ok) (seq (str detail)))
                   (str "  (" (subs (str detail) 0 (min 400 (count (str detail)))) ")"))))
   (boolean ok)))

(defn section
  [title]
  (println title))

(defn- http-client
  "An HTTP client with its own cookie jar (the session hosts and the manager
   share the cookie name, like a browser that sends it to both)."
  []
  {:http (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NEVER)
             (.connectTimeout (Duration/ofSeconds 10))
             (.build))
   :cookies (atom {})})

(defn- cookie-header
  [cookies]
  (string/join "; " (map (fn [[k v]] (str k "=" v)) cookies)))

(defn- remember-cookies!
  [cookies set-cookies]
  (doseq [set-cookie set-cookies
          :let [[pair] (string/split set-cookie #";")
                [k v] (string/split pair #"=" 2)]
          :when k]
    (if (string/blank? v)
      (swap! cookies dissoc k)
      (swap! cookies assoc k v))))

(defn- send!
  [{:keys [http cookies]} method url {:keys [body headers]}]
  (let [with-cookies
        (cond-> (merge {"Content-Type" "application/json" "X-Requested-With" "e2e"} headers)
          (seq @cookies) (assoc "Cookie" (cookie-header @cookies)))

        builder
        (reduce (fn [b [k v]] (if v (.header b k v) b))
                (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 120)))
                with-cookies)

        request
        (.build (.method builder method (if body
                                          (HttpRequest$BodyPublishers/ofString body)
                                          (HttpRequest$BodyPublishers/noBody))))

        response
        (.send ^HttpClient http request (HttpResponse$BodyHandlers/ofString))]
    (remember-cookies! cookies (.allValues (.headers response) "set-cookie"))
    {:status (.statusCode response)
     :headers (into {} (map (fn [[k v]] [(string/lower-case k) (first v)])) (.map (.headers response)))
     :body (.body response)}))

(defn- parse
  [text]
  (when-not (string/blank? text)
    (try
      (util/to-edn text)
      (catch Exception _
        text))))

(defn login
  [username]
  (let [client
        (http-client)

        {:keys [status]}
        (send! client "POST" (str base-url "/api/auth/login") {:body (util/to-json {:username username})})]
    (when-not (= 200 status)
      (throw (ex-info (str "Login of " username " failed: " status) {})))
    {:username username :client client}))

(defn request
  ([person method path]
   (request person method path nil))
  ([{:keys [client]} method path body]
   (let [response
         (send! client method (str base-url path) {:body (when body (util/to-json body))})]
     (assoc response :data (parse (:body response))))))

(defn- envelope
  [payload]
  (merge {:request-id (UUID/randomUUID) :interaction-id (UUID/randomUUID)} payload))

(defn- outcome
  "{:ok result} or {:error {:status :message}} of an edd response."
  [{:keys [status data body]}]
  (let [failure
        (when (map? data) (or (:exception data) (:error data)))]
    (cond
      (and (map? data) (contains? data :result) (nil? failure)) {:ok (:result data)}
      (map? failure) {:error {:status (:status failure) :message (:message failure)}}
      failure {:error {:status status :message (str failure)}}
      (map? data) {:error {:status status :message (or (:detail data) body)}}
      :else {:error {:status status :message body}})))

(defn query
  [person q]
  (outcome (request person "POST" "/api/query" (envelope {:query q}))))

(defn command
  [person cmd]
  (outcome (request person "POST" "/api/command" (envelope {:commands [cmd]}))))

(defn result
  [person q]
  (:ok (query person q)))

(defn error-status
  [response]
  (get-in response [:error :status]))

(defn error-message
  [response]
  (str (get-in response [:error :message])))

(defn- wait-until
  [timeout-ms f]
  (let [deadline
        (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [value
            (f)]
        (cond
          value value
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 3000) (recur)))))))

(defn start-run
  [person team-id workflow-id inputs]
  (let [run-id
        (UUID/randomUUID)

        response
        (command person {:cmd-id :start-run :id run-id :attrs {:team-id team-id :workflow-id workflow-id :inputs inputs}})]
    (check (str "start " team-id "/" workflow-id) (:ok response) (:error response))
    (when (:ok response)
      run-id)))

(defn wait-for-run
  [person run-id]
  (if-not run-id
    {:status :not-started}
    (or (wait-until run-timeout-ms
                    #(let [found (result person {:query-id :get-run :run-id run-id})]
                       (when (contains? #{:completed :failed :cancelled} (:status found))
                         found)))
        {:status :timeout :id run-id})))

(defn sh
  [& args]
  (let [{:keys [out err]}
        (apply shell/sh args)]
    (str out err)))

(defn container-of
  [session-id]
  (str "opencode-" session-id))

(defn in-sandbox
  [session-id script]
  (sh "docker" "exec" (container-of session-id) "sh" "-c" script))

(defn sandbox-config
  "The OpenCode config the session's sandbox runs with, {} when unreadable."
  [session-id]
  (try
    (jsonista/read-value (sh "docker" "exec" (container-of session-id) "cat" "/etc/opencode/opencode/opencode.json")
                         jsonista/keyword-keys-object-mapper)
    (catch Exception _
      {})))

(defn- audit-ok
  [person session-id]
  (let [verification
        (result person {:query-id :verify-audit :session-id session-id})

        events
        (result person {:query-id :list-audit-events :session-id session-id :after 0 :limit 1000})

        tools
        (keep #(get-in % [:payload :tool]) (filter #(= "tool" (name (:type %))) events))]
    (check "audit hash chain is valid" (true? (:valid verification)) verification)
    (check "audit recorded tool calls" (seq tools) (vec tools))))

(defn platform-and-access
  [{:keys [alice bob carol]}]
  (section "Platform and access control")
  (let [synced
        (command alice {:cmd-id :sync-platform})]
    (check "admin can sync the onboarding repository" (:ok synced) (:error synced))
    (check "the sync finishes"
           (wait-until 60000 #(let [platform (result alice {:query-id :get-platform})]
                                (and (:synced-at platform) (nil? (:error platform)) platform)))))
  (check "non-admin cannot sync" (= 403 (error-status (command bob {:cmd-id :sync-platform}))))
  (let [team
        (result alice {:query-id :get-team :team-id "demo"})

        invalid
        (into {} (map (juxt :id :errors)) (remove :valid (:definitions team)))]
    (check "invalid workflow is rejected with a reason"
           (and (contains? invalid "github-review") (string/includes? (str invalid) "not granted"))
           invalid))
  (check "non-member cannot see a team" (= 404 (error-status (query carol {:query-id :get-team :team-id "demo"}))))
  (check "non-member cannot start a run"
         (= 404 (error-status (command carol {:cmd-id :start-run :id (UUID/randomUUID)
                                              :attrs {:team-id "demo" :workflow-id "requirements" :inputs {:brief (apply str (repeat 20 "x"))}}}))))
  (let [response
        (command alice {:cmd-id :start-run :id (UUID/randomUUID)
                        :attrs {:team-id "demo" :workflow-id "implement-change" :inputs {:task "short"}}})]
    (check "run inputs are validated" (= 422 (error-status response)) response))
  (check "marketplace lists the activities"
         (every? (set (map :id (result alice {:query-id :list-activities}))) ["session" "review" "implement" "analysis"])))

(defn roles-and-administration
  [{:keys [alice dave erin]}]
  (section "Roles and administration")
  (let [personas
        (into {} (map (juxt :username :roles)) (:data (request alice "GET" "/api/auth/personas")))]
    (check "login offers personas with their roles"
           (and (some #{"Platform admin"} (get personas "alice"))
                (some #(string/includes? % "Payments") (get personas "dave")))
           personas))
  (check "a cookie request from another origin is refused"
         (= 403 (:status (send! (:client alice) "POST" (str base-url "/api/command")
                                {:body (util/to-json {:commands [{:cmd-id :sync-platform}]})
                                 :headers {"X-Requested-With" nil "Origin" "http://evil.localhost:8080"}}))))
  (let [teams
        (into {} (map (juxt :id identity)) (result alice {:query-id :list-teams}))

        payments
        (get teams "payments")]
    (check "teams can be synced from a local path"
           (and (= :ok (:status payments)) (string/starts-with? (str (:commit payments)) "files:"))
           payments))
  (check "team admins see only their team" (= ["payments"] (mapv :id (:teams (result dave {:query-id :get-me})))))
  (check "team members cannot use the admin API" (= 403 (error-status (query erin {:query-id :get-admin-overview}))))
  (let [overview
        (result alice {:query-id :get-admin-overview})]
    (check "platform admins get an overview" (>= (or (:teams overview) 0) 3) overview))
  (let [users
        (into {} (map (juxt :username identity)) (result alice {:query-id :list-admin-users}))]
    (check "admin user matrix shows team roles" (= :admin (get-in users ["dave" :teams :payments])) (get users "dave")))
  (let [credentials
        (result alice {:query-id :list-admin-credentials})]
    (check "credential status never includes values"
           (and (seq credentials) (not-any? #(or (contains? % :secret) (contains? % :value) (contains? % :sealed)) credentials))))
  (let [connection
        (:id (first (:llm-connections (result alice {:query-id :get-platform}))))

        tested
        (result alice {:query-id :test-llm-connection :connection-id connection})]
    (check "admins can test an LLM connection" (:ok tested) tested))
  (check "admins can list schedules" (sequential? (result alice {:query-id :list-schedules}))))

(defn session-entry
  "Opens a session the way a browser does: the manager's entry URL hands off
   to the session host (through Traefik), which sets its cookie and proxies
   to OpenCode once forward auth passes."
  [person session]
  (let [entry
        (request person "GET" (:open-url session))

        location
        (get-in entry [:headers "location"])

        host-response
        (when location
          (send! (:client person) "GET" location {}))

        after-handoff
        (get-in host-response [:headers "location"])

        opened
        (when after-handoff
          (wait-until 120000 #(let [response (send! (:client person) "GET" after-handoff {})]
                                (when (= 200 (:status response)) response))))]
    {:entry entry :location location :host-response host-response :opened opened}))

(defn monitoring-and-policy
  [{:keys [bob carol]}]
  (section "Monitoring and policy")
  (let [run-id
        (start-run bob "demo" "workspace" {:title "e2e policy"
                                           :prompt "Use the bash tool to run exactly: git push origin main --dry-run   Then use the bash tool to run: ls repo. Report what happened."})

        watched
        (wait-for-run bob run-id)

        session-id
        (:session-id watched)

        activity
        (when session-id
          (or (wait-until 240000 #(let [found (result bob {:query-id :get-session-activity :session-id session-id})]
                                    (when (and (= :idle (:state found)) (seq (:tools found)) (pos? (:blocked found 0)))
                                      found)))
              (result bob {:query-id :get-session-activity :session-id session-id})))]
    (check "monitor plugin reports tool calls" (some #(= "bash" (:tool %)) (:tools activity)) (:tools activity))
    (check "harness policy blocks denied commands" (>= (or (:blocked activity) 0) 1) (:blocked activity))
    (check "carol cannot read the session activity"
           (= 404 (error-status (query carol {:query-id :get-session-activity :session-id session-id}))))
    (when session-id
      (let [output
            (in-sandbox session-id "echo x >> /etc/opencode/opencode/plugins/platform-monitor.js 2>&1 || echo denied")]
        (check "monitor plugin is read-only" (or (string/includes? output "denied") (string/includes? output "Permission")) output))
      (command bob {:cmd-id :stop-session :id session-id}))))

(defn cancel-and-retry
  [{:keys [alice erin]}]
  (let [run-id
        (start-run alice "demo" "requirements" {:brief "This run is cancelled straight away by the e2e test."})]
    (check "other users cannot cancel a run" (= 404 (error-status (command erin {:cmd-id :cancel-run :id run-id}))))
    (let [cancelled
          (command alice {:cmd-id :cancel-run :id run-id})

          state
          (wait-until 30000 #(let [found (result alice {:query-id :get-run :run-id run-id})]
                               (when (= :cancelled (:status found)) found)))]
      (check "runs can be cancelled" (and (:ok cancelled) state) (or (:error cancelled) (:status (result alice {:query-id :get-run :run-id run-id})))))
    (let [retry-id
          (UUID/randomUUID)

          retried
          (command alice {:cmd-id :retry-run :id retry-id :attrs {:source-id run-id}})

          run
          (result alice {:query-id :get-run :run-id retry-id})]
      (check "runs can be retried" (and (:ok retried) (= (str run-id) (str (get-in run [:trigger :retry-of])))) (or (:error retried) (:trigger run)))
      (when (:ok retried)
        (command alice {:cmd-id :cancel-run :id retry-id})))))

(defn interactive-session
  [{:keys [alice bob carol]} run]
  (check "interactive session launches" (= :completed (:status run)) (or (:error run) (:status run)))
  (let [session
        (or (:session run) {})

        session-id
        (:id session)]
    (check "interactive session is private to its owner" (and (= :private (:visibility session)) (= "bob" (:sub session))) session)
    (check "the run carries the session as the UI shows it"
           (and (true? (:is-owner session)) (contains? session :open-url) (not (contains? session :secret-key)))
           (keys session))
    (let [seen
          (query alice {:query-id :get-session :session-id session-id})]
      (check "team admins see a private session but cannot work in it" (= :private (get-in seen [:ok :open-blocked])) seen))
    (check "non-members cannot see the session" (= 404 (error-status (query carol {:query-id :get-session :session-id session-id}))))
    (when session-id
      (let [{:keys [entry location host-response opened]}
            (session-entry bob session)]
        (check "session entry redirects through the handoff" (= 303 (:status entry)) (:status entry))
        (check "the handoff goes to the session host" (string/starts-with? (str location) (str "http://" session-id ".")) location)
        (check "the session host sets its cookie and drops the handoff token"
               (and (= 302 (:status host-response)) (not (string/includes? (str (get-in host-response [:headers "location"])) "handoff")))
               (select-keys host-response [:status :headers]))
        (check "the session opens through Traefik with forward auth" (= 200 (:status opened)) (:status opened)))
      (let [stranger
            (send! (:client (login "carol")) "GET" (str "http://" session-id ".localhost:8080/") {})]
        (check "others are sent to log in instead of reaching the session" (contains? #{302 401 404} (:status stranger)) (:status stranger)))
      (section "Sandbox security (probes from inside the session container)")
      (let [output
            (in-sandbox session-id "wget -q -T 5 -O /dev/null https://example.com && echo REACHABLE || echo blocked")]
        (check "session has no internet access" (string/includes? output "blocked") output))
      (let [output
            (in-sandbox session-id "echo x >> /etc/opencode/opencode/opencode.json && echo WRITABLE || echo denied")]
        (check "OpenCode config is read-only" (string/includes? output "denied") output))
      (let [output
            (in-sandbox session-id "touch /usr/x 2>/dev/null && echo WRITABLE || echo denied; id -u")]
        (check "root filesystem is read-only and the agent is not root"
               (and (string/includes? output "denied") (string/ends-with? (string/trim output) "1000"))
               output))
      (let [config
            (sandbox-config session-id)

            llm-key
            (get-in config [:provider :platform :options :apiKey])

            post
            (fn [key model]
              (in-sandbox session-id
                          (str "wget -q -O- --header 'Authorization: Bearer " key "' --header 'Content-Type: application/json' "
                               "--post-data '{\"model\":\"" model "\",\"messages\":[]}' http://manager:8001/llm/v1/chat/completions 2>&1; true")))]
        (check "gateway rejects a forged key" (string/includes? (post "forged.key" "claude-sonnet-5.5") "401"))
        (check "gateway rejects models outside the harness" (string/includes? (post llm-key "gpt-not-allowed") "403"))
        (check "the session password is not an LLM key"
               (string/includes? (post (in-sandbox session-id "printf %s \"$OPENCODE_SERVER_PASSWORD\"") "claude-sonnet-5.5") "401"))
        (check "the sandbox cannot reach the public listener"
               (string/includes? (in-sandbox session-id "wget -q -T 5 -O- http://manager:8000/healthz >/dev/null 2>&1 && echo reachable || echo blocked")
                                 "blocked"))
        (check "the sandbox listener serves only the gateways"
               (string/includes? (in-sandbox session-id "wget -q -S -T 5 -O- http://manager:8001/api/auth/personas 2>&1; true")
                                 "404"))))))

(defn result-runs
  [{:keys [alice]} runs]
  (section "Results")
  (doseq [[run-name run-id verify] runs]
    (let [run
          (wait-for-run alice run-id)]
      (when (check (str run-name " run completes") (= :completed (:status run)) (or (:error run) (:status run)))
        (check (str run-name " run used the metered gateway")
               (and (pos? (get-in run [:usage :calls] 0)) (pos? (get-in run [:usage :prompt-tokens] 0)))
               (:usage run))
        (check (str run-name " session stopped afterwards")
               (wait-until 120000 #(= :stopped (get-in (result alice {:query-id :get-run :run-id run-id}) [:session :status])))
               (get-in run [:session :status]))
        (audit-ok alice (:session-id run))
        (case verify
          :document
          (check "analysis produced a document" (> (count (str (get-in run [:result :document :markdown]))) 300))

          :implementation
          (let [patch
                (str (get-in run [:result :patch]))

                tests
                (string/lower-case (string/join " " (get-in run [:result :implementation :tests])))]
            (check "implementation produced a patch with tests"
                   (and (string/includes? patch "pricing.py") (string/includes? patch "def test_"))
                   (get-in run [:result :files]))
            (check "implementation ran the tests" (some #(string/includes? tests %) ["ok" "pass" "ran"]) tests))

          :review
          (let [{:keys [review plan posted branch] :as outcome}
                (:result run)]
            (check "review has a verdict" (:verdict review) outcome)
            (check "review reviewed the branch against main" (= {:name "e2e-review" :base "main"} branch) branch)
            (check "review found an issue in pricing.py"
                   (some #(string/includes? (str (:path %)) "pricing.py") (:findings review))
                   (:findings review))
            (check "review planned comments and posted none"
                   (and (pos? (or (:comments plan) 0)) (= 0 posted))
                   (select-keys outcome [:plan :posted]))))))))

(defn- tools-call
  [session-id key tool]
  (in-sandbox session-id
              (str "wget -q -S -O- --header 'X-Platform-Key: " key "' --header 'Content-Type: application/json' "
                   "--header 'Accept: application/json, text/event-stream' "
                   "--post-data '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" tool "\",\"arguments\":{}}}' "
                   "http://manager:8001/mcp-gw/bitbucket 2>&1; true")))

(defn credentials-and-tools
  [{:keys [alice bob carol erin]}]
  (section "Credentials, MCP gateway, fork and lock")
  (let [mine
        (result erin {:query-id :get-my-credentials})

        states
        (into {} (map (juxt :id identity)) (:credentials mine))]
    (check "people see the credentials their teams need" (get-in states ["JIRA_TOKEN" :required]) (keys states))
    (check "new credentials are offered at login" (some #{"JIRA_TOKEN"} (:pending mine)) (:pending mine)))
  (let [response
        (outcome (request bob "PUT" "/api/credentials/BITBUCKET_TOKEN" {:secret "definitely-not-a-valid-token"}))]
    (check "invalid credentials are rejected before saving"
           (and (= 422 (error-status response)) (string/includes? (error-message response) "Not saved"))
           response))
  (check "only admins set platform values"
         (= 403 (error-status (outcome (request carol "PUT" "/api/admin/credentials/BITBUCKET_TOKEN" {:secret "x"})))))
  (if-not (.isFile token-file)
    (check "Bitbucket token available for the tool checks (local/secrets/BITBUCKET_TOKEN)" false)
    (let [token
          (string/trim (slurp token-file))

          saved
          (outcome (request bob "PUT" "/api/credentials/BITBUCKET_TOKEN" {:secret token}))]
      (check "a valid personal credential is checked and saved" (:ok saved) saved)
      (check "credential values are never returned by the API"
             (not (string/includes? (pr-str (result alice {:query-id :list-admin-credentials})) token)))
      (let [run
            (wait-for-run bob (start-run bob "demo" "bitbucket-workspace" {:title "e2e tools"}))

            session-id
            (:session-id run)]
        (when (check "a workspace with MCP tools launches" (= :completed (:status run)) (or (:error run) (:status run)))
          (let [tools-key
                (get-in (sandbox-config session-id) [:mcp :bitbucket :headers :X-Platform-Key])

                allowed
                (tools-call session-id tools-key "whoami")

                blocked
                (tools-call session-id tools-key "delete_repository")

                forged
                (tools-call session-id (str session-id ".forged") "whoami")]
            (check "the gateway calls allowed tools with the person's credential" (string/includes? allowed "display_name") allowed)
            (check "the gateway blocks tools outside the allow-list" (string/includes? blocked "not allowed") blocked)
            (check "the gateway rejects forged keys" (string/includes? forged "401") forged))
          (let [events
                (result bob {:query-id :list-audit-events :session-id session-id :after 0 :limit 1000})

                sources
                (keep #(get-in % [:payload :source]) (filter #(= "mcp" (name (:type %))) events))]
            (check "MCP calls are audited with whose credential was used" (some #(= "user" (name %)) sources) (vec sources)))
          (let [output
                (in-sandbox session-id "wget -q -T 5 -O- http://mcp-bitbucket:8000/ >/dev/null 2>&1 && echo reachable || echo blocked")]
            (check "sandboxes cannot reach MCP servers directly" (string/includes? output "blocked") output))
          (let [output
                (in-sandbox session-id "echo x >> /etc/opencode/opencode/skills/pr-triage/SKILL.md 2>/dev/null && echo writable || echo denied")]
            (check "skills are mounted read-only" (string/includes? output "denied") output))
          (let [seen
                (result alice {:query-id :get-session :session-id session-id})]
            (check "others cannot work in a session that uses personal credentials" (= :personal (:open-blocked seen)) (:open-blocked seen)))
          (check "they cannot start it either" (= 409 (error-status (command alice {:cmd-id :start-session :id session-id}))))
          (let [fork-id
                (UUID/randomUUID)

                forked
                (command alice {:cmd-id :fork-session :id fork-id :attrs {:source-id session-id}})

                fork
                (result alice {:query-id :get-session :session-id fork-id})]
            (check "they can fork it into their own private copy"
                   (and (:ok forked) (= "alice" (:sub fork)) (= :private (:visibility fork)))
                   (or (:error forked) fork))
            (check "the fork starts with its own keys"
                   (wait-until 180000 #(= :running (:status (result alice {:query-id :get-session :session-id fork-id}))))
                   (:status (result alice {:query-id :get-session :session-id fork-id})))
            (let [fork-config
                  (sandbox-config fork-id)]
              (check "the fork's sandbox keys are derived for the fork"
                     (string/starts-with? (str (get-in fork-config [:provider :platform :options :apiKey])) (str fork-id))
                     (get-in fork-config [:provider :platform :options :apiKey])))
            (command alice {:cmd-id :stop-session :id fork-id}))
          (check "non-members cannot fork it"
                 (= 404 (error-status (command carol {:cmd-id :fork-session :id (UUID/randomUUID) :attrs {:source-id session-id}}))))
          (check "only the owner can lock it" (= 403 (error-status (command alice {:cmd-id :lock-session :id session-id}))))
          (let [locked
                (command bob {:cmd-id :lock-session :id session-id})]
            (check "the owner can lock it"
                   (and (:ok locked) (= "bob" (:locked-by (result bob {:query-id :get-session :session-id session-id}))))
                   locked))
          (check "nobody can start a locked session" (= 409 (error-status (command bob {:cmd-id :start-session :id session-id}))))
          (check "a locked session can still be forked"
                 (:ok (command bob {:cmd-id :fork-session :id (UUID/randomUUID) :attrs {:source-id session-id}})))))
      (command bob {:cmd-id :delete-my-credential :attrs {:credential-id "BITBUCKET_TOKEN"}}))))

(defn usage-and-metrics
  [{:keys [alice bob]}]
  (section "Usage and metrics")
  (let [usage
        (result alice {:query-id :get-admin-usage :days 1})

        users
        (into {} (map (juxt :username identity)) (result alice {:query-id :list-admin-users}))

        overview
        (result alice {:query-id :get-admin-overview})]
    (check "admin usage shows today's LLM calls" (some #(pos? (:calls %)) usage) usage)
    (check "admin usage is priced" (some :cost usage) usage)
    (check "tokens are attributed to the people who used them" (pos? (get-in users ["bob" :tokens-30d] 0)) (get users "bob"))
    (check "runs are counted per person" (pos? (get-in users ["alice" :runs-30d] 0)) (get users "alice"))
    (check "the overview counts today's runs" (seq (:runs-today overview)) (:runs-today overview))
    (check "the overview counts every session" (pos? (get-in overview [:sessions :total] 0)) (:sessions overview)))
  (let [{:keys [status body]}
        (request alice "GET" "/metrics")]
    (check "admins can read Prometheus metrics" (and (= 200 status) (string/includes? (str body) "opencode_llm_tokens_total")) status))
  (check "non-admins cannot read metrics" (= 403 (:status (request bob "GET" "/metrics")))))

(def probe-image
  "opencode-platform/opencode-dev:1.19.5")

(defn on-sandbox-network
  "Runs script in a throwaway container on the sandbox network, where a
   sandbox would send it from."
  [script]
  (sh "docker" "run" "--rm" "--network" "opencode-sandbox" "--entrypoint" "sh" probe-image "-c" script))

(defn- first-line
  [text]
  (first (string/split-lines (string/trim (str text)))))

(defn- container-on-sandbox-network
  [service]
  (first-line (sh "docker" "ps" "-q" "--filter" "network=opencode-sandbox"
                  "--filter" (str "label=com.docker.compose.service=" service))))

(defn- address-on
  [network container]
  (when container
    (string/trim (sh "docker" "inspect" "-f" (str "{{with index .NetworkSettings.Networks \"" network "\"}}{{.IPAddress}}{{end}}")
                     container))))

(defn- sandbox-gateway
  []
  (string/trim (sh "docker" "network" "inspect" "-f" "{{range .IPAM.Config}}{{.Gateway}}{{end}}" "opencode-sandbox")))

(defn api-routes
  "[label method url headers] for every way a sandbox could try to reach the
   manager's API: the public listener directly, through Traefik (by name, by
   address, with the platform's Host), through ports published on the host,
   and Traefik's own API."
  []
  (let [gateway
        (sandbox-gateway)

        traefik
        (container-on-sandbox-network "traefik")

        manager
        (container-on-sandbox-network "manager")

        platform-host
        ["Host: localhost"]]
    (concat
     (for [[label base headers] [["manager:8000" "http://manager:8000" []]
                                 ["manager-sandbox:8000" "http://manager-sandbox:8000" []]
                                 ["manager-public:8000" "http://manager-public:8000" []]
                                 ["manager on opencode-net" (str "http://" (address-on "opencode-net" manager) ":8000") []]
                                 ["sandbox listener" "http://manager:8001" []]
                                 ["traefik" "http://traefik" platform-host]
                                 ["traefik on opencode-sandbox" (str "http://" (address-on "opencode-sandbox" traefik)) platform-host]
                                 ["traefik on opencode-net" (str "http://" (address-on "opencode-net" traefik)) platform-host]
                                 ["host port 8080 via the sandbox gateway" (str "http://" gateway ":8080") platform-host]
                                 ["host port 8080 via docker0" "http://172.17.0.1:8080" platform-host]]
           [method path] [["GET" "/api/auth/personas"] ["POST" "/api/query"] ["POST" "/api/command"]]]
       [(str method " " path " via " label) method (str base path) headers])
     [["Traefik API" "GET" "http://traefik:8080/api/rawdata" []]
      ["Traefik API via the host port" "GET" (str "http://" gateway ":9090/api/rawdata") []]])))

(defn- status-script
  "Shell printing label=<HTTP status>, or label= when no connection is made."
  [[label method url headers]]
  (str "printf '%s=' '" label "'; wget -q -S -T 5 -O /dev/null "
       (string/join " " (map #(str "--header '" % "'") headers))
       (when (= "POST" method) " --header 'Content-Type: application/json' --post-data '{}'")
       " '" url "' 2>&1 | awk '/^ *HTTP\\//{code=$2} END{print code}'"))

(defn route-statuses
  "{label status} of every api-routes request sent from inside the session."
  [session-id]
  (let [output
        (in-sandbox session-id (string/join "; " (map status-script (api-routes))))]
    (into {}
          (keep #(let [[label status] (string/split % #"=" 2)]
                   (when (seq label) [label (string/trim (str status))])))
          (string/split-lines output))))

(defn sandbox-cannot-reach-api
  [session-id]
  (let [statuses
        (route-statuses session-id)

        reached
        (into {} (remove (fn [[_ status]] (contains? #{"" "404"} status))) statuses)]
    (check "a sandbox can't reach /api/* by any route (direct, Traefik, host ports)"
           (and (>= (count statuses) 30) (empty? reached))
           (if (seq reached) reached statuses))))

(defn- telemetry-key
  [session-id]
  (second (re-find #"const KEY = \"([^\"]+)\""
                   (in-sandbox session-id "cat /etc/opencode/opencode/plugins/platform-monitor.js"))))

(defn- post-from-sandbox
  [session-id url headers body]
  (in-sandbox session-id
              (str "wget -q -S -O- "
                   (string/join " " (map #(str "--header '" % "'") headers))
                   " --header 'Content-Type: application/json' --post-data '" body "' " url " 2>&1; true")))

(defn- telemetry-call
  [session-id key body]
  (post-from-sandbox session-id "http://manager:8001/api/telemetry" [(str "X-Platform-Key: " key)] body))

(defn telemetry
  [{:keys [bob carol]} session-id]
  (section "Telemetry")
  (let [key
        (telemetry-key session-id)

        llm-key
        (get-in (sandbox-config session-id) [:provider :platform :options :apiKey])

        report
        "{\"kind\":\"tool.end\",\"tool\":\"e2e_probe\",\"call_id\":\"e2e-telemetry\",\"duration_ms\":7}"

        accepted
        (telemetry-call session-id key report)]
    (check "the monitor plugin's key reports telemetry" (string/includes? accepted "204") accepted)
    (check "reported tool calls show in the live activity"
           (wait-until 30000 #(some (fn [tool] (= "e2e_probe" (:tool tool)))
                                    (:tools (result bob {:query-id :get-session-activity :session-id session-id}))))
           (:tools (result bob {:query-id :get-session-activity :session-id session-id})))
    (check "telemetry refuses the LLM key" (string/includes? (telemetry-call session-id llm-key report) "401"))
    (check "telemetry refuses a forged key" (string/includes? (telemetry-call session-id (str session-id ".forged") report) "401"))
    (check "telemetry rejects unknown report kinds"
           (string/includes? (telemetry-call session-id key "{\"kind\":\"steal\"}") "422"))
    (check "the telemetry key is no LLM key"
           (string/includes? (post-from-sandbox session-id "http://manager:8001/llm/v1/chat/completions"
                                                [(str "Authorization: Bearer " key)]
                                                "{\"model\":\"claude-sonnet-5.5\",\"messages\":[]}")
                             "401"))
    (check "the LLM gateway lists only the harness's models"
           (let [listed
                 (in-sandbox session-id (str "wget -q -O- --header 'Authorization: Bearer " llm-key "' http://manager:8001/llm/v1/models 2>&1; true"))]
             (and (string/includes? listed "claude-sonnet-5.5") (not (string/includes? listed "claude-opus-5.5")))))
    (check "people outside the team can't read the live activity"
           (= 404 (error-status (query carol {:query-id :get-session-activity :session-id session-id}))))))

(defn- export-lines
  [person session-id]
  (let [{:keys [status headers body]}
        (request person "GET" (str "/api/sessions/" session-id "/audit/export"))]
    {:status status
     :content-type (get headers "content-type")
     :lines (when (= 200 status)
              (mapv #(jsonista/read-value % jsonista/keyword-keys-object-mapper)
                    (remove string/blank? (string/split-lines (str body)))))}))

(defn conversations-and-audit
  [{:keys [bob carol]} session-id]
  (section "Conversations and audit trail")
  (let [conversations
        (result bob {:query-id :list-conversations :session-id session-id})]
    (check "a session lists its conversations, each with its own open link"
           (and (seq conversations) (every? #(seq (str (:open-path %))) conversations))
           conversations))
  (check "people outside the team can't list its conversations"
         (= 404 (error-status (query carol {:query-id :list-conversations :session-id session-id}))))
  (let [events
        (result bob {:query-id :list-audit-events :session-id session-id :after 0 :limit 1000})

        {:keys [status content-type lines]}
        (export-lines bob session-id)]
    (check "the audit trail exports as JSON lines"
           (and (= 200 status) (string/includes? (str content-type) "ndjson")
                (seq lines) (every? #(and (:seq %) (:hash %) (contains? % :payload)) lines))
           [status content-type (count lines)])
    (check "the export holds every audit record" (= (count events) (count lines)) [(count events) (count lines)])
    (check "the export keeps the order of the hash chain" (= (map :seq lines) (sort (map :seq lines)))))
  (check "people outside the team can't export the audit trail"
         (not= 200 (:status (export-lines carol session-id))))
  (check "nobody can export it without logging in"
         (= 401 (:status (send! (http-client) "GET" (str base-url "/api/sessions/" session-id "/audit/export") {}))))
  (check "people outside the team can't verify the audit trail"
         (= 404 (error-status (query carol {:query-id :verify-audit :session-id session-id})))))

(defn- session-of
  [person session-id]
  (query person {:query-id :get-session :session-id session-id}))

(defn- session-status
  [person session-id]
  (get-in (session-of person session-id) [:ok :status]))

(defn- listed?
  [person session-id]
  (boolean (some #(= (str session-id) (str (:id %))) (result person {:query-id :list-sessions}))))

(defn- llm-call-from-network
  [key]
  (on-sandbox-network
   (str "wget -q -S -O- --header 'Authorization: Bearer " key "' --header 'Content-Type: application/json' "
        "--post-data '{\"model\":\"claude-sonnet-5.5\",\"messages\":[]}' http://manager:8001/llm/v1/chat/completions 2>&1; true")))

(defn stored-audit-events
  "Audit records of a session in the event store, read with psql."
  [session-id]
  (let [postgres
        (first-line (sh "docker" "ps" "-q" "--filter" "network=opencode-net" "--filter" "label=com.docker.compose.service=postgres"))

        output
        (sh "docker" "exec" postgres "psql" "-U" "postgres" "-tAc"
            (str "select count(*) from main.event_store where aggregate_id = '" (uuid/named (str "audit:" session-id)) "'"))]
    (or (parse-long (string/trim output)) 0)))

(defn session-management
  [{:keys [alice bob carol]} session-id]
  (section "Session management: rename, sharing, stop, start, delete")
  (let [update!
        (fn [person attrs] (command person {:cmd-id :update-session :id session-id :attrs attrs}))

        llm-key
        (get-in (sandbox-config session-id) [:provider :platform :options :apiKey])

        audit-before-delete
        (atom 0)]
    (check "the owner can rename a session and describe it"
           (and (:ok (update! bob {:name "e2e renamed" :description "Renamed by the e2e test"}))
                (= ["e2e renamed" "Renamed by the e2e test"]
                   ((juxt :name :description) (:ok (session-of bob session-id))))))
    (check "a session name can't be empty" (= 422 (error-status (update! bob {:name ""}))))
    (check "people outside the team can't change a session" (= 404 (error-status (update! carol {:name "x"}))))
    (check "the owner can share it with the team" (:ok (update! bob {:visibility :team})))
    (check "shared with the team, team admins can work in it"
           (let [seen (:ok (session-of alice session-id))]
             (and (= :team (:visibility seen)) (true? (:can-open seen)))))
    (check "people outside the team still can't see it" (= 404 (error-status (session-of carol session-id))))
    (check "the owner can share it with everyone" (:ok (update! bob {:visibility :public})))
    (let [seen
          (session-of carol session-id)]
      (check "shared with everyone, anyone can see it and work in it"
             (and (true? (get-in seen [:ok :can-open])) (false? (get-in seen [:ok :can-manage])))
             seen))
    (check "a public session is listed for everyone" (listed? carol session-id))
    (check "people who may work in a session can't stop it" (= 403 (error-status (command carol {:cmd-id :stop-session :id session-id}))))
    (check "nor change its sharing" (= 403 (error-status (update! carol {:visibility :private}))))
    (check "nor lock it" (= 403 (error-status (command carol {:cmd-id :lock-session :id session-id}))))
    (update! bob {:visibility :private})
    (check "made private again, it is no longer listed for others" (not (listed? carol session-id)))
    (check "the owner can stop it" (:ok (command bob {:cmd-id :stop-session :id session-id})))
    (check "it stops" (wait-until 120000 #(= :stopped (session-status bob session-id))) (session-status bob session-id))
    (check "a stopped session's LLM key is refused" (string/includes? (llm-call-from-network llm-key) "401"))
    (check "the owner can start it again" (:ok (command bob {:cmd-id :start-session :id session-id})))
    (check "it runs again with its workspace"
           (and (wait-until 180000 #(= :running (session-status bob session-id)))
                (string/includes? (in-sandbox session-id "ls repo") "demo"))
           (session-status bob session-id))
    (check "team admins can stop a member's session" (:ok (command alice {:cmd-id :stop-session :id session-id})))
    (wait-until 120000 #(= :stopped (session-status bob session-id)))
    (check "people outside the team can't delete it" (= 404 (error-status (command carol {:cmd-id :delete-session :id session-id}))))
    (reset! audit-before-delete (count (result bob {:query-id :list-audit-events :session-id session-id :after 0 :limit 1000})))
    (check "the owner can delete it" (:ok (command bob {:cmd-id :delete-session :id session-id})))
    (check "a deleted session is gone"
           (wait-until 120000 #(and (not (listed? bob session-id)) (= 404 (error-status (session-of bob session-id)))))
           (session-of bob session-id))
    (check "its container is removed"
           (wait-until 120000 #(string/blank? (sh "docker" "ps" "-aq" "--filter" (str "name=" (container-of session-id))))))
    (check "its audit trail is kept in the store"
           (and (pos? @audit-before-delete) (>= (stored-audit-events session-id) @audit-before-delete))
           [(stored-audit-events session-id) @audit-before-delete])))

(defn authentication-and-browser
  [{:keys [alice dave]}]
  (section "Authentication and browser hardening")
  (check "get-me tells platform admins apart"
         (and (true? (:platform-admin (result alice {:query-id :get-me})))
              (false? (:platform-admin (result dave {:query-id :get-me})))))
  (check "the API needs a login"
         (= 401 (:status (send! (http-client) "POST" (str base-url "/api/query")
                                {:body (util/to-json (envelope {:query {:query-id :get-me}}))}))))
  (check "the reserved username platform can't log in"
         (= 422 (:status (send! (http-client) "POST" (str base-url "/api/auth/login") {:body (util/to-json {:username "platform"})}))))
  (check "invalid usernames can't log in"
         (= 422 (:status (send! (http-client) "POST" (str base-url "/api/auth/login") {:body (util/to-json {:username "Not A Name!"})}))))
  (let [person
        (login "erin")

        logged-out
        (request person "POST" "/api/auth/logout")]
    (check "logging out ends the login" (and (= 204 (:status logged-out)) (= 401 (:status (request person "POST" "/api/query" (envelope {:query {:query-id :get-me}})))))))
  (let [sync-body
        (util/to-json (envelope {:commands [{:cmd-id :stop-idle-sessions}]}))

        from
        (fn [headers]
          (:status (send! (:client alice) "POST" (str base-url "/api/command") {:body sync-body :headers headers})))]
    (check "a cookie request the browser marks same-origin is accepted"
           (= 200 (from {"X-Requested-With" nil "Sec-Fetch-Site" "same-origin"})))
    (check "a cookie request from the UI's origin is accepted"
           (= 200 (from {"X-Requested-With" nil "Origin" base-url})))
    (check "a cookie request without any origin proof is refused"
           (= 403 (from {"X-Requested-With" nil})))
    (check "a cross-site cookie request is refused even when the browser says so"
           (= 403 (from {"X-Requested-With" nil "Sec-Fetch-Site" "cross-site"}))))
  (let [{:keys [status headers]}
        (send! (http-client) "GET" (str base-url "/") {})

        policy
        (str (get headers "content-security-policy"))]
    (check "the UI is served with a strict Content-Security-Policy"
           (and (= 200 status)
                (every? #(string/includes? policy %) ["default-src 'self'" "frame-ancestors 'none'" "object-src 'none'"])
                (not (string/includes? policy "unsafe-eval")))
           policy)
    (check "the UI can't be framed" (= "DENY" (get headers "x-frame-options")) headers))
  (check "people can't send the platform's own commands"
         (every? #(= 403 (error-status (command alice %)))
                 (let [session-id
                       (UUID/randomUUID)

                       now
                       (str (java.time.Instant/now))]
                   [{:cmd-id :append-audit :attrs {:session-id session-id :ts now :type "tool" :key "forged" :body "{}"}}
                    {:cmd-id :record-usage :attrs {:session-id session-id :model "claude-sonnet-5.5" :ts now}}
                    {:cmd-id :submit-run-result :id (UUID/randomUUID) :attrs {}}
                    {:cmd-id :record-session-started :id session-id :attrs {}}
                    {:cmd-id :upsert-directory-row
                     :attrs {:identity "user:e2e-forged" :collection :runs :row {:id session-id}}}])))
  (check "people can't run the platform's own queries"
         (every? #(= 403 (error-status (query alice %)))
                 [{:query-id :get-personas}
                  {:query-id :get-stored-credential :credential-id "BITBUCKET_TOKEN" :owner "bob"}
                  {:query-id :list-session-rows}])))

(defn catalog-and-library
  [{:keys [alice bob carol]}]
  (section "Teams, harnesses and activities")
  (check "people see the teams they belong to with their role"
         (= {"demo" :member "data" :member}
            (into {} (keep #(when (#{"demo" "data"} (:id %)) [(:id %) (keyword (:role %))])) (result bob {:query-id :list-teams}))))
  (let [team
        (result alice {:query-id :get-team :team-id "demo"})

        valid
        (set (map :id (filter :valid (:definitions team))))]
    (check "a team lists its agents, harnesses and workflows"
           (every? valid ["engineer" "sandboxed" "workspace" "requirements" "implement-change" "review-branch"])
           valid))
  (let [harnesses
        (into {} (map (juxt (juxt :team-id :id) identity)) (result bob {:query-id :list-harnesses}))

        tools
        (get harnesses ["demo" "bitbucket-tools"])]
    (check "harnesses say what the sandbox may do"
           (= ["claude-sonnet-5.5" "gemini-3.5-flash"] (:models (get harnesses ["demo" "sandboxed"])))
           (get harnesses ["demo" "sandboxed"]))
    (check "a harness is ready when its credentials are available"
           (and (true? (:ready tools)) (= ["BITBUCKET_TOKEN"] (mapv :id (:credentials tools))))
           tools))
  (check "people outside a team don't see its harnesses"
         (not-any? #(= "demo" (:team-id %)) (result carol {:query-id :list-harnesses})))
  (let [schemas
        (result bob {:query-id :get-schemas})]
    (check "the activity schemas are published" (and (map? schemas) (seq schemas)) (keys schemas)))
  (check "pull requests can be listed only by team members"
         (= 404 (error-status (query carol {:query-id :list-open-pull-requests :team-id "demo" :workflow-id "implement-change"}))))
  (check "a workflow without a pull request provider has no pull requests to continue"
         (= [] (result bob {:query-id :list-open-pull-requests :team-id "demo" :workflow-id "implement-change"})))
  (check "people outside a team don't see its runs"
         (not-any? #(= "demo" (:team-id %)) (result carol {:query-id :list-runs}))))

(defn- credential-state
  [person credential-id]
  (first (filter #(= credential-id (:id %)) (:credentials (result person {:query-id :get-my-credentials})))))

(defn- admin-credential
  [person credential-id]
  (first (filter #(= credential-id (:id %)) (result person {:query-id :list-admin-credentials}))))

(defn- put-credential
  [person scope credential-id body]
  (outcome (request person "PUT" (str (if (= :platform scope) "/api/admin/credentials/" "/api/credentials/") credential-id) body)))

(defn credential-management
  [{:keys [alice erin]}]
  (section "Credential management")
  (let [ids
        (mapv :id (:credentials (result erin {:query-id :get-my-credentials})))

        seen
        (command erin {:cmd-id :mark-credentials-seen :attrs {:credentials ids}})

        pending
        (:pending (result erin {:query-id :get-my-credentials}))]
    (check "seen credentials aren't offered again; missing personal-only ones still are"
           (and (:ok seen) (= ["JIRA_TOKEN"] pending))
           pending))
  (let [saved
        (put-credential erin :user "JIRA_TOKEN" {:secret "e2e-personal-secret" :username "erin@example.com"})

        state
        (credential-state erin "JIRA_TOKEN")]
    (check "a personal credential without a check is saved" (:ok saved) saved)
    (check "it shows as the person's own with a hint, never the value"
           (and (= :own (keyword (:state state))) (seq (str (:hint state)))
                (not (string/includes? (pr-str (result erin {:query-id :get-my-credentials})) "e2e-personal-secret")))
           state)
    (check "once set, it is no longer offered at login" (empty? (:pending (result erin {:query-id :get-my-credentials})))))
  (check "a value can be replaced"
         (and (:ok (put-credential erin :user "JIRA_TOKEN" {:secret "e2e-replaced-secret" :username "erin@example.com"}))
              (= :own (keyword (:state (credential-state erin "JIRA_TOKEN"))))))
  (check "basic credentials need a username"
         (= 422 (error-status (put-credential erin :user "JIRA_TOKEN" {:secret "x"}))))
  (check "the person can remove their value"
         (and (:ok (command erin {:cmd-id :delete-my-credential :attrs {:credential-id "JIRA_TOKEN"}}))
              (= :missing (keyword (:state (credential-state erin "JIRA_TOKEN")))))
         (credential-state erin "JIRA_TOKEN"))
  (check "people can't set credentials the platform doesn't declare"
         (= 404 (error-status (put-credential erin :user "COPILOT_TOKEN" {:secret "x"}))))
  (when-let [platform-only
             (first (filter #(and (:declared %) (= :platform (keyword (:provided-by %))))
                            (result alice {:query-id :list-admin-credentials})))]
    (check "people can't set credentials only platform admins provide"
           (= 403 (error-status (put-credential erin :user (:id platform-only) {:secret "x" :username "x"})))))
  (check "admins can't set a personal-only credential for everyone"
         (= 422 (error-status (put-credential alice :platform "JIRA_TOKEN" {:secret "x" :username "y"}))))
  (let [response
        (put-credential alice :platform "BITBUCKET_TOKEN" {:secret "definitely-not-a-valid-token"})]
    (check "invalid platform values are rejected before saving"
           (and (= 422 (error-status response)) (string/includes? (error-message response) "Not saved"))
           response))
  (check "non-admins can't clear platform values"
         (= 403 (error-status (command erin {:cmd-id :delete-platform-credential :attrs {:credential-id "BITBUCKET_TOKEN"}}))))
  (when (.isFile token-file)
    (let [saved
          (put-credential alice :platform "BITBUCKET_TOKEN" {:secret (string/trim (slurp token-file))})

          row
          (admin-credential alice "BITBUCKET_TOKEN")]
      (check "admins set a platform value once it passes its check"
             (and (:ok saved) (= :admin (keyword (:platform-value row))) (seq (str (:hint row))))
             (or (:error saved) row))
      (check "clearing it falls back to the secret file"
             (and (:ok (command alice {:cmd-id :delete-platform-credential :attrs {:credential-id "BITBUCKET_TOKEN"}}))
                  (= :file (keyword (:platform-value (admin-credential alice "BITBUCKET_TOKEN")))))
             (admin-credential alice "BITBUCKET_TOKEN")))))

(defn- stop-session!
  [person session-id]
  (when session-id
    (command person {:cmd-id :stop-session :id session-id})))

(defn session-limit
  [{:keys [erin]}]
  (section "Team session limit")
  (let [runs
        (doall (for [n [1 2]]
                 (wait-for-run erin (start-run erin "payments" "workspace" {:title (str "e2e limit " n)}))))

        sessions
        (keep :session-id runs)

        refused
        (when (= 2 (count sessions))
          (wait-until 120000 #(let [response (command erin {:cmd-id :start-run :id (UUID/randomUUID)
                                                            :attrs {:team-id "payments" :workflow-id "workspace" :inputs {:title "e2e limit 3"}}})]
                                (if (:ok response)
                                  (do (Thread/sleep 1000) nil)
                                  response))))]
    (check "a team's sessions start up to its limit" (= 2 (count sessions)) (mapv :status runs))
    (check "one more is refused with the limit" (string/includes? (error-message refused) "limit") refused)
    (doseq [session-id sessions]
      (stop-session! erin session-id))))

(def budget-body
  "A call of more than the ops team's daily budget (1000 tokens): the first
   is relayed, the next refused."
  (str "{\"model\":\"claude-sonnet-5.5\",\"max_tokens\":5,\"messages\":[{\"role\":\"user\",\"content\":\""
       (string/join " " (repeat 1500 "budget"))
       " Reply with ok.\"}]}"))

(defn token-budget
  [{:keys [henry]}]
  (section "Team token budget")
  (let [run
        (wait-for-run henry (start-run henry "ops" "workspace" {:title "e2e budget"}))

        session-id
        (:session-id run)

        llm-key
        (get-in (sandbox-config session-id) [:provider :platform :options :apiKey])

        call
        #(post-from-sandbox session-id "http://manager:8001/llm/v1/chat/completions"
                            [(str "Authorization: Bearer " llm-key)]
                            budget-body)

        first-call
        (when session-id (call))

        refused
        (when session-id
          (wait-until 60000 #(let [output (call)] (when (string/includes? output "429") output))))]
    (check "the first call within the budget is relayed" (string/includes? (str first-call) "200 OK") first-call)
    (check "once the team used its daily budget, the gateway refuses calls"
           (string/includes? (str refused) "429 Too Many Requests")
           refused)
    (stop-session! henry session-id)))

(defn platform-started-sessions
  [{:keys [alice carol henry]}]
  (section "Platform-started sessions (schedule)")
  (let [run
        (wait-until (* 8 60 1000) #(first (filter :platform-started (result henry {:query-id :list-runs :team-id "ops"}))))

        detail
        (when run
          (wait-until 300000 #(let [found (result henry {:query-id :get-run :run-id (:id run)})]
                                (when (:session-id found) found))))

        session-id
        (:session-id detail)]
    (check "a schedule starts runs by itself" (some? run))
    (check "they are marked as started by the platform"
           (and (true? (:platform-started detail)) (= "platform:schedule" (:created-by detail)))
           (select-keys detail [:platform-started :created-by]))
    (when session-id
      (let [seen
            (:ok (session-of carol session-id))]
        (check "its session is public but only the team may work in it (team credentials)"
               (and (= :public (keyword (:visibility seen))) (= :team-only (keyword (:open-blocked seen))))
               (select-keys seen [:visibility :open-blocked])))
      (check "people outside the team can't start it"
             (= 409 (error-status (command carol {:cmd-id :start-session :id session-id}))))
      (check "nor fork it"
             (= 403 (error-status (command carol {:cmd-id :fork-session :id (UUID/randomUUID) :attrs {:source-id session-id}}))))
      (check "team members may work in it" (nil? (get-in (session-of henry session-id) [:ok :open-blocked])))
      (check "platform admins can stop it" (:ok (stop-session! alice session-id))))))

(defn administration-actions
  [{:keys [alice bob]}]
  (section "Administration actions")
  (check "admins can ask the reaper to stop idle sessions" (:ok (command alice {:cmd-id :stop-idle-sessions})))
  (check "non-admins can't" (= 403 (error-status (command bob {:cmd-id :stop-idle-sessions}))))
  (let [bitbucket
        (first (filter #(= "bitbucket" (:id %)) (result alice {:query-id :list-mcp-servers})))]
    (check "admins see MCP servers: reachable, credential set, used by which harnesses"
           (and (true? (:reachable bitbucket)) (true? (:credential-ready bitbucket))
                (some #(string/includes? % "bitbucket-tools") (:harnesses bitbucket)))
           bitbucket))
  (check "admins see the scheduled workflows"
         (some #(= "trigger-ops-scheduled-workspace" (:id %)) (result alice {:query-id :list-schedules})))
  (doseq [[label q] [["MCP servers" {:query-id :list-mcp-servers}]
                     ["credentials" {:query-id :list-admin-credentials}]
                     ["schedules" {:query-id :list-schedules}]
                     ["usage" {:query-id :get-admin-usage :days 1}]
                     ["people" {:query-id :list-admin-users}]
                     ["connection tests" {:query-id :test-llm-connection :connection-id "copilot"}]]]
    (check (str "non-admins can't read admin " label) (= 403 (error-status (query bob q)))))
  (check "nobody reads metrics without logging in" (= 401 (:status (send! (http-client) "GET" (str base-url "/metrics") {})))))

(defn -main
  [& _args]
  (let [people
        (into {} (map (fn [username] [(keyword username) (login username)])) ["alice" "bob" "carol" "dave" "erin" "henry"])

        _
        (platform-and-access people)

        _
        (roles-and-administration people)

        _
        (authentication-and-browser people)

        _
        (catalog-and-library people)

        _
        (section "Activities")

        analysis
        (start-run (:alice people) "demo" "requirements" {:brief "Members get 5% off orders over 100 EUR, stackable with other discounts up to 20% in total."})

        implement
        (start-run (:alice people) "demo" "implement-change"
                   {:task "Add a keyword argument member: bool = False to total(). Members get an extra 5% off when the subtotal is over 100; the combined discount must not exceed 20%. Add unit tests and run them."})

        workspace
        (start-run (:bob people) "demo" "workspace" {:title "e2e workspace" :prompt "Run the unit tests in repo/ and tell me the result."})

        review
        (start-run (:alice people) "demo" "review-branch" {:branch "e2e-review"})

        workspace-run
        (wait-for-run (:bob people) workspace)

        workspace-session
        (get-in workspace-run [:session :id])]
    (monitoring-and-policy people)
    (cancel-and-retry people)
    (interactive-session people workspace-run)
    (when workspace-session
      (sandbox-cannot-reach-api workspace-session)
      (telemetry people workspace-session)
      (conversations-and-audit people workspace-session)
      (session-management people workspace-session))
    (result-runs people [["analysis" analysis :document] ["implement" implement :implementation] ["review" review :review]])
    (credentials-and-tools people)
    (credential-management people)
    (session-limit people)
    (token-budget people)
    (platform-started-sessions people)
    (administration-actions people)
    (usage-and-metrics people)
    (let [failed
          (map first (remove second @results))]
      (println (str "\n" (- (count @results) (count failed)) "/" (count @results) " checks passed"))
      (when (seq failed)
        (println "Failed:")
        (doseq [check-name failed]
          (println "  -" check-name)))
      (shutdown-agents)
      (System/exit (if (seq failed) 1 0)))))
