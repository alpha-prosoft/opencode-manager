(ns manager-svc.sessions.lifecycle-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.auth :as auth]
            [manager-svc.observability.api :as observability]
            [manager-svc.opencode :as opencode]
            [manager-svc.runs.api :as runs]
            [manager-svc.sessions.api :as api]
            [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.fixtures :as fixtures]
            [manager-svc.sessions.lifecycle :as lifecycle]
            [manager-svc.sessions.live :as live]
            [manager-svc.sessions.storage :as storage]
            [manager-svc.test-support :as support]))

(def session-id #uuid "11111111-1111-1111-1111-111111111111")
(def fork-id #uuid "22222222-2222-2222-2222-222222222222")

(deftest starting-stopping-deleting
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          bob
          (support/as-user ctx "bob")]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :start true})
        (lifecycle/reconcile! system)
        (testing "the requested start runs the sandbox and is recorded"
          (is
           (= [:start session-id "opencode:test"]
              (let [[action id sandbox] (last (fixtures/calls system))] [action id (:image sandbox)])))
          (is
           (nil?
            (:pending (api/get-session system session-id))))
          (is
           (= [session-id]
              (fixtures/synced system)))
          (is
           (= [session-id]
              (api/running-session-ids system))))
        (fixtures/command! bob {:cmd-id :stop-session :id session-id})
        (lifecycle/reconcile! system)
        (is
         (= [:stop session-id]
            (last (fixtures/calls system))))
        (is
         (some?
          (:stopped-at (api/get-session system session-id))))
        (fixtures/command! bob {:cmd-id :delete-session :id session-id})
        (lifecycle/reconcile! system)
        (testing "deleting removes the sandbox, its files and its route"
          (is
           (= [:delete session-id]
              (last (fixtures/calls system))))
          (is
           (nil?
            (api/get-session system session-id)))
          (is
           (false?
            (.exists (storage/session-dir (:settings system) session-id))))
          (is
           (= []
              (fixtures/synced system))))))))

(deftest starting-without-sandbox-fails
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :start true})
        (storage/remove! (:settings system) session-id)
        (lifecycle/reconcile! system)
        (is
         (= []
            (fixtures/calls system)))
        (is
         (= {:error (str "Session " session-id " has no sandbox configuration")}
            (select-keys (api/get-session system session-id) [:error :pending])))))))

(deftest preparing-forks
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          settings
          (:settings system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :public})
        (spit (io/file (storage/workspace settings session-id) "notes.txt") "hello")
        (io/make-parents (io/file (storage/session-dir settings session-id) storage/opencode-data "opencode.db"))
        (spit (io/file (storage/session-dir settings session-id) storage/opencode-data "opencode.db") "db")
        (spit (io/file (storage/session-dir settings session-id) storage/opencode-data "log") "skip")
        (fixtures/command! (support/as-user ctx "carol") {:cmd-id :fork-session :id fork-id :attrs {:source-id session-id}})
        (fixtures/command! (support/as-user ctx "carol") {:cmd-id :start-session :id fork-id})
        (let [audited
              (atom [])

              built
              (atom nil)]
          (with-redefs [observability/append-audit! (fn [_ & args] (swap! audited conj (vec args)))
                        runs/fork-sandbox (fn [_ source fork]
                                            (reset! built [(:id source) (:id fork) (:sub fork)])
                                            {:mcp-servers ["github"] :env {"OPENCODE_SERVER_PASSWORD" "fork-key"}})]
            (lifecycle/reconcile! system))
          (testing "the fork's sandbox is built for the fork from the source"
            (is
             (= [session-id fork-id "carol"]
                @built)))
          (testing "the fork is recorded in the source's audit trail"
            (is
             (= [[session-id "fork" (str "fork:" fork-id) {:forked-to (str fork-id) :by "carol"}]]
                @audited))))
        (is
         (= "hello"
            (slurp (io/file (storage/workspace settings fork-id) "notes.txt"))))
        (is
         (= "db"
            (slurp (io/file (storage/session-dir settings fork-id) storage/opencode-data "opencode.db"))))
        (is
         (false?
          (.exists (io/file (storage/session-dir settings fork-id) storage/opencode-data "log"))))
        (testing "the fork runs with its own sandbox config, not the source's"
          (is
           (= ["github"]
              (api/mcp-servers system fork-id))))
        (is
         (nil?
          (:fork-pending (api/get-session system fork-id))))
        (testing "the fork starts in the pass that prepared it"
          (is
           (= [:start fork-id]
              (vec (take 2 (last (fixtures/calls system)))))))))))

