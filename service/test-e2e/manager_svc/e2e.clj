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
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util UUID]))

(def base-url
  (or (System/getenv "MANAGER_URL") "http://localhost:8080"))

(def run-timeout-ms
  (* 15 60 1000))

(def results
  (atom []))

(def started-runs
  "[person run-id] of every run a pass starts, cancelled and cleaned up at its end."
  (atom []))

(def started-sessions
  "[person session-id] of sessions a pass creates besides those of its runs (forks)."
  (atom []))

(defn track-run!
  [person run-id]
  (when run-id
    (swap! started-runs conj [person run-id]))
  run-id)

(defn track-session!
  [person session-id]
  (when session-id
    (swap! started-sessions conj [person session-id]))
  session-id)

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

(defonce timings
  (atom []))

(defn- seconds-since
  [started-ms]
  (quot (- (System/currentTimeMillis) started-ms) 1000))

(defn guarded
  "Runs f and records how long it took; an exception is a failed check named
   after title, so the pass goes on to its summary and cleanup."
  [title f]
  (let [started
        (System/currentTimeMillis)]
    (try
      (f)
      (catch Throwable e
        (check (str title " ran to its end") false (str (.getSimpleName (class e)) ": " (ex-message e)))
        nil)
      (finally
        (swap! timings conj [title (seconds-since started)])
        (println (str "  TIME  " title ": " (seconds-since started) " s"))))))

(defn- print-timings!
  []
  (println "\nSlowest sections:")
  (doseq [[title seconds] (take 15 (sort-by (comp - second) @timings))]
    (println (format "  %5d s  %s" seconds title))))

(defn session-host-url
  "The URL of a session's own host, next to the manager's (MANAGER_URL)."
  [session-id]
  (let [uri
        (URI/create base-url)]
    (str (.getScheme uri) "://" session-id "." (.getHost uri)
         (when (pos? (.getPort uri)) (str ":" (.getPort uri)))
         "/")))

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

(def auth-mode
  "The server's MANAGER_AUTH_MODE as /api/auth/config tells it."
  (delay
    (let [{:keys [status body]}
          (send! (http-client) "GET" (str base-url "/api/auth/config") {})]
      (if (= 200 status)
        (str (:mode (parse body)))
        "login"))))

(defn oidc-mode?
  []
  (= "oidc" @auth-mode))

(defn provider-password
  "The local Keycloak realm's password of a user (deploy/keycloak, README)."
  [username]
  username)

(defn- unescape-html
  [text]
  (-> text
      (string/replace "&amp;" "&")
      (string/replace "&quot;" "\"")
      (string/replace "&#39;" "'")))

(defn- form-body
  [params]
  (string/join "&" (for [[k v] params]
                     (str (URLEncoder/encode (str k) StandardCharsets/UTF_8) "="
                          (URLEncoder/encode (str v) StandardCharsets/UTF_8)))))

