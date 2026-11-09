(ns manager-svc.sessions.fixtures
  "Fake backend and route provider, and a system wired to the mock DAL."
  (:require [edd.core :as edd]
            [edd.security :as security]
            [edd.test.fixture.dal :as mock]
            [manager-svc.client :as client]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.observability.api :as observability]
            [manager-svc.platform.api :as platform]
            [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.core :as core]
            [manager-svc.sessions.live :as live]
            [manager-svc.sessions.routing :as routing]
            [manager-svc.sessions.storage :as storage]
            [manager-svc.test-support :as support]
            [manager-svc.workers :as workers])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defrecord FakeBackend [state]
  backend/Backend
  (prepare! [_]
    (swap! state update :calls conj [:prepare]))
  (image-ready? [_ image]
    (not (contains? (:missing-images @state) image)))
  (pull-image! [_ image]
    (when-let [message (:pull-error @state)]
      (throw (ex-info message {})))
    (swap! state #(-> %
                      (update :calls conj [:pull image])
                      (update :missing-images disj image))))
  (start! [_ session sandbox]
    (swap! state #(-> %
                      (update :calls conj [:start (:id session) sandbox])
                      (assoc-in [:statuses (:id session)] :running))))
  (stop! [_ session-id]
    (swap! state #(-> %
                      (update :calls conj [:stop session-id])
                      (assoc-in [:statuses session-id] :stopped))))
  (delete! [_ session-id]
    (swap! state #(-> %
                      (update :calls conj [:delete session-id])
                      (update :statuses dissoc session-id))))
  (statuses [_]
    (:statuses @state))
  (service-url [_ session-id]
    (str "http://opencode-" session-id ":4096")))

(defrecord FakeRoutes [synced]
  routing/RouteProvider
  (sync-routes! [_ sessions]
    (reset! synced (mapv :id sessions))))

(defn temp-dir
  []
  (str (Files/createTempDirectory "sessions-test" (make-array FileAttribute 0))))

(defn system
  []
  {:settings (assoc support/settings
                    :domain "example.test"
                    :shared-mount-path (temp-dir))
   :sessions/backend (->FakeBackend (atom {:statuses {} :calls [] :missing-images #{}}))
   :sessions/routes (->FakeRoutes (atom nil))
   :sessions/state (live/new-state)
   :sessions/lock (Object.)})

(defn calls
  [system]
  (:calls @(get-in system [:sessions/backend :state])))

(defn set-status!
  [system session-id status]
  (swap! (get-in system [:sessions/backend :state]) assoc-in [:statuses session-id] status))

(defn synced
  [system]
  @(get-in system [:sessions/routes :synced]))

(defn- without-required-credentials
  "The credentials domain's :get-missing-required-credentials (a dependency
   of :fork-session): nobody lacks one unless a test registers its own."
  [ctx]
  (edd/reg-query ctx :get-missing-required-credentials
                 (fn [_ {:keys [team-id]}]
                   {:team-id team-id :missing []})
                 :auth {:role [:non-interactive]}))

(defn ctx
  [system]
  (security/register (without-required-credentials (core/register (support/ctx system)))))

(def sandbox
  {:image "opencode:test"
   :network "none"
   :cpus 1
   :memory-mb 512
   :pids 100
   :llm-connection "default"
   :llm-models ["gpt"]
   :team-id "alpha"
   :idle-minutes 15
   :mcp-servers ["jira"]
   :env {"OPENCODE_SERVER_PASSWORD" "secret"}
   :files {"opencode/opencode.json" "{}"}})

(def people
  "What the tokens of test people carry. Team alpha of acme: alice admin,
   bob and carol members; mallory is in acme without a team; root is acme's
   tenant admin. gina and hank are of globex, which also has a team alpha."
  {"alice" {:teams {"alpha" :admin}}
   "bob" {:teams {"alpha" :member}}
   "carol" {:teams {"alpha" :member}}
   "mallory" {}
   "root" {:tenant-admin true}
   "gina" {:tenant "globex" :tenant-admin true :teams {"alpha" :admin}}
   "hank" {:tenant "globex" :teams {"alpha" :member}}})

(defn person
  "The caller map of a test person."
  [username]
  (support/person username (get people username {})))

(defn as
  "ctx as a test person."
  [ctx username]
  (support/as-user ctx username (get people username {})))

(def ^:dynamic *personal* #{})

(def ^:dynamic *team* nil)

(defn command!
  "Runs a command as the given ctx, applies its events to the view store and
   runs its effects."
  [ctx cmd]
  (support/apply! ctx cmd))

(defmacro with-domains
  "Other domains' facades replaced (the team's entry is *team*, personal
   credentials *personal*); edd calls of the edge, workers and facade run
   against the mock DAL as the platform."
  [ctx & body]
  `(let [platform-ctx#
         (support/as-platform ~ctx)]
     (with-redefs [platform/team (fn [_# _tenant# _team-id#] *team*)
                   credentials/uses-personal? (fn [_# scope# _servers#] (contains? *personal* (:user scope#)))
                   observability/usage-for-session (fn [_# _id#] {:calls 3})
                   observability/audit-count (fn [_# _id#] 7)
                   observability/audit-conversations (fn [_# _id#] [{:id "ses_abcdefgh12" :title "Fix" :messages 2 :started nil :last nil}])
                   client/query (fn [_# query#] (mock/query platform-ctx# query#))
                   client/command (fn [_# & commands#] (doseq [cmd# commands#] (command! platform-ctx# cmd#)) {:success true})
                   client/command-no-settle (fn [_# & commands#] (doseq [cmd# commands#] (command! platform-ctx# cmd#)) {:success true})
                   workers/kick! (fn [& _#] nil)]
       ~@body)))

(defn create-session!
  "A session as the runs domain creates it, with a sandbox config."
  [system ctx {:keys [id owner visibility labels tenant team-id project start]
               :or {visibility :private tenant "acme" team-id "alpha" labels {:workflow "fix"}}}]
  (storage/prepare! (:settings system) id)
  (storage/write-sandbox! (:settings system) id sandbox)
  (command! (support/as-platform ctx)
            {:cmd-id :create-session
             :id id
             :attrs {:sub owner
                     :email (str owner "@users.local")
                     :name "Fix the bug"
                     :visibility visibility
                     :labels labels
                     :tenant tenant
                     :team-id team-id
                     :project project
                     :activity "fix"
                     :start (boolean start)}}))
