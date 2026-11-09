(ns manager-svc.runs.fixtures
  "A fake platform (team, workflows, agents, harnesses), fake sessions and an
   edd backend on the mock DAL for runs tests."
  (:require [clojure.string :as string]
            [edd.core :as edd]
            [edd.security :as security]
            [manager-svc.auth :as auth]
            [manager-svc.client :as client]
            [edd.test.fixture.dal :as mock]
            [malli.core :as m]
            [malli.error :as me]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.observability.api :as observability]
            [manager-svc.platform.api :as platform]
            [manager-svc.roles :as roles]
            [manager-svc.runs.core :as core]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.test-support :as support]))

(def tenant
  "The tenant whose team alpha the fake platform knows."
  "acme")

(def team
  {:id "alpha"
   :tenant tenant
   :name "Alpha"
   :budget {}
   :notifications {}})

(def personas
  "What each test person's claims say: alice and bob are members of acme's
   alpha, carol its admin, root acme's tenant admin, mallory of acme in no
   team, gina a member of a team alpha of tenant globex."
  {"alice" {:teams {"alpha" :member}}
   "bob" {:teams {"alpha" :member}}
   "carol" {:teams {"alpha" :admin}}
   "root" {:tenant-admin true}
   "mallory" {}
   "gina" {:tenant "globex" :teams {"alpha" :member}}})

(defn person
  [username]
  (get personas username {}))

(def workflows
  {"review-prs" {:name "Review PRs"
                 :project "app"
                 :activity "review"
                 :agent "reviewer"
                 :harness "default"
                 :trigger {:type "pull_request"
                           :provider "github"
                           :repository "acme/app"
                           :credentials "gh-token"
                           :poll-minutes 15}
                 :options {:post-mode "auto" :max-comments 5}}
   "implement" {:name "Implement"
                :activity "implement"
                :agent "coder"
                :harness "default"
                :options {:repository {:url "https://github.com/acme/app.git"
                                       :branch "main"
                                       :credentials "gh-token"}
                          :pull-request {:provider "github" :repository "acme/app"}
                          :ci {:mcp-server "jenkins" :job "app/PR-{pr}" :on-failure "analyse"}}}
   "analyse" {:name "Analyse"
              :activity "analysis"
              :agent "analyst"
              :harness "default"
              :trigger {:type "schedule" :every-minutes 60 :inputs {:brief "Weekly architecture review"}}}
   "workspace" {:name "Workspace"
                :project "app"
                :activity "session"
                :agent "coder"
                :harness "default"}})

(def agents
  {"reviewer" {:description "Reviews" :prompt "You review code."}
   "coder" {:description "Codes" :prompt "You write code."}
   "analyst" {:description "Analyses" :prompt "You analyse."}})

(def harnesses
  {"default" {:llm {:connection "main" :models ["model-a" "model-b"]}
              :permissions {:bash "allow"}
              :mcp []}})

(def activities
  {"session" {:id "session" :interactive true :result-tool nil
              :inputs [:map {:closed true}
                       [:title {:optional true} :string]
                       [:prompt {:optional true} :string]
                       [:branch {:optional true} :string]]}
   "review" {:id "review" :interactive false :result-tool "submit_review"
             :inputs [:map {:closed true} [:pull-request [:int {:min 1}]]]}
   "implement" {:id "implement" :interactive false :result-tool "submit_implementation"
                :inputs [:map {:closed true}
                         [:task [:string {:min 10}]]
                         [:pull-request {:optional true} [:int {:min 1}]]
                         [:branch {:optional true} :string]]}
   "analysis" {:id "analysis" :interactive false :result-tool "submit_document"
               :inputs [:map {:closed true} [:brief [:string {:min 10}]]]}})

(defn- validate-inputs
  [activity-id inputs]
  (let [schema
        (get-in activities [activity-id :inputs])

        value
        (into {} (map (fn [[k v]] [(keyword (string/replace (name k) "_" "-")) v])) inputs)]
    (if (m/validate schema value)
      {:value value :errors []}
      {:value nil
       :errors (vec (for [[k messages] (me/humanize (m/explain schema value))
                          message messages]
                      (str (name k) ": " message)))})))

(defn- definition
  [tenant-name _team-id kind id]
  (when (= tenant tenant-name)
    (when-let [spec (get (case kind :workflows workflows :agents agents :harnesses harnesses) id)]
      {:kind kind :id id :spec spec :valid true :errors []})))

(defn sessions-store
  []
  (atom {}))

(defonce ^:private edd-backend
  (atom nil))

(defn- run-commands
  [commands]
  (doseq [command commands]
    ((:command @edd-backend) command)))