(defn- login-form-action
  "The action of Keycloak's login form (form id kc-form-login)."
  [html]
  (some->> (re-find #"<form[^>]*id=\"kc-form-login\"[^>]*>" (str html))
           (re-find #"action=\"([^\"]+)\"")
           second
           unescape-html))

(defn provider-login
  "Logs in through the identity provider's login page as a browser does.
   {:client :submitted (the login form's answer) :callback (the manager's
   answer to the provider's redirect)}."
  [username password]
  (let [client
        (http-client)

        started
        (send! client "GET" (str base-url "/api/auth/oidc/login?next=%2F") {})

        login-page
        (send! client "GET" (get-in started [:headers "location"]) {})

        action
        (or (login-form-action (:body login-page))
            (throw (ex-info (str "No login form at the identity provider (HTTP " (:status login-page) ")") {})))

        submitted
        (send! client "POST" action {:body (form-body {"username" username "password" password "credentialId" ""})
                                     :headers {"Content-Type" "application/x-www-form-urlencoded"
                                               "X-Requested-With" nil}})

        callback
        (when-let [location
                   (get-in submitted [:headers "location"])]
          (send! client "GET" location {}))]
    {:client client :submitted submitted :callback callback}))

(defn- password-login
  [username]
  (let [client
        (http-client)

        {:keys [status]}
        (send! client "POST" (str base-url "/api/auth/login") {:body (util/to-json {:username username})})]
    (when-not (= 200 status)
      (throw (ex-info (str "Login of " username " failed: " status) {})))
    client))

(defn login
  "A person logged in: through the identity provider in oidc mode
   (compose.keycloak.yml), else with the password-free test login."
  [username]
  (if (oidc-mode?)
    (let [{:keys [client callback]}
          (provider-login username (provider-password username))

          location
          (str (get-in callback [:headers "location"]))]
      (when-not (string/includes? location "complete=1")
        (throw (ex-info (str "Provider login of " username " failed: " (:status callback) " " location) {})))
      {:username username :client client})
    {:username username :client (password-login username)}))

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
  ([person team-id workflow-id inputs]
   (start-run person team-id workflow-id inputs nil))
  ([person team-id workflow-id inputs visibility]
   (let [run-id
         (UUID/randomUUID)

         attrs
         (cond-> {:team-id team-id :workflow-id workflow-id :inputs inputs}
           visibility (assoc :visibility visibility))

         response
         (command person {:cmd-id :start-run :id run-id :attrs attrs})]
     (check (str "start " team-id "/" workflow-id) (:ok response) (:error response))
     (when (:ok response)
       (track-run! person run-id)))))

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
  (let [synced-before
        (:synced-at (result alice {:query-id :get-tenant}))

        synced
        (command alice {:cmd-id :sync-tenant})]
    (check "tenant admins can sync their onboarding repository" (:ok synced) (:error synced))
    (check "the requested sync finishes"
           (wait-until 120000 #(let [tenant (result alice {:query-id :get-tenant})]
                                 (and (:synced-at tenant)
                                      (not= synced-before (:synced-at tenant))
                                      (nil? (:error-message tenant)))))
           [synced-before (select-keys (result alice {:query-id :get-tenant}) [:synced-at :error-message])]))
  (check "non-admin cannot sync" (= 403 (error-status (command bob {:cmd-id :sync-tenant}))))
  (let [team
        (result alice {:query-id :get-team :team-id "demo"})

        invalid
        (into {} (map (juxt :id :errors)) (remove :valid (:definitions team)))]
    (check "invalid workflow is rejected with a reason"
           (and (contains? invalid "github-review") (string/includes? (str invalid) "not granted"))
           invalid))
  (check "tenant members outside a team cannot see it, and are told why" (= 403 (error-status (query carol {:query-id :get-team :team-id "demo"}))))
  (check "someone of the tenant outside the team cannot start its workflows, and is told why"
         (= 403 (error-status (command carol {:cmd-id :start-run :id (UUID/randomUUID)
                                              :attrs {:team-id "demo" :workflow-id "requirements" :inputs {:brief (apply str (repeat 20 "x"))}}}))))
  (let [response
        (command alice {:cmd-id :start-run :id (UUID/randomUUID)
                        :attrs {:team-id "demo" :workflow-id "implement-change" :inputs {:task "short"}}})]
    (check "run inputs are validated" (= 422 (error-status response)) response))
  (check "marketplace lists the activities"
         (every? (set (map :id (result alice {:query-id :list-activities}))) ["session" "review" "implement" "analysis"])))

(defn roles-and-administration
  [{:keys [alice dave erin gina]}]
  (section "Roles and administration")
  (when-not (oidc-mode?)
    (let [personas
          (into {} (map (juxt :username identity)) (:data (request alice "GET" "/api/auth/personas")))]
      (check "login offers the dev users with their tenant and roles"
             (and (= "acme" (get-in personas ["alice" :tenant]))
                  (some #(string/includes? % "Tenant admin") (get-in personas ["alice" :roles]))
                  (some #(string/includes? (string/lower-case %) "payments") (get-in personas ["dave" :roles]))
                  (= "globex" (get-in personas ["gina" :tenant])))
             personas)))
  (check "a cookie request from another origin is refused"
         (= 403 (:status (send! (:client alice) "POST" (str base-url "/api/command")
                                {:body (util/to-json {:commands [{:cmd-id :sync-tenant}]})
                                 :headers {"X-Requested-With" nil "Origin" "http://evil.localhost:8080"}}))))
  (let [teams
        (into {} (map (juxt :id identity)) (result alice {:query-id :list-teams}))

        payments
        (get teams "payments")]
    (check "every team of the tenant is synced from its own repository"
           (and (= :ok (:status payments)) (seq (str (:commit payments))) (every? teams ["demo" "ops"]))
           payments)
    (check "a tenant's teams list holds no team of another tenant" (not (contains? teams "research")) (keys teams)))
  (check "team roles come from the claims, one per team"
         (= {"ops" :member "payments" :admin}
            (into {} (map (juxt :id (comp keyword :role))) (:teams (result dave {:query-id :get-me})))))
  (check "people see only their own teams" (= #{"ops" "payments"} (set (map :id (:teams (result dave {:query-id :get-me}))))))
  (check "team members cannot use the admin API" (= 403 (error-status (query erin {:query-id :get-admin-overview}))))
  (let [overview
        (result alice {:query-id :get-admin-overview})]
    (check "tenant admins get an overview of their tenant" (= 3 (:teams overview)) overview))
  (let [users
        (set (map :username (result alice {:query-id :list-admin-users})))]
    (check "the people list holds only people of the tenant" (not-any? #{"gina" "hank"} users) users))
  (check "another tenant doesn't exist for a tenant admin who names it"
         (= 404 (error-status (command gina {:cmd-id :sync-tenant :attrs {:tenant "acme"}}))))
  (let [credentials
        (result alice {:query-id :list-admin-credentials})]
    (check "credential status never includes values"
           (and (seq credentials) (not-any? #(or (contains? % :secret) (contains? % :value) (contains? % :sealed)) credentials))))
  (let [connection
        (:id (first (:llm-connections (result alice {:query-id :get-tenant}))))

        tested
        (result alice {:query-id :test-llm-connection :connection-id connection})]
    (check "tenant admins can test an LLM connection" (:ok tested) tested))
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
    (check "other users cannot cancel a run" (#{403 404} (error-status (command erin {:cmd-id :cancel-run :id run-id}))))
    (check "a run that is still active can't be run again"
           (= 409 (error-status (command alice {:cmd-id :retry-run :id (track-run! alice (UUID/randomUUID)) :attrs {:source-id run-id}}))))
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
        (check "the handoff goes to the session host" (string/includes? (str location) (str "://" session-id ".")) location)
        (check "the session host sets its cookie and drops the handoff token"
               (and (= 302 (:status host-response)) (not (string/includes? (str (get-in host-response [:headers "location"])) "handoff")))
               (select-keys host-response [:status :headers]))
        (check "the session opens through Traefik with forward auth" (= 200 (:status opened)) (:status opened)))
      (let [stranger
            (send! (:client (login "carol")) "GET" (session-host-url session-id) {})]
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
    (check "people see the credentials their teams need" (get-in states ["JIRA_TOKEN" :personal-only]) (keys states))
    (check "new credentials are offered at login" (some #{"JIRA_TOKEN"} (:pending mine)) (:pending mine))
    (check "credentials only admins provide are listed read-only with their state, never offered at login"
           (and (false? (get-in states ["NEXUS_TOKEN" :may-provide]))
                (keyword? (get-in states ["NEXUS_TOKEN" :state]))
                (not (some #{"NEXUS_TOKEN"} (:pending mine))))
           [(get states "NEXUS_TOKEN") (:pending mine)]))
  (check "people can't set their own value of a credential only admins provide"
         (#{403 422} (error-status (outcome (request erin "PUT" "/api/credentials/NEXUS_TOKEN" {:secret "x"})))))
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
      (try
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
              (track-session! alice fork-id)
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
            (check "people who can't see it cannot fork it"
                   (= 404 (error-status (command carol {:cmd-id :fork-session :id (UUID/randomUUID) :attrs {:source-id session-id}}))))
            (check "only the owner can lock it" (= 403 (error-status (command alice {:cmd-id :lock-session :id session-id}))))
            (let [locked
                  (command bob {:cmd-id :lock-session :id session-id})]
              (check "the owner can lock it"
                     (and (:ok locked) (= "bob" (:locked-by (result bob {:query-id :get-session :session-id session-id}))))
                     locked))
            (check "nobody can start a locked session" (= 409 (error-status (command bob {:cmd-id :start-session :id session-id}))))
            (let [fork-id
                  (track-session! bob (UUID/randomUUID))]
              (check "a locked session can still be forked"
                     (:ok (command bob {:cmd-id :fork-session :id fork-id :attrs {:source-id session-id}})))
              (command bob {:cmd-id :delete-session :id fork-id}))
            (command bob {:cmd-id :delete-session :id session-id})))
        (finally
          (command bob {:cmd-id :delete-my-credential :attrs {:credential-id "BITBUCKET_TOKEN"}}))))))

(defn usage-and-metrics
  [{:keys [alice bob oscar]}]
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
        (request oscar "GET" "/metrics")]
    (check "operators can read Prometheus metrics" (and (= 200 status) (string/includes? (str body) "opencode_llm_tokens_total")) status))
  (check "tenant admins cannot read the platform's metrics" (= 403 (:status (request alice "GET" "/metrics"))))
  (check "members cannot read metrics" (= 403 (:status (request bob "GET" "/metrics")))))

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
    (check "the result tools refuse a forged key"
           (string/includes? (post-from-sandbox session-id "http://manager:8001/mcp"
                                                [(str "X-Platform-Key: " session-id ".forged")
                                                 "Accept: application/json, text/event-stream"]
                                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                             "401"))
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
  [{:keys [alice bob carol gina]} session-id]
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
    (check "the owner can share it with everyone in the tenant" (:ok (update! bob {:visibility :tenant})))
    (let [seen
          (session-of carol session-id)]
      (check "shared with the tenant, anyone in it can see it and work in it"
             (and (true? (get-in seen [:ok :can-open])) (false? (get-in seen [:ok :can-manage])))
             seen))
    (check "a tenant-wide session is listed for everyone in the tenant" (listed? carol session-id))
    (check "people of another tenant still can't see it" (= 404 (error-status (session-of gina session-id))))
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
  (let [me
        (result alice {:query-id :get-me})]
    (check "get-me tells the tenant and tenant admins apart"
           (and (= "acme" (:tenant me))
                (true? (:tenant-admin me))
                (false? (boolean (:tenant-admin (result dave {:query-id :get-me})))))
           me))
  (when-not (oidc-mode?)
    (check "a dev user without a tenant is refused at login unless an operator"
           (= 403 (:status (send! (http-client) "POST" (str base-url "/api/auth/login") {:body (util/to-json {:username "nobody-e2e"})}))))
    (check "the reserved username platform can't log in"
           (= 422 (:status (send! (http-client) "POST" (str base-url "/api/auth/login") {:body (util/to-json {:username "platform"})}))))
    (check "invalid usernames can't log in"
           (= 422 (:status (send! (http-client) "POST" (str base-url "/api/auth/login") {:body (util/to-json {:username "Not A Name!"})})))))
  (check "the API needs a login"
         (= 401 (:status (send! (http-client) "POST" (str base-url "/api/query")
                                {:body (util/to-json (envelope {:query {:query-id :get-me}}))}))))
  (let [person
        (login "erin")

        logged-out
        (request person "POST" "/api/auth/logout")]
    (check "logging out ends the login" (and (#{200 204} (:status logged-out)) (= 401 (:status (request person "POST" "/api/query" (envelope {:query {:query-id :get-me}})))))))
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
                     :attrs {:identity "/user/e2e-forged" :collection :runs :row {:id session-id}}}])))
  (check "people can't run the platform's own queries"
         (every? #(= 403 (error-status (query alice %)))
                 [{:query-id :get-registry}
                  {:query-id :get-stored-credential :credential-id "BITBUCKET_TOKEN" :owner "bob"}
                  {:query-id :list-session-rows}])))

(defn catalog-and-library
  [{:keys [alice bob carol]}]
  (section "Teams, harnesses and activities")
  (check "people see the teams they belong to with their role"
         (= {"demo" :member "payments" :member}
            (into {} (map #(vector (:id %) (keyword (:role %)))) (result bob {:query-id :list-teams}))))
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
  (check "pull requests can be listed only by team members, others of the tenant are told why"
         (= 403 (error-status (query carol {:query-id :list-open-pull-requests :team-id "demo" :workflow-id "implement-change"}))))
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
  (outcome (request person "PUT" (str (if (= :tenant scope) "/api/admin/credentials/" "/api/credentials/") credential-id) body)))

(defn- tenant-value
  [row]
  (keyword (or (:tenant-value row) (:platform-value row))))

(defn credential-management
  [{:keys [alice erin]}]
  (section "Credential management")
  (command erin {:cmd-id :delete-my-credential :attrs {:credential-id "JIRA_TOKEN"}})
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
             (first (filter #(and (:declared %) (= [:tenant] (mapv keyword (:provided-by %))))
                            (result alice {:query-id :list-admin-credentials})))]
    (check "people can't set credentials only tenant admins provide"
           (= 403 (error-status (put-credential erin :user (:id platform-only) {:secret "x" :username "x"})))))
  (check "tenant admins can't set a personal-only credential for everyone"
         (= 422 (error-status (put-credential alice :tenant "JIRA_TOKEN" {:secret "x" :username "y"}))))
  (let [response
        (put-credential alice :tenant "BITBUCKET_TOKEN" {:secret "definitely-not-a-valid-token"})]
    (check "invalid tenant values are rejected before saving"
           (and (= 422 (error-status response)) (string/includes? (error-message response) "Not saved"))
           response))
  (check "non-admins can't clear tenant values"
         (= 403 (error-status (command erin {:cmd-id :delete-tenant-credential :attrs {:credential-id "BITBUCKET_TOKEN"}}))))
  (when (.isFile token-file)
    (let [saved
          (put-credential alice :tenant "BITBUCKET_TOKEN" {:secret (string/trim (slurp token-file))})

          row
          (admin-credential alice "BITBUCKET_TOKEN")]
      (check "tenant admins set a tenant value once it passes its check"
             (and (:ok saved) (= :admin (tenant-value row)) (seq (str (:hint row))))
             (or (:error saved) row))
      (check "clearing it falls back to the secret file"
             (and (:ok (command alice {:cmd-id :delete-tenant-credential :attrs {:credential-id "BITBUCKET_TOKEN"}}))
                  (= :file (tenant-value (admin-credential alice "BITBUCKET_TOKEN"))))
             (admin-credential alice "BITBUCKET_TOKEN")))))

(defn- stop-session!
  [person session-id]
  (when session-id
    (command person {:cmd-id :stop-session :id session-id})))

(defn- running-in
  [person team-id]
  (filterv #(and (= team-id (:team-id %)) (= :running (keyword (:status %))))
           (result person {:query-id :list-sessions})))

(defn room-in-team!
  "Stops the team's running sessions (scheduled workspaces and what earlier
   passes left pile up) so new ones fit under its limit."
  [admin team-id]
  (doseq [session (running-in admin team-id)]
    (stop-session! admin (:id session)))
  (wait-until 120000 #(empty? (running-in admin team-id))))

(defn- delete-session!
  [person session-id]
  (when session-id
    (command person {:cmd-id :delete-session :id session-id})))

(defn- team-roles
  [person query-id]
  (let [found
        (if (= :get-me query-id)
          (:teams (result person {:query-id :get-me}))
          (result person {:query-id :list-teams}))]
    (into {} (map (juxt :id (comp keyword :role))) found)))

(defn- sees-session?
  [person session-id]
  (some? (:ok (query person {:query-id :get-session :session-id session-id}))))

(defn- lists-session?
  [person session-id]
  (some #(= (str session-id) (str (:id %))) (result person {:query-id :list-sessions})))

(defn- share!
  [person session-id visibility]
  (:ok (command person {:cmd-id :update-session :id session-id :attrs {:visibility visibility}})))

(defn- by-id-refused
  "Checks that person reads nothing of a run, its session and team by id."
  [label person {:keys [run-id session-id team-id]}]
  (doseq [[what q] [["run" {:query-id :get-run :run-id run-id}]
                    ["session" {:query-id :get-session :session-id session-id}]
                    ["audit trail" {:query-id :list-audit-events :session-id session-id :after 0 :limit 10}]
                    ["team" {:query-id :get-team :team-id team-id}]
                    ["team credentials" {:query-id :list-team-credentials :team-id team-id}]]]
    (check (str label " can't read its " what " by id") (#{403 404} (error-status (query person q))))))

(defn several-teams-and-sharing
  "People in several teams (bob: demo and payments; dave: admin of payments,
   member of ops; ivy of globex: research and admin of lab), team-scoped data
   within a tenant, sharing private, team, tenant, forking across teams, and
   isolation between tenants by direct id."
  [{:keys [bob carol dave erin henry hank ivy]}]
  (section "People in several teams, team isolation and sharing")
  (check "someone in two teams has their role in each (admin in one, member in the other)"
         (= {"payments" :admin "ops" :member} (team-roles dave :get-me))
         (team-roles dave :get-me))
  (check "their teams list holds exactly those teams with those roles"
         (= {"payments" :admin "ops" :member} (team-roles dave :list-teams))
         (team-roles dave :list-teams))
  (check "a member of two teams is a member in each"
         (= {"demo" :member "payments" :member} (team-roles bob :list-teams))
         (team-roles bob :list-teams))
  (check "the same holds in another tenant"
         (= {"research" :member "lab" :admin} (team-roles ivy :list-teams))
         (team-roles ivy :list-teams))
  (let [demo-run
        (wait-for-run bob (start-run bob "demo" "workspace" {:title "e2e two teams: demo"}))

        payments-run
        (wait-for-run bob (start-run bob "payments" "workspace" {:title "e2e two teams: payments"}))

        demo-session
        (get-in demo-run [:session :id])

        payments-session
        (get-in payments-run [:session :id])

        sessions
        (mapv #(result bob {:query-id :get-session :session-id %}) (remove nil? [demo-session payments-session]))]
    (check "someone in two teams starts sessions in both"
           (= ["demo" "payments"] (mapv :team-id sessions))
           (mapv (juxt :team-id :status) sessions))
    (check "and may work in both" (and (= 2 (count sessions)) (every? :can-open sessions)) (mapv :open-blocked sessions))
    (when demo-session
      (check "a private session is hidden from the rest of its team's tenant"
             (not-any? #(sees-session? % demo-session) [henry erin carol]))
      (check "a session can be shared with its team" (share! bob demo-session :team))
      (check "shared with the team, people only in other teams of the tenant don't see it"
             (and (not (sees-session? henry demo-session)) (not (sees-session? erin demo-session))
                  (not (lists-session? henry demo-session)) (not (sees-session? dave demo-session))))
      (check "a team's credentials are refused to people only in other teams"
             (= 403 (error-status (query henry {:query-id :list-team-credentials :team-id "demo"}))))
      (check "a team's runs are closed to people only in other teams, who are told why"
             (and (= 403 (error-status (query henry {:query-id :get-run :run-id (:id demo-run)})))
                  (not-any? #(= "demo" (:team-id %)) (result henry {:query-id :list-runs}))))
      (check "a team's team page is closed to people only in other teams, who are told why"
             (= 403 (error-status (query henry {:query-id :get-team :team-id "demo"}))))
      (check "a session can be shared with the tenant" (share! bob demo-session :tenant))
      (check "shared with the tenant, everyone in it sees and lists it"
             (and (every? #(sees-session? % demo-session) [henry erin carol dave])
                  (lists-session? henry demo-session)))
      (let [fork-id
            (UUID/randomUUID)

            forked
            (command henry {:cmd-id :fork-session :id (track-session! henry fork-id) :attrs {:source-id demo-session}})

            fork
            (when (:ok forked)
              (result henry {:query-id :get-session :session-id fork-id}))]
        (check "someone of another team forks a session shared with the tenant" (:ok forked) (:error forked))
        (check "the fork stays in the source's team, private to whoever forked it"
               (= ["demo" :private "henry"] [(:team-id fork) (keyword (:visibility fork)) (:sub fork)])
               (select-keys fork [:team-id :visibility :sub]))
        (check "the fork is not visible to the source's owner"
               (not (sees-session? bob fork-id)))
        (command henry {:cmd-id :delete-session :id fork-id}))
      (by-id-refused "another tenant" ivy {:run-id (:id demo-run) :session-id demo-session :team-id "demo"})
      (check "another tenant doesn't list a session shared with acme"
             (not (lists-session? hank demo-session))))
    (let [lab-run
          (wait-for-run ivy (start-run ivy "lab" "workspace" {:title "e2e globex lab"}))

          lab-session
          (get-in lab-run [:session :id])]
      (check "someone in two teams of another tenant starts a session in their second team" (some? lab-session)
             (:status lab-run))
      (when lab-session
        (share! ivy lab-session :tenant)
        (check "shared with globex, a globex member of another team sees it" (sees-session? hank lab-session))
        (by-id-refused "acme" bob {:run-id (:id lab-run) :session-id lab-session :team-id "lab"})
        (check "acme doesn't list a session shared with globex" (not (lists-session? bob lab-session)))
        (delete-session! ivy lab-session)))
    (delete-session! bob demo-session)
    (delete-session! bob payments-session)))

(defn- refused-at-the-limit
  "The refusal of one more payments workspace; a start that still gets
   through is cancelled at once and tried again."
  [erin]
  (wait-until 120000 #(let [run-id
                            (UUID/randomUUID)

                            response
                            (command erin {:cmd-id :start-run :id run-id
                                           :attrs {:team-id "payments" :workflow-id "workspace" :inputs {:title "e2e limit 3"}}})]
                        (if (:ok response)
                          (do (track-run! erin run-id)
                              (command erin {:cmd-id :cancel-run :id run-id})
                              nil)
                          response))))

(defn session-limit
  [{:keys [erin alice]}]
  (section "Team session limit")
  (room-in-team! alice "payments")
  (let [runs
        (doall (for [n [1 2]]
                 (wait-for-run erin (start-run erin "payments" "workspace" {:title (str "e2e limit " n)}))))

        sessions
        (keep :session-id runs)]
    (try
      (let [refused
            (when (= 2 (count sessions))
              (refused-at-the-limit erin))]
        (check "a team's sessions start up to its limit" (= 2 (count sessions)) (mapv :status runs))
        (check "one more is refused with the limit" (string/includes? (error-message refused) "limit") refused)
        (when (= 2 (count sessions))
          (let [at-the-limit?
                #(and (= 422 (error-status %)) (string/includes? (error-message %) "limit"))

                forked
                (command erin {:cmd-id :fork-session :id (track-session! erin (UUID/randomUUID))
                               :attrs {:source-id (first sessions)}})

                retried
                (command erin {:cmd-id :retry-run :id (track-run! erin (UUID/randomUUID))
                               :attrs {:source-id (:id (first runs))}})]
            (check "at the limit a session can't be forked either" (at-the-limit? forked) forked)
            (check "nor a finished run be run again" (at-the-limit? retried) retried))))
      (finally
        (doseq [session-id sessions]
          (delete-session! erin session-id))
        (wait-until 120000 #(empty? (running-in alice "payments")))))))

(defn- remove-required!
  [person]
  (doseq [credential-id ["JIRA_TOKEN" "PAGERDUTY_TOKEN"]]
    (command person {:cmd-id :delete-my-credential :attrs {:credential-id credential-id}})))

(defn- add-required!
  [{:keys [username] :as person}]
  [(put-credential person :user "JIRA_TOKEN" {:secret (str "e2e-" username "-jira") :username (str username "@example.com")})
   (put-credential person :user "PAGERDUTY_TOKEN" {:secret (str "e2e-" username "-pagerduty")})])

(defn- refused-for-credentials?
  [response & names]
  (and (= 422 (error-status response))
       (every? #(string/includes? (error-message response) %) names)))

(defn- required-of-work-in-progress
  "henry has a session and a finished run in ops; without his own Jira value
   he can't fork or run them again, someone outside the team still forks."
  [{:keys [alice carol dave henry]}]
  (room-in-team! alice "ops")
  (let [run
        (wait-for-run henry (start-run henry "ops" "workspace" {:title "e2e required"}))

        session-id
        (:session-id run)

        fork
        (fn [person]
          (let [fork-id (track-session! person (UUID/randomUUID))]
            [fork-id (command person {:cmd-id :fork-session :id fork-id :attrs {:source-id session-id}})]))]
    (check "with their own values, work in ops starts" (and (= :completed (:status run)) session-id) (or (:error run) (:status run)))
    (when session-id
      (share! henry session-id :tenant)
      (command henry {:cmd-id :delete-my-credential :attrs {:credential-id "JIRA_TOKEN"}})
      (let [[_ forked]
            (fork henry)

            retried
            (command henry {:cmd-id :retry-run :id (track-run! henry (UUID/randomUUID)) :attrs {:source-id (:id run)}})]
        (check "without one, a member can't fork a session of the team" (refused-for-credentials? forked "Jira") forked)
        (check "nor run one of its runs again" (refused-for-credentials? retried "Jira") retried))
      (remove-required! dave)
      (let [refused
            (command dave {:cmd-id :start-run :id (UUID/randomUUID)
                           :attrs {:team-id "ops" :workflow-id "workspace" :inputs {:title "e2e required"}}})]
        (check "the same holds for a member who administers another team"
               (refused-for-credentials? refused "Jira" "PagerDuty")
               refused))
      (let [[fork-id forked]
            (fork carol)]
        (check "someone outside the team forks its shared session without them" (:ok forked) (:error forked))
        (delete-session! carol fork-id))
      (add-required! henry)
      (let [[fork-id forked]
            (fork henry)]
        (check "with the value back, the member forks it" (:ok forked) (:error forked))
        (delete-session! henry fork-id))
      (delete-session! henry session-id))))

(defn required-credentials
  "acme requires PagerDuty of everyone in ops (tenant.json) and ops requires
   Jira of its members (its team.json): henry must add his own before work in
   ops starts; team and tenant values don't count."
  [{:keys [alice bob henry] :as people}]
  (section "Required credentials")
  (remove-required! henry)
  (let [mine
        (result henry {:query-id :get-my-credentials})

        states
        (into {} (map (juxt :id identity)) (:credentials mine))]
    (check "required credentials say who requires them"
           (and (= {:tenant true :teams []} (get-in states ["PAGERDUTY_TOKEN" :required-by]))
                (= {:tenant false :teams ["ops"]} (get-in states ["JIRA_TOKEN" :required-by]))
                (every? #(true? (get-in states [% :required])) ["JIRA_TOKEN" "PAGERDUTY_TOKEN"]))
           (mapv #(select-keys (get states %) [:id :required :required-by]) ["JIRA_TOKEN" "PAGERDUTY_TOKEN"]))
    (check "the missing ones lead what setup offers"
           (and (= #{"JIRA_TOKEN" "PAGERDUTY_TOKEN"} (set (:missing-required mine)))
                (= #{"JIRA_TOKEN" "PAGERDUTY_TOKEN"} (set (take 2 (:pending mine)))))
           (select-keys mine [:missing-required :pending])))
  (command henry {:cmd-id :mark-credentials-seen :attrs {:credentials ["JIRA_TOKEN" "PAGERDUTY_TOKEN"]}})
  (let [seen
        (result henry {:query-id :get-my-credentials})]
    (check "having seen them doesn't make them optional"
           (and (= #{"JIRA_TOKEN" "PAGERDUTY_TOKEN"} (set (take 2 (:pending seen))))
                (= #{"JIRA_TOKEN" "PAGERDUTY_TOKEN"} (set (:missing-required seen))))
           (select-keys seen [:missing-required :pending])))
  (check "they are required only of the teams' people, not of a tenant admin outside ops"
         (not-any? #{"JIRA_TOKEN" "PAGERDUTY_TOKEN"} (:missing-required (result alice {:query-id :get-my-credentials}))))
  (let [refused
        (command henry {:cmd-id :start-run :id (UUID/randomUUID)
                        :attrs {:team-id "ops" :workflow-id "workspace" :inputs {:title "e2e required"}}})]
    (check "work in ops doesn't start without them, and says which and where"
           (and (= 422 (error-status refused))
                (every? #(string/includes? (error-message refused) %) ["Team Operations" "Jira" "PagerDuty" "Accounts"]))
           refused))
  (let [required
        (result henry {:query-id :list-required-credentials :team-id "ops"})]
    (check "the team's settings list what it requires and where that comes from"
           (= {"JIRA_TOKEN" {:tenant false :team true} "PAGERDUTY_TOKEN" {:tenant true :team false}}
              (into {} (map (juxt :id :required-by)) required))
           required))
  (check "people outside the team don't read its requirements"
         (= 403 (error-status (query bob {:query-id :list-required-credentials :team-id "ops"}))))
  (let [rows
        (into {} (map (juxt :id identity)) (result alice {:query-id :list-admin-credentials}))]
    (check "admins see which credentials are required, by the tenant or by teams"
           (and (true? (get-in rows ["PAGERDUTY_TOKEN" :required]))
                (= ["ops"] (get-in rows ["JIRA_TOKEN" :required-by-teams])))
           (mapv #(select-keys (get rows %) [:id :required :required-by-teams]) ["JIRA_TOKEN" "PAGERDUTY_TOKEN"])))
  (let [added
        (add-required! henry)]
    (check "once added, nothing is missing"
           (and (every? :ok added)
                (empty? (:missing-required (result henry {:query-id :get-my-credentials}))))
           added))
  (required-of-work-in-progress people))

(def budget-body
  "A call of more than the ops team's daily budget (1000 tokens): the first
   is relayed, the next refused."
  (str "{\"model\":\"claude-sonnet-5.5\",\"max_tokens\":5,\"messages\":[{\"role\":\"user\",\"content\":\""
       (string/join " " (repeat 1500 "budget"))
       " Reply with ok.\"}]}"))

(defn token-budget
  [{:keys [alice henry]}]
  (section "Team token budget")
  (add-required! henry)
  (room-in-team! alice "ops")
  (let [budget
        (:budget (result henry {:query-id :get-team :team-id "ops"}))

        spent-already?
        (>= (or (:used-today budget) 0) (or (:daily-tokens budget) Long/MAX_VALUE))

        run
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
    (if spent-already?
      (check "a budget already used today (an earlier run on the same data) refuses the first call"
             (string/includes? (str first-call) "429") first-call)
      (check "the first call within the budget is relayed" (string/includes? (str first-call) "200 OK") first-call))
    (check "once the team used its daily budget, the gateway refuses calls"
           (string/includes? (str refused) "429 Too Many Requests")
           refused)
    (delete-session! henry session-id)))

(defn platform-started-sessions
  [{:keys [alice carol henry]}]
  (section "Platform-started sessions (schedule)")
  (let [run
        (wait-until (* 8 60 1000) #(first (filter (fn [listed] (and (:platform-started listed) (not= :failed (:status listed))))
                                                  (result henry {:query-id :list-runs :team-id "ops"}))))

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
        (check "its session is tenant-wide but only the team may work in it (team credentials)"
               (and (= :tenant (keyword (:visibility seen))) (= :team-only (keyword (:open-blocked seen))))
               (select-keys seen [:visibility :open-blocked])))
      (check "people outside the team can't start it"
             (= 409 (error-status (command carol {:cmd-id :start-session :id session-id}))))
      (let [entry
            (request carol "GET" (:open-url (:ok (session-of henry session-id))))]
        (check "opening it sends them to its page under the tenant instead of the session"
               (and (= 303 (:status entry))
                    (= (str "/acme/sessions/" session-id) (get-in entry [:headers "location"])))
               (select-keys entry [:status :headers])))
      (room-in-team! alice "ops")
      (let [fork-id
            (track-session! carol (UUID/randomUUID))

            forked
            (command carol {:cmd-id :fork-session :id fork-id :attrs {:source-id session-id}})]
        (check "but they can fork it into their own copy, kept in the team"
               (and (:ok forked)
                    (= ["carol" "ops" :private]
                       ((juxt :sub :team-id (comp keyword :visibility)) (:ok (session-of carol fork-id)))))
               (or (:error forked) (session-of carol fork-id)))
        (when (:ok forked)
          (command carol {:cmd-id :delete-session :id fork-id})))
      (check "team members may work in it" (nil? (get-in (session-of henry session-id) [:ok :open-blocked])))
      (check "tenant admins can stop it" (:ok (stop-session! alice session-id))))))

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
         (some #(string/includes? (str (:id %)) "scheduled-workspace") (result alice {:query-id :list-schedules})))
  (doseq [[label q] [["MCP servers" {:query-id :list-mcp-servers}]
                     ["credentials" {:query-id :list-admin-credentials}]
                     ["schedules" {:query-id :list-schedules}]
                     ["usage" {:query-id :get-admin-usage :days 1}]
                     ["people" {:query-id :list-admin-users}]
                     ["connection tests" {:query-id :test-llm-connection :connection-id "copilot"}]]]
    (check (str "non-admins can't read admin " label) (= 403 (error-status (query bob q)))))
  (check "nobody reads metrics without logging in" (= 401 (:status (send! (http-client) "GET" (str base-url "/metrics") {})))))

(defn tenant-isolation
  "gina and hank belong to globex; nothing of acme may reach them."
  [{:keys [gina hank]} run]
  (section "Tenant isolation")
  (let [session-id
        (get-in run [:session :id])

        run-id
        (:id run)

        acme-team?
        #(contains? #{"demo" "payments" "ops"} (:team-id %))]
    (check "people belong to the tenant of their claims" (= "globex" (:tenant (result hank {:query-id :get-me}))))
    (check "another tenant's teams are not listed" (= #{"lab" "research"} (set (map :id (result gina {:query-id :list-teams})))))
    (check "another tenant's team is not found" (= 404 (error-status (query gina {:query-id :get-team :team-id "demo"}))))
    (check "another tenant's harnesses are not listed" (not-any? acme-team? (result gina {:query-id :list-harnesses})))
    (check "another tenant's workflows can't be started"
           (= 404 (error-status (command hank {:cmd-id :start-run :id (UUID/randomUUID)
                                               :attrs {:team-id "demo" :workflow-id "workspace" :inputs {:title "x"}}}))))
    (when session-id
      (doseq [[label q] [["session" {:query-id :get-session :session-id session-id}]
                         ["live activity" {:query-id :get-session-activity :session-id session-id}]
                         ["conversations" {:query-id :list-conversations :session-id session-id}]
                         ["audit trail" {:query-id :list-audit-events :session-id session-id :after 0 :limit 10}]
                         ["audit verification" {:query-id :verify-audit :session-id session-id}]]]
        (check (str "another tenant's " label " is not found") (= 404 (error-status (query gina q)))))
      (check "another tenant's sessions are not listed"
             (not-any? #(= (str session-id) (str (:id %))) (result gina {:query-id :list-sessions})))
      (check "another tenant's sessions can't be forked"
             (= 404 (error-status (command gina {:cmd-id :fork-session :id (UUID/randomUUID) :attrs {:source-id session-id}}))))
      (check "another tenant's sessions can't be changed"
             (= 404 (error-status (command gina {:cmd-id :update-session :id session-id :attrs {:visibility :tenant}}))))
      (check "another tenant's audit trail can't be exported"
             (not= 200 (:status (request gina "GET" (str "/api/sessions/" session-id "/audit/export")))))
      (check "another tenant's session host refuses them"
             (not= 200 (:status (send! (:client gina) "GET" (session-host-url session-id) {})))))
    (when run-id
      (check "another tenant's run is not found" (= 404 (error-status (query gina {:query-id :get-run :run-id run-id}))))
      (check "another tenant's runs can't be cancelled" (= 404 (error-status (command gina {:cmd-id :cancel-run :id run-id})))))
    (check "another tenant's runs are not listed" (not-any? acme-team? (result gina {:query-id :list-runs})))
    (let [overview
          (result gina {:query-id :get-admin-overview})]
      (check "a tenant admin's overview counts only their tenant" (= 2 (:teams overview)) overview))
    (check "a tenant admin sees only their tenant's credentials"
           (not-any? #(#{"BITBUCKET_TOKEN" "JIRA_TOKEN"} (:id %)) (result gina {:query-id :list-admin-credentials})))
    (check "a tenant admin sees only their tenant's MCP servers"
           (not-any? #(= "bitbucket" (:id %)) (result gina {:query-id :list-mcp-servers})))
    (check "a tenant admin sees only their tenant's schedules"
           (not-any? acme-team? (result gina {:query-id :list-schedules})))
    (check "a tenant admin sees only their tenant's usage"
           (not-any? acme-team? (result gina {:query-id :get-admin-usage :days 1})))
    (check "a tenant admin sees only their tenant's people"
           (not-any? #{"alice" "bob" "carol"} (map :username (result gina {:query-id :list-admin-users}))))
    (check "a tenant admin can't set another tenant's team credentials"
           (#{403 404} (error-status (outcome (request gina "PUT" "/api/teams/demo/credentials/BITBUCKET_TOKEN" {:secret "x"})))))
    (check "people of another tenant don't see its personal credentials"
           (not-any? #(= "JIRA_TOKEN" (:id %)) (:credentials (result hank {:query-id :get-my-credentials}))))))

(defn projects-and-search
  "Projects as pages and the global search, inside the caller's tenant only."
  [{:keys [bob carol gina]} workspace-run]
  (section "Projects and search")
  (let [session
        (:session workspace-run)

        project
        (result bob {:query-id :get-project :team-id "demo" :project-id "demo-service"})]
    (check "people list the projects of their teams"
           (some #(= ["demo" "demo-service"] [(:team-id %) (:id %)]) (result bob {:query-id :list-projects})))
    (check "a project names its team and the workflows working on it"
           (and (= "acme" (:tenant project))
                (some #(= "implement-gerrit" (:id %)) (:workflows project)))
           (map :id (:workflows project)))
    (check "a project's runs are only runs of its workflows (e2e-ci checks that one is listed)"
           (every? #(= "demo-service" (:project %))
                   (result bob {:query-id :list-runs :team-id "demo" :project-id "demo-service"})))
    (check "runs can be listed for one team"
           (let [runs (result bob {:query-id :list-runs :team-id "demo"})]
             (and (some #(= (str (:id workspace-run)) (str (:id %))) runs)
                  (every? #(= "demo" (:team-id %)) runs))))
    (check "sessions can be listed for one team"
           (let [in-team (fn [team-id]
                           (set (map (comp str :id) (result bob {:query-id :list-sessions :team-id team-id}))))]
             (and (contains? (in-team "demo") (str (:id session)))
                  (not (contains? (in-team "payments") (str (:id session))))))
           (:id session))
    (check "search returns at most the asked number per group"
           (= [2 1]
              [(count (:teams (result bob {:query-id :search :text "e" :limit 2})))
               (count (:teams (result bob {:query-id :search :text "e" :limit 1})))]))
    (check "people outside the team may not open its project"
           (= 403 (error-status (query carol {:query-id :get-project :team-id "demo" :project-id "demo-service"}))))
    (check "another tenant's project is not found"
           (= 404 (error-status (query gina {:query-id :get-project :team-id "demo" :project-id "demo-service"}))))
    (check "another tenant's projects are not listed"
           (not-any? #(= "demo" (:team-id %)) (result gina {:query-id :list-projects})))
    (check "search finds a project by its name"
           (some #(= "demo-service" (:id %)) (:projects (result bob {:query-id :search :text "demo-serv"}))))
    (check "search finds a team of the tenant"
           (some #(= "payments" (:id %)) (:teams (result bob {:query-id :search :text "payments"}))))
    (check "another tenant's search finds none of the tenant's teams and projects"
           (let [found (result gina {:query-id :search :text "demo"})]
             (and (empty? (:projects found)) (not-any? #(= "demo" (:id %)) (:teams found))))
           (result gina {:query-id :search :text "demo"}))
    (when session
      (let [title
            (:name session)

            listed?
            (fn [person]
              (some #(= (str (:id session)) (str (:id %))) (:sessions (result person {:query-id :search :text title}))))]
        (check "search finds the person's own session" (listed? bob) title)
        (when (= :private (keyword (:visibility session)))
          (check "search never shows someone else's private session" (not (listed? carol))))
        (check "search never shows another tenant's session" (not (listed? gina)))))))

(def e2e-tenant
  "A tenant the e2e test registers, with an onboarding repository that doesn't exist."
  "e2e-initech")

(defn operators-and-tenants
  [{:keys [oscar alice]}]
  (section "Operators and tenants")
  (let [tenants
        (set (map :name (result oscar {:query-id :list-tenants})))]
    (check "operators see every tenant" (every? tenants ["acme" "globex"]) tenants))
  (check "tenant admins can't list tenants" (= 403 (error-status (query alice {:query-id :list-tenants}))))
  (check "tenant admins can't register tenants"
         (= 403 (error-status (command alice {:cmd-id :register-tenant
                                              :attrs {:name "e2e-refused" :onboarding "file:///repos/missing"}}))))
  (let [synced
        (command oscar {:cmd-id :sync-tenant :attrs {:tenant "globex"}})]
    (check "operators sync any tenant" (:ok synced) (:error synced)))
  (check "tenant names are validated"
         (= 422 (error-status (command oscar {:cmd-id :register-tenant :attrs {:name "Not A Name" :onboarding "file:///repos/x"}}))))
  (let [reloaded
        (request oscar "POST" "/api/admin/reload-config" {})]
    (check "operators reload the configuration now"
           (and (= 200 (:status reloaded))
                (= ["reloaded" "reloaded"]
                   (mapv #(some-> (get-in reloaded [:data % :status]) name) [:claims-parser :tenants])))
           (:body reloaded)))
  (check "others can't reload the configuration"
         (= 403 (:status (request alice "POST" "/api/admin/reload-config" {}))))
  (let [registered
        (command oscar {:cmd-id :register-tenant :attrs {:name e2e-tenant :onboarding "file:///repos/e2e-missing-onboarding"}})]
    (check "operators register a tenant" (or (:ok registered) (= 409 (error-status registered))) registered))
  (check "a registered tenant is listed right away"
         (some #(= e2e-tenant (:name %)) (result oscar {:query-id :list-tenants})))
  (check "its sync fails on its own, with its own status"
         (wait-until 120000 #(let [tenants (into {} (map (juxt :name identity)) (result oscar {:query-id :list-tenants}))]
                               (and (seq (str (get-in tenants [e2e-tenant :error-message])))
                                    (nil? (get-in tenants ["acme" :error-message]))
                                    (nil? (get-in tenants ["globex" :error-message]))))))
  (check "the other tenants keep working" (= 3 (count (result alice {:query-id :list-teams})))))

(defn- team-credential
  [person team-id credential-id]
  (first (filter #(= credential-id (:id %)) (result person {:query-id :list-team-credentials :team-id team-id}))))

(defn- put-team-credential
  [person team-id credential-id body]
  (outcome (request person "PUT" (str "/api/teams/" team-id "/credentials/" credential-id) body)))

(defn team-credentials
  [{:keys [alice bob dave]}]
  (section "Team credentials")
  (check "team admins see the credentials their team may provide"
         (some? (team-credential alice "demo" "BITBUCKET_TOKEN")))
  (let [listed
        (result bob {:query-id :list-team-credentials :team-id "demo"})]
    (check "team members see which team credentials are set, without hints"
           (and (seq listed)
                (every? #(= #{:id :name :description :provided-by :set} (set (keys %))) listed))
           listed))
  (check "people outside the team can't list its credentials"
         (= 403 (error-status (query dave {:query-id :list-team-credentials :team-id "demo"}))))
  (check "admins of another team can't set them"
         (= 403 (error-status (put-team-credential dave "demo" "BITBUCKET_TOKEN" {:secret "x"}))))
  (check "credentials a team may not provide are refused"
         (#{403 422} (error-status (put-team-credential alice "demo" "JIRA_TOKEN" {:secret "x" :username "y"}))))
  (let [response
        (put-team-credential alice "demo" "BITBUCKET_TOKEN" {:secret "definitely-not-a-valid-token"})]
    (check "invalid team values are rejected before saving"
           (and (= 422 (error-status response)) (string/includes? (error-message response) "Not saved"))
           response))
  (when (.isFile token-file)
    (let [token
          (string/trim (slurp token-file))

          saved
          (put-team-credential alice "demo" "BITBUCKET_TOKEN" {:secret token})

          row
          (team-credential alice "demo" "BITBUCKET_TOKEN")]
      (check "team admins set a team value once it passes its check"
             (and (:ok saved) (seq (str (get-in row [:value :hint]))))
             (or (:error saved) row))
      (check "team values are never returned by the API"
             (not (string/includes? (pr-str (result alice {:query-id :list-team-credentials :team-id "demo"})) token)))
      (check "team admins can clear the team value"
             (:ok (command alice {:cmd-id :delete-team-credential :attrs {:team-id "demo" :credential-id "BITBUCKET_TOKEN"}}))))))

(defn team-sessions
  [{:keys [alice bob carol]}]
  (section "Team-scoped sessions")
  (let [start
        (fn [person workflow-id]
          (let [run-id (UUID/randomUUID)]
            [run-id (command person {:cmd-id :start-team-session :id run-id
                                     :attrs {:team-id "demo" :workflow-id workflow-id :inputs {:title "e2e team session"}}})]))]
    (check "team members can't open a session in the team's scope"
           (= 403 (error-status (second (start bob "workspace")))))
    (check "only interactive workflows open team sessions"
           (#{409 422} (error-status (second (start alice "requirements")))))
    (let [[run-id response]
          (start alice "workspace")

          run
          (when (:ok response) (wait-for-run alice run-id))

          session-id
          (:session-id run)]
      (check "team admins open a session in the team's scope" (= :completed (:status run)) (or (:error response) (:status run)))
      (check "it belongs to the team, not to the admin"
             (and (true? (:platform-started run)) (= ["platform" "demo"] ((juxt :sub :team-id) (:session run))))
             (select-keys run [:platform-started :session]))
      (when session-id
        (check "team members may work in it" (nil? (get-in (session-of bob session-id) [:ok :open-blocked])))
        (check "people outside the team don't see it (shared with the team)"
               (= 404 (error-status (session-of carol session-id))))
        (let [fork-id
              (track-session! bob (UUID/randomUUID))

              forked
              (command bob {:cmd-id :fork-session :id fork-id :attrs {:source-id session-id}})]
          (check "members fork it to continue in their own copy"
                 (and (:ok forked) (= ["bob" "demo"] ((juxt :sub :team-id) (:ok (session-of bob fork-id)))))
                 (or (:error forked) (session-of bob fork-id)))
          (when (:ok forked)
            (command bob {:cmd-id :delete-session :id fork-id})))
        (check "team admins can delete it" (:ok (command alice {:cmd-id :delete-session :id session-id})))))))

(defn run-visibility
  "Who sees the session of a run is chosen when it starts, and kept when it is run again."
  [{:keys [alice bob carol]}]
  (section "Who sees a run's session")
  (let [start
        (fn [visibility]
          (wait-for-run bob (start-run bob "demo" "workspace" {:title (str "e2e shared with " (name visibility))} visibility)))

        team-run
        (start :team)

        tenant-run
        (start :tenant)

        team-session
        (:session-id team-run)

        tenant-session
        (:session-id tenant-run)

        shared-with
        #(keyword (get-in % [:session :visibility]))]
    (try
      (check "a run started for the team gives its session to the team, and nobody else"
             (and (= :team (shared-with team-run))
                  (true? (get-in (session-of alice team-session) [:ok :can-open]))
                  (= 404 (error-status (session-of carol team-session))))
             (shared-with team-run))
      (check "one started for the tenant is seen by everyone in it"
             (and (= :tenant (shared-with tenant-run)) (sees-session? carol tenant-session))
             (shared-with tenant-run))
      (let [retry-id
            (track-run! bob (UUID/randomUUID))

            retried
            (command bob {:cmd-id :retry-run :id retry-id :attrs {:source-id (:id team-run)}})

            again
            (when (:ok retried)
              (wait-for-run bob retry-id))]
        (check "running it again keeps who sees it"
               (= :team (shared-with again))
               (or (:error retried) (shared-with again)))
        (delete-session! bob (:session-id again)))
      (let [refused
            (command alice {:cmd-id :start-team-session :id (track-run! alice (UUID/randomUUID))
                            :attrs {:team-id "demo" :workflow-id "workspace" :visibility :private
                                    :inputs {:title "e2e private team session"}}})]
        (check "a team session can't be private" (some? (:error refused)) refused))
      (finally
        (delete-session! bob team-session)
        (delete-session! bob tenant-session)))))

(defn- team-role
  [me team-id]
  (some #(when (= team-id (:id %)) (some-> (:role %) name)) (:teams me)))

(defn single-sign-on
  "Logins through Keycloak (compose.keycloak.yml): tenant, teams and roles
   come from its token claims through the claims parser."
  [{:keys [alice bob gina oscar]}]
  (section "Single sign-on (OpenID Connect)")
  (let [reloaded
        (request oscar "POST" "/api/admin/reload-config" {})]
    (check "the provider's endpoints and keys come from its discovery URL (compose.keycloak.yml)"
           (= ["reloaded" "reloaded"]
              (mapv #(some-> (get-in reloaded [:data :identity-provider % :status]) name) [:discovery :keys]))
           (:body reloaded)))
  (let [alice-me
        (result alice {:query-id :get-me})

        bob-me
        (result bob {:query-id :get-me})

        gina-me
        (result gina {:query-id :get-me})]
    (check "alice signs in as tenant admin of acme and admin of demo"
           (and (= "acme" (:tenant alice-me)) (true? (:tenant-admin alice-me)) (= "admin" (team-role alice-me "demo")))
           alice-me)
    (check "bob signs in as a member of demo in acme"
           (and (= "acme" (:tenant bob-me)) (false? (boolean (:tenant-admin bob-me))) (= "member" (team-role bob-me "demo")))
           bob-me)
    (check "gina signs in as tenant admin of globex"
           (and (= "globex" (:tenant gina-me)) (true? (:tenant-admin gina-me)))
           gina-me))
  (check "bob sees no globex team"
         (and (not-any? #(= "research" (:id %)) (result bob {:query-id :list-teams}))
              (#{403 404} (error-status (query bob {:query-id :get-team :team-id "research"})))))
  (let [{:keys [client callback]}
        (provider-login "nobody" (provider-password "nobody"))

        config
        (parse (:body (send! client "GET" (str base-url "/api/auth/config") {})))]
    (check "an account without a tenant is refused, and the login page learns why"
           (and (string/starts-with? (str (get-in callback [:headers "location"])) "/login?")
                (not (string/includes? (str (get-in callback [:headers "location"])) "complete=1"))
                (string/includes? (str (:error config)) "belongs to no tenant"))
           [callback config]))
  (let [{:keys [submitted callback]}
        (provider-login "bob" "wrong-password")]
    (check "a wrong password stays on the provider's login page"
           (and (= 200 (:status submitted)) (nil? callback))
           (:status submitted)))
  (let [client
        (http-client)

        forged
        (send! client "GET" (str base-url "/api/auth/oidc/callback?code=forged&state=forged") {})]
    (check "a callback the browser did not start is refused"
           (and (= 302 (:status forged))
                (= 401 (:status (send! client "POST" (str base-url "/api/query")
                                       {:body (util/to-json (envelope {:query {:query-id :get-me}}))}))))
           forged))
  (check "the password-free test login is off"
         (= 404 (:status (send! (http-client) "POST" (str base-url "/api/auth/login") {:body (util/to-json {:username "bob"})}))))
  (let [person
        (login "hank")

        logged-out
        (request person "POST" "/api/auth/logout")]
    (check "logging out sends the browser to the provider's logout"
           (string/includes? (str (get-in logged-out [:data :redirect])) "/protocol/openid-connect/logout")
           logged-out)))

(defn- housekeeping
  "Says what the pass starts from and makes room under the teams' session limits."
  [{:keys [alice gina]}]
  (section "Before the pass")
  (doseq [team-id ["demo" "payments" "ops"]]
    (let [budget
          (:budget (result alice {:query-id :get-team :team-id team-id}))]
      (println (str "  " team-id ": " (or (:used-today budget) 0) " of " (:daily-tokens budget) " tokens used today, "
                    (count (running-in alice team-id)) " running sessions"))
      (when (= "demo" team-id)
        (check "the demo team has token budget left for this pass"
               (< (or (:used-today budget) 0) (or (:daily-tokens budget) Long/MAX_VALUE))
               budget)))
    (room-in-team! alice team-id))
  (doseq [team-id ["lab" "research"]]
    (room-in-team! gina team-id)))

(defn- quietly
  [f]
  (try
    (f)
    (catch Throwable _
      nil)))

(defn clean-up!
  "Cancels what the pass left running and deletes its sessions, whatever
   happened before."
  [_people]
  (section "Cleanup")
  (doseq [[person run-id] @started-runs]
    (quietly
     #(when-let [run (result person {:query-id :get-run :run-id run-id})]
        (when-not (contains? #{:completed :failed :cancelled} (:status run))
          (command person {:cmd-id :cancel-run :id run-id}))
        (delete-session! person (:session-id run)))))
  (doseq [[person session-id] @started-sessions]
    (quietly #(delete-session! person session-id))))

(defn- restore-credentials!
  "Removes the credential values the pass set for people, the demo team and the tenant."
  [{:keys [alice bob erin henry]}]
  (doseq [[person cmd] [[bob {:cmd-id :delete-my-credential :attrs {:credential-id "BITBUCKET_TOKEN"}}]
                        [erin {:cmd-id :delete-my-credential :attrs {:credential-id "JIRA_TOKEN"}}]
                        [henry {:cmd-id :delete-my-credential :attrs {:credential-id "JIRA_TOKEN"}}]
                        [henry {:cmd-id :delete-my-credential :attrs {:credential-id "PAGERDUTY_TOKEN"}}]
                        [alice {:cmd-id :delete-team-credential :attrs {:team-id "demo" :credential-id "BITBUCKET_TOKEN"}}]
                        [alice {:cmd-id :delete-tenant-credential :attrs {:credential-id "BITBUCKET_TOKEN"}}]]
          :when person]
    (quietly #(command person cmd))))

(defn summary!
  "Prints how the checks went; the exit code."
  []
  (let [failed
        (map first (remove second @results))]
    (print-timings!)
    (println (str "\n" (- (count @results) (count failed)) "/" (count @results) " checks passed"))
    (when (seq failed)
      (println "Failed:")
      (doseq [check-name failed]
        (println "  -" check-name)))
    (if (seq failed) 1 0)))

(def usernames
  ["alice" "bob" "carol" "dave" "erin" "henry" "gina" "hank" "ivy" "oscar"])

(defn- pass
  [{:keys [alice bob] :as people}]
  (let [in
        (fn [title f & args]
          (guarded title #(apply f people args)))]
    (in "Before the pass" housekeeping)
    (in "Platform and access control" platform-and-access)
    (in "Roles and administration" roles-and-administration)
    (in "Authentication and browser hardening" authentication-and-browser)
    (when (oidc-mode?)
      (in "Single sign-on" single-sign-on))
    (in "Teams, harnesses and activities" catalog-and-library)
    (section "Activities")
    (let [analysis
          (start-run alice "demo" "requirements" {:brief "Members get 5% off orders over 100 EUR, stackable with other discounts up to 20% in total."})

          implement
          (start-run alice "demo" "implement-change"
                     {:task "Add a keyword argument member: bool = False to total(). Members get an extra 5% off when the subtotal is over 100; the combined discount must not exceed 20%. Add unit tests and run them."})

          workspace
          (start-run bob "demo" "workspace" {:title "e2e workspace" :prompt "Run the unit tests in repo/ and tell me the result."})

          review
          (start-run alice "demo" "review-branch" {:branch "e2e-review"})

          workspace-run
          (guarded "The workspace run" #(wait-for-run bob workspace))

          workspace-session
          (get-in workspace-run [:session :id])]
      (in "Monitoring and policy" monitoring-and-policy)
      (in "Cancel and retry" cancel-and-retry)
      (in "Interactive session" interactive-session workspace-run)
      (in "Tenant isolation" tenant-isolation workspace-run)
      (in "Projects and search" projects-and-search workspace-run)
      (when workspace-session
        (guarded "Sandbox routes" #(sandbox-cannot-reach-api workspace-session))
        (in "Telemetry" telemetry workspace-session)
        (in "Conversations and audit trail" conversations-and-audit workspace-session)
        (in "Session management" session-management workspace-session))
      (in "Results" result-runs [["analysis" analysis :document] ["implement" implement :implementation] ["review" review :review]]))
    (in "Credentials, MCP gateway, fork and lock" credentials-and-tools)
    (in "Credential management" credential-management)
    (in "Team credentials" team-credentials)
    (in "Team-scoped sessions" team-sessions)
    (in "Who sees a run's session" run-visibility)
    (in "Team session limit" session-limit)
    (in "People in several teams" several-teams-and-sharing)
    (in "Required credentials" required-credentials)
    (in "Team token budget" token-budget)
    (in "Platform-started sessions" platform-started-sessions)
    (in "Administration actions" administration-actions)
    (in "Usage and metrics" usage-and-metrics)
    (in "Operators and tenants" operators-and-tenants)
    (in "Webhooks" (requiring-resolve 'manager-svc.e2e-webhooks/webhooks))
    (in "Gerrit and Jenkins" (requiring-resolve 'manager-svc.e2e-ci/gerrit-and-jenkins))))

(defn log-in
  "{:alice person ...} of usernames; nil with a failed check when a login fails."
  [names]
  (guarded "Logging in"
           #(into {} (map (fn [username] [(keyword username) (login username)])) names)))

(defn -main
  [& _args]
  (when-let [people
             (log-in usernames)]
    (try
      (guarded "The pass" #(pass people))
      (finally
        (guarded "Deleting webhooks" #((requiring-resolve 'manager-svc.e2e-webhooks/delete-saved!) (:alice people)))
        (guarded "Cleanup" #(clean-up! people))
        (guarded "Restoring credentials" #(restore-credentials! people)))))
  (let [exit-code
        (summary!)]
    (shutdown-agents)
    (System/exit exit-code)))
