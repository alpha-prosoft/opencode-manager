(ns manager-svc.e2e-credentials
  "Which credential the platform calls an MCP server with, and for whom: the
   credential hierarchy as a specification, checked against a running stack.

     cd service && MANAGER_URL=http://localhost:8080 clojure -M:e2e-credentials

   The rules. A value of a credential belongs to a person, a team or a
   tenant. A tool call carries the most specific one the credential's
   provided_by allows: the session owner's own, then their team's, then the
   tenant's (what a tenant admin set, else the operator's secret). Sessions
   the platform owns (team sessions, those webhooks and schedules start)
   never carry a person's value. Nothing crosses tenants.

   How it is checked. Every session is a whoami workspace: a sandbox nobody
   prompts, so a pass costs no tokens. A call goes from inside the sandbox
   through the MCP gateway to the mock server whoami (images/mcp-whoami),
   which answers with the credential it was called with. Values are made up
   and name whose they are: whoami-tenant-acme, whoami-team-demo,
   whoami-user-bob.

   The example tenants declare (examples/tenants/*/onboarding/tenant.json):

     acme    WHOAMI_TOKEN    token  tenant, team, user  server whoami          demo, payments, ops
             WHOAMI_ACCOUNT  basic  tenant, team        server whoami-account  demo, payments
     globex  WHOAMI_TOKEN    token  tenant, team, user  server whoami          research, lab
             WHOAMI_KEY      token  user                server whoami-key      lab (header X-Api-Key)

   compose.yml gives every tenant the operator's secrets whoami-operator
   (WHOAMI_TOKEN and, as a username, WHOAMI_ACCOUNT). Ops requires each
   member's own WHOAMI_TOKEN.

   Not here: that a stored value of a scope the credential no longer allows
   is not used needs another tenant.json, and is unit tested
   (credentials.core-test, a-scope-provides-a-value-only-where-provided-by-allows)."
  (:require [clojure.string :as string]
            [jsonista.core :as jsonista]
            [manager-svc.e2e :as e2e]
            [manager-svc.e2e-ci :as e2e-ci])
  (:import [java.util UUID]))

(def workflow
  "whoami-workspace")

(def tenant-of
  {"demo" "acme"
   "payments" "acme"
   "ops" "acme"
   "research" "globex"
   "lab" "globex"})

(def tenant-admins
  {"acme" :alice
   "globex" :gina})

(def team-admins
  "Who sets a team's values in this pass: its team admin, or for ops, which
   has none, the tenant admin."
  {"demo" :frank
   "payments" :dave
   "ops" :alice
   "research" :gina
   "lab" :ivy})

(def workspaces
  "The sessions of a pass, each a whoami workspace of its team: a person's
   own (:owner), a team session its admin starts (:team-admin) and one a
   webhook starts (:webhook); the platform owns the last two."
  (array-map
   :bob-in-demo {:team "demo" :owner :bob}
   :alice-in-demo {:team "demo" :owner :alice}
   :demo-team-session {:team "demo" :team-admin :alice}
   :bob-in-payments {:team "payments" :owner :bob}
   :payments-team-session {:team "payments" :team-admin :dave}
   :henry-in-ops {:team "ops" :owner :henry}
   :ops-webhook-session {:team "ops" :webhook :alice}
   :hank-in-research {:team "research" :owner :hank}
   :ivy-in-research {:team "research" :owner :ivy}
   :ivy-in-lab {:team "lab" :owner :ivy}))

(def every-value
  "Every value a pass may store, removed before it starts and after it ends."
  {"WHOAMI_TOKEN" [[:tenant "acme"] [:tenant "globex"]
                   [:team "demo"] [:team "payments"] [:team "ops"] [:team "research"] [:team "lab"]
                   [:user :alice] [:user :bob] [:user :dave] [:user :henry] [:user :ivy]]
   "WHOAMI_ACCOUNT" [[:tenant "acme"] [:team "demo"] [:team "payments"]]
   "WHOAMI_KEY" [[:user :ivy]]})

(defn- token
  "The made-up value of an owner: [:tenant \"acme\"] has whoami-tenant-acme,
   [:team \"demo\"] whoami-team-demo, [:user :bob] whoami-user-bob."
  [[kind who]]
  (str "whoami-" (name kind) "-" (name who)))

(defn- value
  [credential-id owner]
  (if (= "WHOAMI_ACCOUNT" credential-id)
    {:username (token owner) :secret (str (token owner) "-secret")}
    {:secret (token owner)}))

(defn- keeper
  "[person scope]: who sets and removes an owner's value, and in which scope."
  [people [kind who]]
  (case kind
    :tenant [(people (tenant-admins who)) :tenant]
    :team [(people (team-admins who)) [:team who]]
    :user [(people who) :user]))

(def stored
  "{credential-id #{owner}}: the values the pass holds right now."
  (atom {}))

(defn- store!
  [people credential-id owner]
  (let [[person scope]
        (keeper people owner)

        saved
        (e2e/put-credential person scope credential-id (value credential-id owner))]
    (when-not (:ok saved)
      (throw (ex-info (str "Could not store " credential-id " of " owner ": " (:error saved)) {})))
    (swap! stored update credential-id (fnil conj #{}) owner)))

(defn- clear!
  [people credential-id owner]
  (let [[person scope]
        (keeper people owner)]
    (e2e/delete-credential person scope credential-id)
    (swap! stored update credential-id disj owner)))

(defn- store-only!
  "Makes owners the only ones with a value of credential-id."
  [people credential-id owners]
  (let [held
        (set (get @stored credential-id))]
    (doseq [owner (remove (set owners) held)]
      (clear! people credential-id owner))
    (doseq [owner (remove held owners)]
      (store! people credential-id owner))))

(defn- clear-everything!
  [people]
  (doseq [[credential-id owners] every-value
          owner owners]
    (clear! people credential-id owner)))

(defn- json
  [text]
  (try
    (jsonista/read-value text jsonista/keyword-keys-object-mapper)
    (catch Exception _
      nil)))

(def ^:private fetch-script
  "Node, inside a sandbox: posts $BODY to $URL with the key $KEY and prints
   the answer as {status, body}."
  (str "fetch(process.env.URL, {method: 'POST', body: process.env.BODY, headers: {"
       "'X-Platform-Key': process.env.KEY, 'Content-Type': 'application/json', "
       "'Accept': 'application/json, text/event-stream'}})"
       ".then(async answer => console.log(JSON.stringify({status: answer.status, body: await answer.text()})))"))

(defn- post-to-gateway
  "{:status :body} of a JSON-RPC message sent to the gateway of server-id
   from inside the sandbox of session-id, authenticated with key."
  [session-id key server-id message]
  (let [answer
        (json (e2e/sh "docker" "exec"
                      "-e" (str "URL=http://manager:8001/mcp-gw/" server-id)
                      "-e" (str "KEY=" key)
                      "-e" (str "BODY=" (jsonista/write-value-as-string message))
                      (e2e/container-of session-id) "node" "-e" fetch-script))]
    (update answer :body json)))

(defn- tool-call
  [tool arguments]
  {"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"name" tool "arguments" arguments}})

(defn- outcome
  "What the gateway's answer comes to: {:answer} of the tool, or {:refused}
   with the message a person or their agent reads; the HTTP :status."
  [{:keys [status body]}]
  (let [{:keys [content isError]}
        (:result body)

        text
        (apply str (map :text content))]
    (cond
      (:error body) {:status status :refused (get-in body [:error :message])}
      (:detail body) {:status status :refused (:detail body)}
      isError {:status status :refused text}
      :else {:status status :answer (json text)})))

(defn- call
  "Calls a tool of server-id from inside session's sandbox."
  ([session server-id tool]
   (call session server-id tool {}))
  ([{:keys [id key]} server-id tool arguments]
   (outcome (post-to-gateway id key server-id (tool-call tool arguments)))))

(defn- called-as
  "Whose value a call from session to server-id carries: the token or
   username the mock received, or the refusal."
  [session server-id]
  (let [{:keys [answer refused]}
        (call session server-id "whoami")]
    (or (:token answer) (:username answer) refused)))

(defn- mock-container
  []
  (first (string/split-lines (e2e/sh "docker" "ps" "-aq" "--filter" "label=com.docker.compose.service=mcp-whoami"))))

(defn- mock-calls
  "{tool count} of the calls that reached the mock since it started, nil
   while it is down."
  []
  (json (e2e/sh "docker" "exec" (mock-container) "python" "-c"
                "import urllib.request; print(urllib.request.urlopen('http://localhost:8000/calls').read().decode())")))

(defn- tools-key
  [session-id]
  (get-in (e2e/sandbox-config session-id) [:mcp :whoami :headers :X-Platform-Key]))

(defn- title
  [workspace]
  (str "e2e credentials " (name workspace)))

(defn- start-team-session!
  [admin team workspace]
  (let [run-id
        (UUID/randomUUID)

        started
        (e2e/command admin {:cmd-id :start-team-session :id run-id
                            :attrs {:team-id team :workflow-id workflow :inputs {:title (title workspace)}}})]
    (when (e2e/check (str "a team admin starts a team session in " team) (:ok started) (:error started))
      (e2e/track-run! admin run-id))))

(def webhooks
  "[admin id] of the webhooks a pass saved, deleted at its end."
  (atom []))

(defn- start-by-webhook!
  "Saves a webhook for the team's whoami workspace and calls it without a
   login, so the platform starts the run."
  [admin team workspace]
  (let [id
        (UUID/randomUUID)

        _
        (swap! webhooks conj [admin id])

        saved
        (e2e/request admin "PUT" (str "/api/webhooks/" id)
                     {:name (title workspace)
                      :scope {:kind "team" :team-id team}
                      :target {:team-id team :workflow-id workflow}
                      :auth {:type "none"}
                      :mapping [{:input "title" :source "body" :value "$.title"}]})

        called
        (e2e/request (e2e/anonymous) "POST" (str "/hooks/" (tenant-of team) "/" id) {:title (title workspace)})]
    (when (e2e/check (str "a webhook starts a run in " team " as the platform")
                     (= [200 202] [(:status saved) (:status called)])
                     [(:status saved) (:status called) (:data called)])
      (e2e/track-run! admin (parse-uuid (str (get-in called [:data :run-id])))))))

(defn- start!
  "Starts a workspace: [the person who follows its run, the run's id]."
  [people workspace {:keys [team owner team-admin webhook]}]
  (cond
    owner [(people owner) (e2e/start-run (people owner) team workflow {:title (title workspace)})]
    team-admin [(people team-admin) (start-team-session! (people team-admin) team workspace)]
    webhook [(people webhook) (start-by-webhook! (people webhook) team workspace)]))

(defn- running-session
  "{:id :key :person} of a workspace once its run has a running session."
  [workspace [person run-id]]
  (let [run
        (e2e/wait-for-run person run-id)

        session-id
        (:session-id run)]
    (when (e2e/check (str (name workspace) " runs") (and (= :completed (:status run)) session-id) (or (:error run) (:status run)))
      {:id session-id :key (tools-key session-id) :person person})))

(def requirement
  "Team Operations requires your own Who am I credential. Add it under Accounts, then start again; team and tenant values don't count.")

(defn- required-before-work
  "Ops requires each member's own WHOAMI_TOKEN (its team.json): with only the
   team's and the tenant's value henry starts nothing there. Leaves him with
   his own, so his workspace starts."
  [{:keys [henry] :as people}]
  (e2e/section "Credentials: what a team requires of its members before they work in it")
  (e2e/add-required! henry)
  (e2e/delete-credential henry :user "WHOAMI_TOKEN")
  (store-only! people "WHOAMI_TOKEN" [[:tenant "acme"] [:team "ops"]])
  (let [missing
        #(set (:missing-required (e2e/result henry {:query-id :get-my-credentials})))

        refused
        (e2e/command henry {:cmd-id :start-run :id (UUID/randomUUID)
                            :attrs {:team-id "ops" :workflow-id workflow :inputs {:title (title :henry-in-ops)}}})]
    (e2e/check "the team's value serves the team but doesn't satisfy what it requires of a member"
               (and (= :team (e2e/credential-state-in henry "WHOAMI_TOKEN" "ops"))
                    (contains? (missing) "WHOAMI_TOKEN"))
               (e2e/my-credential henry "WHOAMI_TOKEN"))
    (e2e/check "without their own value a member starts no work in the team, and is told what to add where"
               (= [422 requirement] ((juxt e2e/error-status e2e/error-message) refused))
               refused)
    (store! people "WHOAMI_TOKEN" [:user :henry])
    (e2e/check "with their own value nothing is missing"
               (not (contains? (missing) "WHOAMI_TOKEN"))
               (missing))))

(defn- open-workspaces
  "{:people :sessions {workspace session}}: every workspace started and
   running."
  [{:keys [alice gina] :as people}]
  (e2e/section "Credentials: a whoami workspace in every team")
  (doseq [[team-id tenant] tenant-of]
    (e2e/room-in-team! (if (= "acme" tenant) alice gina) team-id))
  (let [started
        (into {} (for [[workspace spec] workspaces]
                   [workspace (start! people workspace spec)]))]
    {:people people
     :sessions (into {} (for [workspace (keys workspaces)]
                          [workspace (running-session workspace (started workspace))]))}))

(defn- close-workspaces!
  [{:keys [sessions]}]
  (let [opened
        (remove nil? (vals sessions))]
    (doseq [{:keys [id person]} opened]
      (e2e/delete-session! person id))
    (e2e/wait-until 120000 1000
                    (fn []
                      (every? (fn [{:keys [id person]}]
                                (not= :running (get-in (e2e/session-of person id) [:ok :status])))
                              opened)))))

(defn- resolves!
  "Stores exactly :stored of credential-id, then checks whose value a call
   to server-id carries from each session of :sees."
  [{:keys [people sessions]} credential-id server-id {:keys [rule stored sees]}]
  (store-only! people credential-id stored)
  (doseq [[workspace expected] (sort-by key sees)]
    (let [seen
          (called-as (sessions workspace) server-id)]
      (e2e/check (str rule ": " (name workspace) " is called as " expected) (= expected seen) seen))))

(def resolution
  "The hierarchy of WHOAMI_TOKEN, a level at a time and back: what is stored
   and whose value each session's calls then carry."
  [{:rule "nothing stored, the operator's secret serves every tenant"
    :stored []
    :sees {:bob-in-demo "whoami-operator"
           :alice-in-demo "whoami-operator"
           :demo-team-session "whoami-operator"
           :bob-in-payments "whoami-operator"
           :payments-team-session "whoami-operator"
           :henry-in-ops "whoami-operator"
           :ops-webhook-session "whoami-operator"
           :hank-in-research "whoami-operator"
           :ivy-in-research "whoami-operator"
           :ivy-in-lab "whoami-operator"}}
   {:rule "a tenant's value serves everyone in that tenant and nobody in another"
    :stored [[:tenant "acme"]]
    :sees {:bob-in-demo "whoami-tenant-acme"
           :alice-in-demo "whoami-tenant-acme"
           :demo-team-session "whoami-tenant-acme"
           :bob-in-payments "whoami-tenant-acme"
           :payments-team-session "whoami-tenant-acme"
           :henry-in-ops "whoami-tenant-acme"
           :ops-webhook-session "whoami-tenant-acme"
           :hank-in-research "whoami-operator"
           :ivy-in-research "whoami-operator"
           :ivy-in-lab "whoami-operator"}}
   {:rule "two tenants hold values of the same credential id, each its own"
    :stored [[:tenant "acme"] [:tenant "globex"]]
    :sees {:bob-in-demo "whoami-tenant-acme"
           :henry-in-ops "whoami-tenant-acme"
           :hank-in-research "whoami-tenant-globex"
           :ivy-in-research "whoami-tenant-globex"
           :ivy-in-lab "whoami-tenant-globex"}}
   {:rule "a team's value wins in that team and only there"
    :stored [[:tenant "acme"] [:tenant "globex"] [:team "demo"] [:team "research"]]
    :sees {:bob-in-demo "whoami-team-demo"
           :alice-in-demo "whoami-team-demo"
           :demo-team-session "whoami-team-demo"
           :bob-in-payments "whoami-tenant-acme"
           :payments-team-session "whoami-tenant-acme"
           :henry-in-ops "whoami-tenant-acme"
           :ops-webhook-session "whoami-tenant-acme"
           :hank-in-research "whoami-team-research"
           :ivy-in-research "whoami-team-research"
           :ivy-in-lab "whoami-tenant-globex"}}
   {:rule "every team with a value uses its own"
    :stored [[:tenant "acme"] [:tenant "globex"]
             [:team "demo"] [:team "payments"] [:team "ops"] [:team "research"] [:team "lab"]]
    :sees {:bob-in-demo "whoami-team-demo"
           :bob-in-payments "whoami-team-payments"
           :payments-team-session "whoami-team-payments"
           :henry-in-ops "whoami-team-ops"
           :ops-webhook-session "whoami-team-ops"
           :ivy-in-research "whoami-team-research"
           :ivy-in-lab "whoami-team-lab"}}
   {:rule "a person's own value wins in each of their teams, for them only, and never in a session the platform owns"
    :stored [[:tenant "acme"] [:tenant "globex"]
             [:team "demo"] [:team "payments"] [:team "ops"] [:team "research"] [:team "lab"]
             [:user :alice] [:user :bob] [:user :dave] [:user :ivy]]
    :sees {:bob-in-demo "whoami-user-bob"
           :alice-in-demo "whoami-user-alice"
           :demo-team-session "whoami-team-demo"
           :bob-in-payments "whoami-user-bob"
           :payments-team-session "whoami-team-payments"
           :henry-in-ops "whoami-team-ops"
           :ops-webhook-session "whoami-team-ops"
           :hank-in-research "whoami-team-research"
           :ivy-in-research "whoami-user-ivy"
           :ivy-in-lab "whoami-user-ivy"}}
   {:rule "without the team's value, its people keep their own and the team's sessions fall back to the tenant's"
    :stored [[:tenant "acme"] [:tenant "globex"]
             [:team "payments"] [:team "ops"] [:team "research"]
             [:user :alice] [:user :bob] [:user :dave] [:user :ivy]]
    :sees {:bob-in-demo "whoami-user-bob"
           :alice-in-demo "whoami-user-alice"
           :demo-team-session "whoami-tenant-acme"
           :bob-in-payments "whoami-user-bob"
           :ivy-in-research "whoami-user-ivy"
           :ivy-in-lab "whoami-user-ivy"}}
   {:rule "without their own value, a person falls back to the team's, else the tenant's, per team"
    :stored [[:tenant "acme"] [:tenant "globex"]
             [:team "payments"] [:team "ops"] [:team "research"]
             [:user :alice] [:user :dave]]
    :sees {:bob-in-demo "whoami-tenant-acme"
           :alice-in-demo "whoami-user-alice"
           :bob-in-payments "whoami-team-payments"
           :ivy-in-research "whoami-team-research"
           :ivy-in-lab "whoami-tenant-globex"}}
   {:rule "without the tenant's value, only its teams and people without one fall back to the operator's"
    :stored [[:tenant "globex"]
             [:team "payments"] [:team "ops"] [:team "research"]
             [:user :alice] [:user :dave]]
    :sees {:bob-in-demo "whoami-operator"
           :alice-in-demo "whoami-user-alice"
           :demo-team-session "whoami-operator"
           :bob-in-payments "whoami-team-payments"
           :payments-team-session "whoami-team-payments"
           :henry-in-ops "whoami-team-ops"
           :hank-in-research "whoami-team-research"
           :ivy-in-lab "whoami-tenant-globex"}}
   {:rule "with everything removed, the operator's secret serves everyone again"
    :stored []
    :sees {:bob-in-demo "whoami-operator"
           :alice-in-demo "whoami-operator"
           :demo-team-session "whoami-operator"
           :bob-in-payments "whoami-operator"
           :payments-team-session "whoami-operator"
           :henry-in-ops "whoami-operator"
           :ops-webhook-session "whoami-operator"
           :hank-in-research "whoami-operator"
           :ivy-in-research "whoami-operator"
           :ivy-in-lab "whoami-operator"}}])

(defn- resolution-order
  [world]
  (e2e/section "Credentials: resolution order, person, team, tenant, operator")
  (doseq [row resolution]
    (resolves! world "WHOAMI_TOKEN" "whoami" row)))

(defn- sessions-the-platform-owns
  [{:keys [people sessions]}]
  (e2e/section "Credentials: sessions the platform owns")
  (let [{:keys [alice bob carol]}
        people

        team-session
        (:ok (e2e/session-of bob (:id (sessions :demo-team-session))))

        webhook-session
        (:ok (e2e/session-of alice (:id (sessions :ops-webhook-session))))]
    (e2e/check "a team session belongs to the platform, not to the admin who started it"
               (= ["platform" true] ((juxt :sub :team-session) team-session))
               (select-keys team-session [:sub :team-session]))
    (e2e/check "so does a session a webhook started"
               (= ["platform" true] ((juxt :sub :team-session) webhook-session))
               (select-keys webhook-session [:sub :team-session]))
    (e2e/check "a member of the team may work in it, with the team's credentials"
               (nil? (:open-blocked team-session))
               (:open-blocked team-session))
    (e2e/check "someone outside the team doesn't see it"
               (= 404 (e2e/error-status (e2e/session-of carol (:id (sessions :demo-team-session))))))))

(def who-may-set
  "[who, where, which credential, :saved or [status reason], the rule]."
  [[:bob :user "WHOAMI_TOKEN" :saved
    "a member sets their own value"]
   [:bob :user "WHOAMI_ACCOUNT" [403 "is managed by your tenant or team admins"]
    "nobody sets their own value of a credential only tenant and team provide"]
   [:hank :user "WHOAMI_KEY" [403 "is not used by your teams"]
    "nobody sets a value of a credential their teams aren't granted"]
   [:bob :user "WHOAMI_KEY" [404 "Unknown credential"]
    "another tenant's credential doesn't exist"]
   [:bob :user "E2E_UNDECLARED" [404 "Unknown credential"]
    "people set only declared credentials"]
   [:oscar :user "WHOAMI_TOKEN" [404 "Unknown credential"]
    "an operator belongs to no tenant and has no credentials there"]
   [:frank [:team "demo"] "WHOAMI_TOKEN" :saved
    "a team admin sets their team's value"]
   [:alice [:team "payments"] "WHOAMI_TOKEN" :saved
    "a tenant admin administers every team of the tenant"]
   [:bob [:team "demo"] "WHOAMI_TOKEN" [403 "Only admins of team demo can do this"]
    "a member doesn't set their team's value"]
   [:dave [:team "demo"] "WHOAMI_TOKEN" [403 "Only admins of team demo can do this"]
    "the admin of another team doesn't either"]
   [:gina [:team "demo"] "WHOAMI_TOKEN" [404 "Team not found"]
    "another tenant's team doesn't exist, even for a tenant admin"]
   [:oscar [:team "demo"] "WHOAMI_TOKEN" [404 "Unknown credential"]
    "nor do a tenant's teams and theirs"]
   [:ivy [:team "lab"] "WHOAMI_KEY" [422 "has no team values (see its provided_by)"]
    "a team has no value of a credential only people provide"]
   [:alice [:team "ops"] "WHOAMI_ACCOUNT" [403 "is not used by team ops"]
    "a team has no value of a credential it isn't granted"]
   [:frank [:team "demo"] "E2E_UNDECLARED" [404 "Unknown credential"]
    "teams set only declared credentials"]
   [:alice :tenant "WHOAMI_TOKEN" :saved
    "a tenant admin sets the tenant's value"]
   [:alice :tenant "E2E_UNDECLARED" :saved
    "a tenant admin sets a secret no credential declares: it has a tenant value only"]
   [:frank :tenant "WHOAMI_TOKEN" [403 "Tenant administrators only"]
    "a team admin doesn't set the tenant's value"]
   [:bob :tenant "WHOAMI_TOKEN" [403 "Tenant administrators only"]
    "a member doesn't either"]
   [:oscar :tenant "WHOAMI_TOKEN" [403 "Tenant administrators only"]
    "nor an operator: tenants keep their credentials to themselves"]
   [:gina :tenant "WHOAMI_KEY" [422 "is personal only: every user sets their own value"]
    "a tenant has no value of a credential only people provide"]])

(defn- scope-name
  [scope]
  (if (vector? scope)
    (str "team " (second scope))
    (name scope)))

(defn- may-set!
  "Checks one row of who-may-set, with a value the mock's check accepts,
   and removes what was saved."
  [people [who scope credential-id expected rule]]
  (let [person
        (people who)

        response
        (e2e/put-credential person scope credential-id (value credential-id [:user who]))]
    (e2e/check (str rule " (" (name who) ", " (scope-name scope) " value of " credential-id ")")
               (if (= :saved expected)
                 (:ok response)
                 (and (= (first expected) (e2e/error-status response))
                      (string/includes? (e2e/error-message response) (second expected))))
               response)
    (when (:ok response)
      (e2e/delete-credential person scope credential-id))))

(def who-may-remove
  "[who, whose value of WHOAMI_TOKEN, the status]."
  [[:bob [:team "demo"] 403]
   [:dave [:team "demo"] 403]
   [:bob :tenant 403]
   [:frank :tenant 403]])

(defn- who-sets-what
  [{:keys [people] :as world}]
  (e2e/section "Credentials: who may set and remove what")
  (doseq [row who-may-set]
    (may-set! people row))
  (store-only! people "WHOAMI_TOKEN" [[:tenant "acme"] [:team "demo"]])
  (doseq [[who scope status] who-may-remove]
    (e2e/check (str (name who) " can't remove the " (scope-name scope) " value")
               (= status (e2e/error-status (e2e/delete-credential (people who) scope "WHOAMI_TOKEN")))))
  (e2e/delete-credential (:gina people) [:team "demo"] "WHOAMI_TOKEN")
  (e2e/delete-credential (:gina people) :tenant "WHOAMI_TOKEN")
  (resolves! world "WHOAMI_TOKEN" "whoami"
             {:rule "what another tenant's admin removes is never this tenant's"
              :stored [[:tenant "acme"] [:team "demo"]]
              :sees {:bob-in-demo "whoami-team-demo"
                     :bob-in-payments "whoami-tenant-acme"}})
  (store-only! people "WHOAMI_TOKEN" []))

(defn- tenants-keep-apart
  [{:keys [people sessions]}]
  (e2e/section "Credentials: tenants keep apart")
  (let [{:keys [alice gina hank]}
        people

        acme-session
        (:id (sessions :bob-in-demo))

        globex-session
        (:id (sessions :hank-in-research))]
    (e2e/check "another tenant's session is not found, shared or not"
               (= [404 404]
                  [(e2e/error-status (e2e/session-of gina acme-session))
                   (e2e/error-status (e2e/session-of alice globex-session))]))
    (e2e/check "nor is its audit trail"
               (= 404 (e2e/error-status (e2e/query gina {:query-id :list-audit-events :session-id acme-session :after 0 :limit 10}))))
    (e2e/check "each tenant admin sees their own tenant's whoami servers"
               (= [#{"whoami" "whoami-account"} #{"whoami" "whoami-key"}]
                  (for [admin [alice gina]]
                    (set (filter #(string/starts-with? % "whoami")
                                 (map :id (e2e/result admin {:query-id :list-mcp-servers})))))))
    (e2e/check "people are offered only their own tenant's credentials"
               (= #{"WHOAMI_TOKEN"}
                  (set (filter #(string/starts-with? % "WHOAMI")
                               (map :id (:credentials (e2e/result hank {:query-id :get-my-credentials})))))))))

(def missing-key
  "Who am I (API key) needs your WHOAMI_KEY credential. Add it under Accounts in the platform.")

(defn- kinds-and-headers
  [{:keys [people sessions] :as world}]
  (e2e/section "Credentials: token, basic and a custom header")
  (let [demo
        (sessions :bob-in-demo)

        lab
        (sessions :ivy-in-lab)

        own-key
        #(call lab "whoami-key" "whoami")

        token
        (call demo "whoami" "whoami")

        account
        (call demo "whoami-account" "whoami")

        without-username
        (e2e/put-credential (:alice people) :tenant "WHOAMI_ACCOUNT" {:secret "whoami-tenant-acme-secret"})

        before
        (own-key)

        _
        (store! people "WHOAMI_KEY" [:user :ivy])

        with-key
        (own-key)

        _
        (clear! people "WHOAMI_KEY" [:user :ivy])

        after
        (own-key)]
    (e2e/check "a token travels as a bearer token"
               (= {:header "authorization" :scheme "bearer" :token "whoami-operator"} (:answer token))
               token)
    (e2e/check "a basic credential travels with its username, here the operator's, and the mock never says the secret"
               (= {:header "authorization" :scheme "basic" :username "whoami-operator"} (:answer account))
               account)
    (resolves! world "WHOAMI_ACCOUNT" "whoami-account"
               {:rule "a basic credential resolves like any other"
                :stored [[:tenant "acme"] [:team "demo"]]
                :sees {:bob-in-demo "whoami-team-demo"
                       :demo-team-session "whoami-team-demo"
                       :bob-in-payments "whoami-tenant-acme"}})
    (store-only! people "WHOAMI_ACCOUNT" [])
    (e2e/check "a basic credential needs a username"
               (= [422 "Who am I account needs a username as well"]
                  ((juxt e2e/error-status e2e/error-message) without-username))
               without-username)
    (e2e/check "without a value anywhere the gateway refuses the call and says what is missing"
               (= {:status 200 :refused missing-key} before)
               before)
    (e2e/check "a server's own header and scheme carry the value as configured"
               (= {:header "x-api-key" :scheme "raw" :token "whoami-user-ivy"} (:answer with-key))
               with-key)
    (e2e/check "once the person removes their value the gateway refuses again"
               (= {:status 200 :refused missing-key} after)
               after)))

(defn- fork!
  "Forks source into team as person: the fork as a session to call from,
   once it runs."
  [person source team]
  (let [fork-id
        (e2e/track-session! person (UUID/randomUUID))

        forked
        (e2e/command person {:cmd-id :fork-session :id fork-id :attrs {:source-id (:id source) :team-id team}})]
    (when (and (e2e/check (str (:username person) " forks a session into " team) (:ok forked) (:error forked))
               (e2e/wait-until 180000 1000 #(= :running (get-in (e2e/session-of person fork-id) [:ok :status]))))
      {:id fork-id :key (tools-key fork-id) :person person})))

(def fork-resolution
  "bob forks the payments team session into demo."
  [{:rule "a fork never carries the source team's value"
    :stored [[:tenant "acme"] [:team "payments"]]
    :sees {:payments-team-session "whoami-team-payments"
           :fork "whoami-tenant-acme"}}
   {:rule "it carries the value of the team it landed in"
    :stored [[:tenant "acme"] [:team "payments"] [:team "demo"]]
    :sees {:fork "whoami-team-demo"}}
   {:rule "a fork of a team session is its forker's own, so their value wins there and only there"
    :stored [[:tenant "acme"] [:team "payments"] [:team "demo"] [:user :bob]]
    :sees {:payments-team-session "whoami-team-payments"
           :fork "whoami-user-bob"}}])

(defn- forks
  [{:keys [people sessions] :as world}]
  (e2e/section "Credentials: forks")
  (let [{:keys [bob ivy]}
        people

        fork
        (fork! bob (sessions :payments-team-session) "demo")

        seen
        (:ok (e2e/session-of bob (:id fork)))

        refused
        (e2e/command ivy {:cmd-id :fork-session :id (UUID/randomUUID)
                          :attrs {:source-id (:id (sessions :ivy-in-lab)) :team-id "research"}})]
    (e2e/check "the fork belongs to the forker and the team they picked"
               (= ["bob" "demo" :private] ((juxt :sub :team-id (comp keyword :visibility)) seen))
               (select-keys seen [:sub :team-id :visibility]))
    (doseq [row fork-resolution]
      (resolves! (assoc-in world [:sessions :fork] fork) "WHOAMI_TOKEN" "whoami" row))
    (store-only! people "WHOAMI_TOKEN" [])
    (e2e/check "a team that isn't granted the session's MCP server can't take the fork, and the refusal names both"
               (and (= 422 (e2e/error-status refused))
                    (every? #(string/includes? (e2e/error-message refused) %)
                            ["Team Research can't run this session" "may not use MCP server whoami-key"]))
               refused)
    (e2e/delete-session! bob (:id fork))))

(def refusals
  "[what is called, from which session, server, tool, HTTP status, the
   message]. None of these calls may reach the mock."
  [["a tool the server's allow-list leaves out"
    :bob-in-demo "whoami" "delete_account" 200 "Tool delete_account is not allowed on Who am I"]
   ["a tool allowed on one server and not on another"
    :bob-in-demo "whoami-account" "echo" 200 "Tool echo is not allowed on Who am I (shared account)"]
   ["a server the team is granted but the session's harness doesn't use"
    :henry-in-ops "bitbucket" "whoami" 403 "This harness may not use MCP server bitbucket"]
   ["a server the tenant doesn't grant the team"
    :hank-in-research "whoami-key" "whoami" 403 "This harness may not use MCP server whoami-key"]
   ["a server only another tenant declares"
    :hank-in-research "whoami-account" "whoami" 403 "This harness may not use MCP server whoami-account"]
   ["a server nobody declares"
    :bob-in-demo "nowhere" "whoami" 403 "This harness may not use MCP server nowhere"]])

(defn- grants-and-allow-lists
  [{:keys [sessions]}]
  (e2e/section "Credentials: grants and allow-lists")
  (let [before
        (mock-calls)

        demo
        (sessions :bob-in-demo)

        listed
        (post-to-gateway (:id demo) (:key demo) "whoami" {"jsonrpc" "2.0" "id" 1 "method" "tools/list"})]
    (doseq [[what workspace server-id tool status message] refusals]
      (let [refused
            (call (sessions workspace) server-id tool)]
        (e2e/check (str what " is refused: " message)
                   (= {:status status :refused message} refused)
                   refused)))
    (let [after
          (mock-calls)

          echoed
          (call demo "whoami" "echo" {"text" "hello"})]
      (e2e/check "none of the refused calls reached the server" (= before after) [before after])
      (e2e/check "a session is shown only the tools on the allow-list"
                 (= ["whoami" "echo"] (mapv :name (get-in listed [:body :result :tools])))
                 (:body listed))
      (e2e/check "an allowed tool is called with its arguments" (= {:text "hello"} (:answer echoed)) echoed)
      (e2e/check "and that call did reach the server"
                 (= (update after :echo inc) (mock-calls))))))

(def rejected
  "Not saved. The service rejected it (HTTP 401).")

(defn- rejects?
  [response message]
  (= [422 message] ((juxt e2e/error-status e2e/error-message) response)))

(defn- stored-and-used?
  "Stores an owner's value of WHOAMI_TOKEN; true when session's calls then carry it."
  [people session owner]
  (store! people "WHOAMI_TOKEN" owner)
  (= (token owner) (called-as session "whoami")))

(defn- check-on-save
  [{:keys [people sessions]}]
  (e2e/section "Credentials: the check before a value is saved")
  (let [{:keys [alice bob frank ivy]}
        people

        demo
        (sessions :bob-in-demo)

        wrong
        {:secret "not-a-whoami-value"}]
    (doseq [[person scope] [[bob :user] [frank [:team "demo"]] [alice :tenant]]]
      (let [response
            (e2e/put-credential person scope "WHOAMI_TOKEN" wrong)]
        (e2e/check (str "a " (scope-name scope) " value its service rejects is not saved, and the answer says so")
                   (rejects? response rejected)
                   response)))
    (let [seen
          [(called-as demo "whoami") (e2e/credential-state-in bob "WHOAMI_TOKEN" "demo")]]
      (e2e/check "nothing of it was stored: calls still carry the operator's secret"
                 (= ["whoami-operator" :tenant] seen)
                 seen))
    (e2e/check "tenant admins set a tenant value once it passes its check"
               (stored-and-used? people demo [:tenant "acme"]))
    (e2e/check "team admins set a team value once it passes its check"
               (stored-and-used? people demo [:team "demo"]))
    (e2e/check "a valid personal credential is checked and saved"
               (and (stored-and-used? people demo [:user :bob])
                    (some? (:verified-at (e2e/my-credential bob "WHOAMI_TOKEN"))))
               (e2e/my-credential bob "WHOAMI_TOKEN"))
    (let [replaced
          (e2e/put-credential bob :user "WHOAMI_TOKEN" wrong)

          seen
          (called-as demo "whoami")]
      (e2e/check "a rejected replacement leaves the value there was"
                 (and (rejects? replaced rejected) (= "whoami-user-bob" seen))
                 [replaced seen]))
    (let [blank
          (e2e/put-credential bob :user "WHOAMI_TOKEN" {:secret " "})]
      (e2e/check "an empty value is refused before any check" (rejects? blank "A secret is required") blank))
    (store-only! people "WHOAMI_TOKEN" [])
    (let [wrong-secret
          (e2e/put-credential alice :tenant "WHOAMI_ACCOUNT" {:username "whoami-tenant-acme" :secret "not-a-whoami-value"})]
      (e2e/check "a basic value is checked with its username and its secret" (rejects? wrong-secret rejected) wrong-secret))
    (let [through-tool
          (e2e/put-credential ivy :user "WHOAMI_KEY" wrong)

          accepted
          (e2e/put-credential ivy :user "WHOAMI_KEY" (value "WHOAMI_KEY" [:user :ivy]))]
      (e2e/check "a credential checked through an MCP tool is not saved when the tool refuses it"
                 (and (= 422 (e2e/error-status through-tool))
                      (string/starts-with? (e2e/error-message through-tool) "Not saved.")
                      (string/includes? (e2e/error-message through-tool) "whoami refused the credential"))
                 through-tool)
      (e2e/check "and is saved when the tool answers" (:ok accepted) accepted))
    (e2e/delete-credential ivy :user "WHOAMI_KEY")))

(defn- mcp-records
  "[server tool credential source] of the session's audited MCP calls, oldest first."
  [person session-id]
  (for [{:keys [type payload]} (e2e/result person {:query-id :list-audit-events :session-id session-id :after 0 :limit 1000})
        :when (= "mcp" (name type))]
    [(:server payload) (:tool payload) (:credential payload) (name (:source payload))]))

(def held-by-bobs-session
  "The values a call from bob's demo session may carry while each level holds one."
  ["whoami-user-bob" "whoami-team-demo" "whoami-tenant-acme" "whoami-operator"])

(def sandbox-search
  "Shell, inside a sandbox: prints the lines of its environment and files
   that hold one of those values."
  (let [values
        (string/join " " (map #(str "-e " %) held-by-bobs-session))]
    (str "{ env; grep -rIs " values " /etc/opencode /session /tmp /shared 2>/dev/null; } | grep " values "; true")))

(defn- what-is-recorded
  [{:keys [people sessions]}]
  (e2e/section "Credentials: what is recorded and shown")
  (let [{:keys [alice bob frank ivy]}
        people

        demo
        (sessions :bob-in-demo)

        before
        (count (mcp-records bob (:id demo)))

        states
        (fn [person credential-id teams]
          (mapv #(e2e/credential-state-in person credential-id %) teams))]
    (store-only! people "WHOAMI_TOKEN" [[:tenant "acme"] [:team "demo"] [:user :bob] [:team "lab"]])
    (e2e/check "people see where a credential's value comes from, per team: their own everywhere"
               (= [:own :own] (states bob "WHOAMI_TOKEN" ["demo" "payments"]))
               (states bob "WHOAMI_TOKEN" ["demo" "payments"]))
    (e2e/check "someone in two teams of another tenant sees the team's value in one and the tenant's in the other"
               (= [:team :tenant] (states ivy "WHOAMI_TOKEN" ["lab" "research"]))
               (states ivy "WHOAMI_TOKEN" ["lab" "research"]))
    (call demo "whoami" "whoami")
    (clear! people "WHOAMI_TOKEN" [:user :bob])
    (e2e/check "without their own, the team's where there is one and the tenant's elsewhere"
               (= [:team :tenant] (states bob "WHOAMI_TOKEN" ["demo" "payments"]))
               (states bob "WHOAMI_TOKEN" ["demo" "payments"]))
    (call demo "whoami" "whoami")
    (clear! people "WHOAMI_TOKEN" [:team "demo"])
    (call demo "whoami" "whoami")
    (clear! people "WHOAMI_TOKEN" [:tenant "acme"])
    (e2e/check "the operator's secret shows as the tenant's value"
               (= [:tenant :tenant] (states bob "WHOAMI_TOKEN" ["demo" "payments"]))
               (states bob "WHOAMI_TOKEN" ["demo" "payments"]))
    (call demo "whoami" "whoami")
    (call demo "whoami" "delete_account")
    (e2e/check "MCP calls are audited with whose credential was used"
               (= [["whoami" "whoami" "WHOAMI_TOKEN" "user"]
                   ["whoami" "whoami" "WHOAMI_TOKEN" "team"]
                   ["whoami" "whoami" "WHOAMI_TOKEN" "tenant"]
                   ["whoami" "whoami" "WHOAMI_TOKEN" "tenant"]]
                  (drop before (mcp-records bob (:id demo))))
               (drop before (mcp-records bob (:id demo))))
    (e2e/check "the audit trail is intact and holds no value"
               (and (true? (:valid (e2e/result bob {:query-id :verify-audit :session-id (:id demo)})))
                    (not (string/includes? (pr-str (e2e/result bob {:query-id :list-audit-events :session-id (:id demo) :after 0 :limit 1000}))
                                           "whoami-user-bob"))))
    (store-only! people "WHOAMI_TOKEN" [[:tenant "acme"] [:team "demo"] [:user :bob]])
    (let [shown
          (pr-str [(e2e/result bob {:query-id :get-my-credentials})
                   (e2e/result frank {:query-id :list-team-credentials :team-id "demo"})
                   (e2e/result alice {:query-id :list-admin-credentials})
                   (e2e/result alice {:query-id :list-mcp-servers})])

          seen
          (called-as demo "whoami")

          found
          (e2e/in-sandbox (:id demo) sandbox-search)]
      (e2e/check "no query returns a value people stored"
                 (not-any? #(string/includes? shown %) (butlast held-by-bobs-session))
                 (filter #(string/includes? shown %) held-by-bobs-session))
      (e2e/check "the sandbox holds no value, neither in its environment nor in its files, while its calls carry one"
                 (and (= "whoami-user-bob" seen) (string/blank? found))
                 [seen found]))
    (e2e/check "the sandbox can't reach the server itself, only the gateway"
               (string/includes? (e2e/in-sandbox (:id demo) "wget -q -T 5 -O- http://mcp-whoami:8000/calls 2>&1 && echo reachable || echo blocked")
                                 "blocked"))
    (store-only! people "WHOAMI_TOKEN" [])))

(defn- required-of-members
  "What ops requires of henry is checked before his workspace starts
   (required-before-work); here, what it means for work in progress."
  [{:keys [people sessions]}]
  (e2e/section "Credentials: a required credential and work in progress")
  (store-only! people "WHOAMI_TOKEN" [[:tenant "acme"] [:team "ops"]])
  (let [own-session
        (called-as (sessions :henry-in-ops) "whoami")

        platform-session
        (called-as (sessions :ops-webhook-session) "whoami")]
    (e2e/check "work a member has running is left alone when they remove their value, on the team's"
               (= "whoami-team-ops" own-session)
               own-session)
    (e2e/check "the platform owes nothing: the webhook's session runs on the team's value"
               (= "whoami-team-ops" platform-session)
               platform-session))
  (store! people "WHOAMI_TOKEN" [:user :henry])
  (let [own-session
        (called-as (sessions :henry-in-ops) "whoami")]
    (e2e/check "with their own value back, their calls carry it"
               (= "whoami-user-henry" own-session)
               own-session))
  (store-only! people "WHOAMI_TOKEN" []))

(def invalid-key
  {:status 401 :refused "Invalid gateway key"})

(defn- session-keys
  "A sandbox's gateway key stands for its session and for nothing else."
  [{:keys [people sessions]}]
  (e2e/section "Credentials: the gateway key of a session")
  (let [{:keys [alice]}
        people

        demo
        (sessions :bob-in-demo)

        other
        (sessions :alice-in-demo)

        signature
        (second (string/split (str (:key demo)) #"\." 2))

        before
        (mock-calls)

        with-key
        (fn [key]
          (select-keys (call (assoc demo :key key) "whoami" "whoami") [:status :refused]))

        on-the-public-listener
        (:status (e2e/send! (:client (e2e/anonymous)) "POST" (str e2e/base-url "/mcp-gw/whoami")
                            {:headers {"X-Platform-Key" (:key demo)}
                             :body (jsonista/write-value-as-string (tool-call "whoami" {}))}))]
    (doseq [[what key] [["no key" ""]
                        ["a forged signature" (str (:id demo) ".forged")]
                        ["this session's signature under another session's id" (str (:id other) "." signature)]
                        ["the session's LLM key" (get-in (e2e/sandbox-config (:id demo)) [:provider :platform :options :apiKey])]
                        ["the session's telemetry key" (e2e/telemetry-key (:id demo))]]]
      (let [answer
            (with-key key)]
        (e2e/check (str "the gateway refuses " what) (= invalid-key answer) answer)))
    (e2e/check "the public listener has no gateway: a key is worth nothing there"
               (= 404 on-the-public-listener)
               on-the-public-listener)
    (e2e/stop-session! alice (:id other))
    (let [stopped
          (e2e/wait-until 120000 1000 #(= :stopped (get-in (e2e/session-of alice (:id other)) [:ok :status])))

          answer
          (with-key (:key other))]
      (e2e/check "a stopped session's key is refused, wherever it is sent from"
                 (and stopped (= invalid-key answer))
                 [stopped answer]))
    (let [after
          (mock-calls)]
      (e2e/check "none of these calls reached the server" (= before after) [before after]))))

(defn- projects
  "A project names the credential its repository uses; its value resolves
   like any other, for the team and the person. There are no values of a
   project's own."
  [{:keys [people]}]
  (e2e/section "Credentials: projects")
  (let [{:keys [bob frank]}
        people

        changes
        (fn [person]
          (e2e/query person {:query-id :list-open-pull-requests :team-id "demo" :workflow-id "gerrit-review"}))]
    (let [shown
          [(:project (e2e/result bob {:query-id :get-project :team-id "demo" :project-id "demo-service"}))
           (e2e/result bob {:query-id :list-projects})]]
      (e2e/check "a project is shown with its repository and without the credential it names"
                 (and (= "demo-service" (get-in shown [0 :scm :repository]))
                      (not (string/includes? (pr-str shown) "GERRIT_TOKEN")))
                 shown))
    (if-not (e2e-ci/reachable?)
      (println "  Gerrit is not reachable: skipping how a project's repository credential resolves (compose.ci.yml)")
      (try
        (e2e/check "a project's changes are listed with the tenant's value of its credential"
                   (sequential? (:ok (changes bob)))
                   (changes bob))
        (e2e/put-credential bob :user "GERRIT_TOKEN" {:username "bob" :secret "e2e-not-bobs-gerrit-password"})
        (e2e/check "a person's own value wins for what they list, here one Gerrit refuses"
                   (= [502 "Could not list pull requests"] ((juxt e2e/error-status e2e/error-message) (changes bob)))
                   (changes bob))
        (e2e/check "and only for them"
                   (sequential? (:ok (changes frank)))
                   (changes frank))
        (finally
          (e2e/delete-credential bob :user "GERRIT_TOKEN"))))))

(defn- with-the-mock-down
  [f]
  (let [container
        (mock-container)]
    (try
      (e2e/sh "docker" "stop" "-t" "1" container)
      (f)
      (finally
        (e2e/sh "docker" "start" container)
        (e2e/wait-until 60000 500 mock-calls)))))

(defn- an-unreachable-server
  [{:keys [people sessions]}]
  (e2e/section "Credentials: a server that is down")
  (let [{:keys [alice bob]}
        people

        demo
        (sessions :bob-in-demo)

        reachable?
        #(:reachable (first (filter (fn [server] (= "whoami" (:id server))) (e2e/result alice {:query-id :list-mcp-servers}))))]
    (with-the-mock-down
      (fn []
        (let [unchecked
              (e2e/put-credential bob :user "WHOAMI_TOKEN" (value "WHOAMI_TOKEN" [:user :bob]))

              seen
              (called-as demo "whoami")]
          (e2e/check "a value that can't be checked is not saved, and the answer names only the host of its check"
                     (and (= 422 (e2e/error-status unchecked))
                          (re-matches #"Not saved\. Could not reach mcp-whoami from the platform \([\w ]+\)\.( Is it only reachable through a VPN\?)?"
                                      (e2e/error-message unchecked)))
                     unchecked)
          (e2e/check "a call says the server is unreachable, nothing more"
                     (= "Who am I is unreachable right now" seen)
                     seen)
          (e2e/check "admins see it as unreachable" (false? (reachable?))))))
    (e2e/check "back up, calls go through again"
               (e2e/wait-until 60000 1000 #(= "whoami-operator" (called-as demo "whoami"))))
    (e2e/check "and admins see it as reachable" (true? (reachable?)))))

(def sections
  [["Credentials: resolution order" resolution-order]
   ["Credentials: sessions the platform owns" sessions-the-platform-owns]
   ["Credentials: who may set and remove what" who-sets-what]
   ["Credentials: tenants keep apart" tenants-keep-apart]
   ["Credentials: token, basic and a custom header" kinds-and-headers]
   ["Credentials: forks" forks]
   ["Credentials: grants and allow-lists" grants-and-allow-lists]
   ["Credentials: the check before a value is saved" check-on-save]
   ["Credentials: what is recorded and shown" what-is-recorded]
   ["Credentials: a required credential and work in progress" required-of-members]
   ["Credentials: projects" projects]
   ["Credentials: the gateway key of a session" session-keys]
   ["Credentials: a server that is down" an-unreachable-server]])

(defn- restore!
  "Leaves nothing of the pass: its values, webhooks and sessions, and the
   mock running."
  [people world]
  (e2e/sh "docker" "start" (mock-container))
  (clear-everything! people)
  (doseq [[admin id] @webhooks]
    (e2e/command admin {:cmd-id :delete-webhook :id id}))
  (reset! webhooks [])
  (close-workspaces! world))

(defn credential-hierarchy
  "The section manager-svc.e2e runs."
  [people]
  (let [world
        (atom nil)]
    (try
      (e2e/guarded "Credentials: clearing what an earlier pass left" #(clear-everything! people))
      (e2e/guarded "Credentials: what a team requires before work" #(required-before-work people))
      (reset! world (e2e/guarded "Credentials: a whoami workspace in every team" #(open-workspaces people)))
      (doseq [[title section] sections]
        (e2e/guarded title #(section @world)))
      (finally
        (e2e/guarded "Credentials: restoring" #(restore! people @world))))))

(defn -main
  [& _args]
  (when-let [people
             (e2e/log-in e2e/usernames)]
    (try
      (credential-hierarchy people)
      (finally
        (e2e/guarded "Cleanup" #(e2e/clean-up! people)))))
  (let [exit-code
        (e2e/summary!)]
    (shutdown-agents)
    (System/exit exit-code)))