(defn fake-facades
  "Facade replacements; sessions live in the given atom {session-id session}."
  [store]
  {#'platform/teams-of (fn [_ caller]
                         (set (filter #(roles/member? caller tenant %) ["alpha"])))
   #'platform/tenants (fn [_] [{:name tenant} {:name "globex"}])
   #'platform/teams (fn [_ tenant-name] (if (= tenant tenant-name) [team] []))
   #'platform/team (fn [_ tenant-name team-id] (when (and (= tenant tenant-name) (= "alpha" team-id)) team))
   #'platform/definition (fn [_ tenant-name team-id kind id] (definition tenant-name team-id kind id))
   #'platform/workflows (fn [_ tenant-name team-id]
                          (keep #(definition tenant-name team-id :workflows %) (keys workflows)))
   #'platform/read-team-file (fn [_ _tenant _team relative] (str "contents of " relative))
   #'platform/read-skills (fn [_ _tenant _team _skills] {})
   #'platform/secret-host-allowed? (fn [_ _tenant _secret _url] true)
   #'platform/mcp-server (fn [_ _tenant server-id]
                           (when (= "jenkins" server-id) {:id "jenkins" :name "Jenkins" :url "http://jenkins/mcp"}))
   #'platform/activity (fn [_ activity-id] (dissoc (get activities activity-id) :inputs))
   #'platform/validate-inputs (fn [_ activity-id inputs _options] (validate-inputs activity-id inputs))
   #'credentials/secret (fn [_ _tenant secret-name] (str "secret-" secret-name))
   #'credentials/resolve-credential (fn [_ _scope credential-id]
                                      {:value {:secret (str "token-" credential-id)} :source :tenant})
   #'observability/usage-for-run (fn [_ _run-id] {:calls 3 :prompt-tokens 10 :completion-tokens 5
                                                  :cached-tokens 0 :errors 0 :avg-latency-ms 12})
   #'client/query (fn [_ q] ((:query @edd-backend) q))
   #'client/command (fn [_ & commands] (run-commands commands))
   #'client/command-no-settle (fn [_ & commands] (run-commands commands))
   #'sessions/get-session (fn [_ session-id] (get @store session-id))
   #'sessions/can-view? (fn [_ session caller] (or (= (:id caller) (:sub session)) (not= :private (:visibility session))))
   #'sessions/describe-session (fn [_ session caller]
                                 (assoc (select-keys session [:id :sub :visibility])
                                        :is-owner (= (:id caller) (:sub session))))
   #'sessions/create-session! (fn [_ opts]
                                (let [session (assoc (dissoc opts :sandbox :identity)
                                                     :sub (get-in opts [:identity :sub])
                                                     :secret-key "pw"
                                                     :sandbox (:sandbox opts)
                                                     :started (get opts :start? true)
                                                     :ready true)]
                                  (swap! store assoc (:id opts) session)
                                  session))
   #'sessions/secret-key (fn [_ _session-id] "pw")
   #'sessions/session-by-key (fn [system key purposes]
                               (some->> (auth/derived-key-owner (:settings system) key purposes) (get @store)))
   #'sessions/hand-over! (fn [_ session-id] (swap! store assoc-in [session-id :handed-over] true))
   #'sessions/start-session! (fn [_ session-id] (swap! store assoc-in [session-id :started] true))
   #'sessions/ready? (fn [_ session-id] (boolean (get-in @store [session-id :ready])))
   #'sessions/running-session-ids (fn [& _] (keep (fn [[id s]] (when-not (:stopped s) id)) @store))
   #'sessions/running-count (fn [_ tenant-name team-id]
                              (count (filter (fn [s] (and (= tenant-name (:tenant s))
                                                          (= team-id (:team-id s))
                                                          (not (:stopped s))))
                                             (vals @store))))
   #'sessions/stop-session! (fn [_ session-id] (swap! store assoc-in [session-id :stopped] true))
   #'sessions/label! (fn [_ session-id k v] (swap! store assoc-in [session-id :labels k] v))
   #'sessions/session-base-url (fn [_ session-id] (str "http://session-" session-id ":4096"))
   #'sessions/entry-url (fn [_ session] (str "https://" (:id session) ".localhost"))
   #'sessions/workspace-dir (fn [_ session-id] (str "target/test-data/workspaces/" session-id))})

(defmacro with-fakes
  "Runs body with the facades replaced; binds store to the sessions atom."
  [[store-sym] & body]
  `(let [~store-sym (sessions-store)]
     (with-redefs-fn (fake-facades ~store-sym)
       (fn [] ~@body))))

(defn- raise-on-error
  "Throws as manager-svc.client does for a failed command."
  [response]
  (when-let [{:keys [status message]}
             (client/failure response)]
    (throw (ex-info message {:status status :message message})))
  response)

(defn- with-sessions-count
  "The sessions domain's :count-running-sessions (the dependency of
   :start-run) over the faked sessions facade."
  [ctx]
  (edd/reg-query ctx :count-running-sessions
                 (fn [query-ctx {:keys [tenant team-id]}]
                   {:count (sessions/running-count (:manager/system query-ctx) tenant team-id)})
                 :auth {:role [:non-interactive]}))

(def ^:dynamic *missing-credentials*
  "{[username team-id] [{:id :name}]}: required credentials people lack."
  {})

(defn- with-required-credentials
  "The credentials domain's :get-missing-required-credentials (a dependency
   of the commands that start work in a team) over *missing-credentials*."
  [ctx]
  (edd/reg-query ctx :get-missing-required-credentials
                 (fn [_ {:keys [team-id user]}]
                   {:team-id team-id
                    :team-name (:name team)
                    :missing (get *missing-credentials* [user team-id] [])})
                 :auth {:role [:non-interactive]}))

(defn env
  "{:ctx :system} on the mock DAL. Inside with-fakes, client/query and
   client/command run as the platform on that ctx; the system's :test/edd
   holds the same functions for tests. extra is merged into the system."
  ([]
   (env {}))
  ([extra]
   (let [ctx-ref
         (atom nil)

         backend
         {:query (fn [q] (mock/query (support/as-platform @ctx-ref) q))
          :command (fn [cmd] (raise-on-error (support/apply! (support/as-platform @ctx-ref) cmd)))}

         system
         (merge {:settings support/settings :test/edd backend} extra)

         ctx
         (security/register (with-required-credentials (with-sessions-count (core/register (support/ctx system)))))]
     (reset! ctx-ref ctx)
     (reset! edd-backend backend)
     {:ctx ctx
      :system (:manager/system ctx)})))

(defn as
  "ctx as the test person username (see personas)."
  [ctx username]
  (support/as-user ctx username (person username)))

(defn command!
  [ctx user cmd]
  (raise-on-error (support/apply! (as ctx user) cmd)))

(defn query
  [ctx user q]
  (mock/query (as ctx user) q))