(deftest a-fork-of-a-deleted-session-says-so
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :public})
        (fixtures/command! (support/as-user ctx "carol") {:cmd-id :fork-session :id fork-id :attrs {:source-id session-id}})
        (fixtures/command! (support/as-user ctx "bob") {:cmd-id :delete-session :id session-id})
        (fixtures/command! (support/as-platform ctx) {:cmd-id :record-session-deleted :id session-id})
        (lifecycle/reconcile! system)
        (is
         (= {:error (str "The forked session " session-id " no longer exists")}
            (select-keys (api/get-session system fork-id) [:error :pending :fork-pending])))))))

(deftest an-outcome-does-not-erase-a-newer-request
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          bob
          (support/as-user ctx "bob")]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :start true})
        (testing "a stop asked for while the start runs stays pending"
          (fixtures/command! bob {:cmd-id :stop-session :id session-id})
          (fixtures/command! (support/as-platform ctx) {:cmd-id :record-session-started :id session-id})
          (is
           (= :stop
              (:pending (api/get-session system session-id)))))
        (testing "a failure of the earlier action keeps the newer request"
          (fixtures/command! (support/as-platform ctx) {:cmd-id :record-session-failed :id session-id
                                                        :attrs {:error "boom" :action :start}})
          (is
           (= {:pending :stop}
              (select-keys (api/get-session system session-id) [:pending :error]))))
        (lifecycle/reconcile! system)
        (is
         (= [:stop session-id]
            (last (fixtures/calls system))))))))

(deftest images-are-pulled-outside-the-lock
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (swap! (get-in system [:sessions/backend :state]) assoc :missing-images #{"opencode:test"})
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :start true})
        (let [pulled
              (promise)]
          (with-redefs [live/run-later! (fn [_ f] (deliver pulled f))]
            (lifecycle/reconcile! system))
          (testing "the start waits while the image is pulled"
            (is
             (= :start
                (:pending (api/get-session system session-id))))
            (is
             (= []
                (fixtures/calls system))))
          (@pulled))
        (lifecycle/reconcile! system)
        (is
         (= [[:pull "opencode:test"] [:start session-id]]
            (mapv #(vec (take 2 %)) (fixtures/calls system))))))))

(deftest a-failed-pull-fails-the-start
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (swap! (get-in system [:sessions/backend :state]) assoc
               :missing-images #{"opencode:test"}
               :pull-error "manifest unknown")
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :start true})
        (lifecycle/reconcile! system)
        (lifecycle/reconcile! system)
        (is
         (= {:error "Could not pull image opencode:test: manifest unknown"}
            (select-keys (api/get-session system session-id) [:error :pending])))))))

(deftest readiness-is-forgotten-for-sandboxes-that-died
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (fixtures/set-status! system session-id :running)
        (with-redefs [opencode/healthy? (fn [_ _] true)]
          (api/ready? system session-id))
        (is
         (true?
          (live/known-ready? system session-id)))
        (fixtures/set-status! system session-id :stopped)
        (lifecycle/reconcile! system)
        (is
         (not
          (live/known-ready? system session-id)))))))

(deftest routes-are-written-when-they-change
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          writes
          (atom 0)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (add-watch (get-in system [:sessions/routes :synced]) ::count (fn [& _] (swap! writes inc)))
        (lifecycle/reconcile! system)
        (lifecycle/reconcile! system)
        (is
         (= 1
            @writes))
        (fixtures/create-session! system ctx {:id fork-id :owner "bob"})
        (lifecycle/reconcile! system)
        (is
         (= 2
            @writes))))))

(deftest reaping
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          idle-id
          #uuid "33333333-3333-3333-3333-333333333333"]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (fixtures/create-session! system ctx {:id idle-id :owner "bob"})
        (fixtures/set-status! system session-id :running)
        (fixtures/set-status! system idle-id :running)
        (storage/write-sandbox! (:settings system) idle-id (assoc fixtures/sandbox :idle-minutes 0))
        (with-redefs [opencode/busy-count (fn [_ _] 0)]
          (testing "recently active sessions are kept"
            (is
             (= [idle-id]
                (lifecycle/reap! system))))
          (is
           (= [:stop idle-id]
              (last (fixtures/calls system)))))
        (testing "busy sessions are kept"
          (fixtures/set-status! system idle-id :running)
          (with-redefs [opencode/busy-count (fn [_ _] 1)]
            (is
             (= []
                (lifecycle/reap! system)))))))))

(deftest facade
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (let [created
              (api/create-session! system {:identity {:sub "bob" :email "bob@users.local"}
                                           :name "Review PR"
                                           :visibility :team
                                           :team-id "alpha"
                                           :activity "review"
                                           :labels {:workflow "review"}
                                           :sandbox fixtures/sandbox})

              id
              (:id created)]
          (is
           (= {:sub "bob" :name "Review PR" :visibility :team :pending :start}
              (select-keys created [:sub :name :visibility :pending])))
          (api/label! system id :conversation "ses_abcdefgh12")
          (is
           (= "ses_abcdefgh12"
              (get-in (api/get-session system id) [:labels :conversation])))
          (testing "the facade's session carries the secret key and sandbox for other domains"
            (is
             (= {:secret-key (api/secret-key system id) :llm-connection "default" :mcp-servers ["jira"]}
                (let [session (api/get-session system id)]
                  {:secret-key (:secret-key session)
                   :llm-connection (get-in session [:sandbox :llm-connection])
                   :mcp-servers (get-in session [:sandbox :mcp-servers])}))))
          (testing "keys the sandbox config derives are accepted for their own purpose only"
            (doseq [purpose ["llm" "mcp" "tools" "telemetry"]]
              (is
               (= id
                  (:id (api/session-by-key system (auth/derived-key (:settings system) purpose id) #{purpose}))))
              (is
               (nil?
                (api/session-by-key system (auth/derived-key (:settings system) purpose id) #{"session"})))))
          (testing "keys of stopped or locked sessions are refused"
            (fixtures/command! (support/as-user ctx "bob") {:cmd-id :stop-session :id id})
            (fixtures/command! (support/as-platform ctx) {:cmd-id :record-session-stopped :id id})
            (is
             (nil?
              (api/session-by-key system (auth/derived-key (:settings system) "llm" id) #{"llm"}))))
          (testing "running sessions of a team are counted"
            (fixtures/set-status! system id :running)
            (is
             (= 1
                (api/running-count system "alpha")))
            (is
             (= 0
                (api/running-count system "beta"))))
          (is
           (= (api/secret-key system id)
              (get (backend/environment (:settings system) id fixtures/sandbox) "OPENCODE_SERVER_PASSWORD")))
          (is
           (nil?
            (api/session-by-key system (str id ".forged") #{"llm"})))
          (is
           (= (str "http://opencode-" id ":4096")
              (api/session-base-url system id)))
          (is
           (= (str "http://example.test/sessions/bob/" id)
              (api/entry-url system created)))
          (testing "describe-session is the Session the UI reads, without keys"
            (let [described
                  (api/describe-session system (api/get-session system id) "bob")]
              (is
               (= {:id id :is-owner true :can-manage true :can-open true :open-blocked nil}
                  (select-keys described [:id :is-owner :can-manage :can-open :open-blocked])))
              (is
               (= (str "/sessions/bob/" id)
                  (:open-url described)))
              (is
               (not-any? #{:secret-key :sandbox :env} (keys described)))))
          (is
           (= ["jira"]
              (api/mcp-servers system id))))))))
