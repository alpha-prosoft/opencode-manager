(ns manager-svc.sessions.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.directory :as directory]
            [manager-svc.sessions.aggregate :as aggregate]
            [manager-svc.sessions.fixtures :as fixtures]
            [manager-svc.sessions.lifecycle :as lifecycle]
            [manager-svc.sessions.live :as live]
            [manager-svc.sessions.records :as records]
            [manager-svc.test-support :as support]))

(def session-id #uuid "11111111-1111-1111-1111-111111111111")
(def fork-id #uuid "22222222-2222-2222-2222-222222222222")

(defn- failure
  "Status of the domain error a command or query fails with."
  [f]
  (try
    (let [result
          (f)]
      (or (get-in result [:error :message]) result))
    (catch clojure.lang.ExceptionInfo e
      (or (:status (ex-data e))
          (some-> (ex-cause e) ex-data :status)
          (ex-message e)))))

(defn- record
  [ctx]
  (:session (mock/query (support/as-platform ctx) {:query-id :get-session-record :session-id session-id})))

(deftest creating-and-listing
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :team})
        (let [[listed]
              (mock/query (support/as-user ctx "bob") {:query-id :list-sessions})]
          (is
           (= {:id session-id
               :sub "bob"
               :name "Fix the bug"
               :visibility :team
               :status :stopped
               :url (str "http://" session-id ".example.test")
               :open-url (str "/sessions/bob/" session-id)
               :is-owner true
               :can-manage true
               :can-open true
               :open-blocked nil
               :personal false
               :locked-at nil}
              (select-keys listed [:id :sub :name :visibility :status :url :open-url :is-owner
                                   :can-manage :can-open :open-blocked :personal :locked-at]))))
        (testing "team members see it but cannot manage it; others don't see it"
          (is
           (= [{:is-owner false :can-manage false :can-open true}]
              (mapv #(select-keys % [:is-owner :can-manage :can-open])
                    (mock/query (support/as-user ctx "carol") {:query-id :list-sessions}))))
          (is
           (= []
              (mock/query (support/as-user ctx "mallory") {:query-id :list-sessions}))))
        (testing "the secret key never reaches the UI"
          (is
           (not-any? #{:secret-key :env}
                     (keys (mock/query (support/as-user ctx "bob") {:query-id :get-session :session-id session-id})))))))))

(deftest session-detail
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (fixtures/set-status! system session-id :running)
        (let [detail
              (mock/query (support/as-user ctx "bob") {:query-id :get-session :session-id session-id})]
          (is
           (= {:status :running :audit-events 7 :calls 3 :prompt-tokens 0}
              (merge (select-keys detail [:status :audit-events])
                     (select-keys (:usage detail) [:calls :prompt-tokens])))))
        (is
         (= 404
            (failure #(mock/query (support/as-user ctx "carol") {:query-id :get-session :session-id session-id}))))
        (is
         (= [{:id "ses_abcdefgh12" :open-path "/L3Nlc3Npb24vd29ya3NwYWNl/session/ses_abcdefgh12"}]
            (mapv #(select-keys % [:id :open-path])
                  (mock/query (support/as-user ctx "bob") {:query-id :list-conversations :session-id session-id}))))))))

(deftest updating
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :team})
        (fixtures/command! (support/as-user ctx "alice")
                           {:cmd-id :update-session :id session-id :attrs {:name "Renamed" :visibility :public}})
        (is
         (= {:name "Renamed" :visibility :public}
            (select-keys (record ctx) [:name :visibility])))
        (is
         (= 403
            (failure #(fixtures/command! (support/as-user ctx "carol")
                                         {:cmd-id :update-session :id session-id :attrs {:name "Mine"}}))))
        (is
         (= 422
            (failure #(fixtures/command! (support/as-user ctx "bob")
                                         {:cmd-id :update-session :id session-id :attrs {:name ""}}))))))))

(deftest lifecycle-requests
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          bob
          (support/as-user ctx "bob")]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :public})
        (fixtures/command! bob {:cmd-id :start-session :id session-id})
        (is
         (= :start
            (:pending (record ctx))))
        (testing "a pending start shows as starting"
          (is
           (= :starting
              (:status (first (mock/query bob {:query-id :list-sessions}))))))
        (testing "people who may not manage it cannot stop or delete it"
          (is
           (= 403
              (failure #(fixtures/command! (support/as-user ctx "carol") {:cmd-id :stop-session :id session-id}))))
          (is
           (= 403
              (failure #(fixtures/command! (support/as-user ctx "carol") {:cmd-id :delete-session :id session-id})))))
        (fixtures/command! bob {:cmd-id :stop-session :id session-id})
        (is
         (= :stop
            (:pending (record ctx))))
        (fixtures/command! bob {:cmd-id :delete-session :id session-id})
        (is
         (= :delete
            (:pending (record ctx))))))))

(deftest blocked-starts
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :private})
        (testing "a team admin sees a private session but may not work in it"
          (is
           (= 409
              (failure #(fixtures/command! (support/as-user ctx "alice") {:cmd-id :start-session :id session-id})))))
        (testing "someone who can't see it gets not found"
          (is
           (= 404
              (failure #(fixtures/command! (support/as-user ctx "mallory") {:cmd-id :start-session :id session-id})))))))))

(deftest locking-sessions
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          bob
          (support/as-user ctx "bob")]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :public})
        (is
         (= 403
            (failure #(fixtures/command! (support/as-user ctx "alice") {:cmd-id :lock-session :id session-id}))))
        (fixtures/command! bob {:cmd-id :lock-session :id session-id})
        (is
         (= {:locked-by "bob" :pending :stop}
            (select-keys (record ctx) [:locked-by :pending])))
        (is
         (= {:open-blocked :locked :can-open false}
            (select-keys (first (mock/query bob {:query-id :list-sessions})) [:open-blocked :can-open])))
        (is
         (= 409
            (failure #(fixtures/command! bob {:cmd-id :lock-session :id session-id}))))
        (is
         (= 409
            (failure #(fixtures/command! bob {:cmd-id :start-session :id session-id}))))))))

(deftest forking
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :public
                                              :labels {:workflow "fix" :conversation "ses_abcdefgh12" :activity "fix"}})
        (fixtures/command! (support/as-user ctx "carol")
                           {:cmd-id :fork-session :id fork-id :attrs {:source-id session-id}})
        (is
         (= {:sub "carol"
             :name "Fix the bug (fork)"
             :visibility :private
             :team-id "alpha"
             :activity "session"
             :forked-from session-id
             :fork-pending true
             :pending :start
             :labels {:workflow "fix" :conversation "ses_abcdefgh12"}}
            (select-keys (:session (mock/query (support/as-platform ctx) {:query-id :get-session-record :session-id fork-id}))
                         [:sub :name :visibility :team-id :activity :forked-from :fork-pending :pending :labels])))
        (testing "only team members can fork"
          (is
           (= 403
              (failure #(fixtures/command! (support/as-user ctx "mallory")
                                           {:cmd-id :fork-session :id (random-uuid) :attrs {:source-id session-id}})))))))))

(deftest forking-needs-a-workflow
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :public :labels {}})
        (is
         (= 422
            (failure #(fixtures/command! (support/as-user ctx "carol")
                                         {:cmd-id :fork-session :id fork-id :attrs {:source-id session-id}}))))))))

(deftest stopping-idle-sessions
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (with-redefs [live/done? (fn [_ session] (= session-id (:id session)))]
          (fixtures/set-status! system session-id :running)
          (is
           (= 403
              (failure #(fixtures/command! (support/as-user ctx "bob") {:cmd-id :stop-idle-sessions}))))
          (testing "the administrator's request is only recorded"
            (fixtures/command! (support/as-user ctx "root") {:cmd-id :stop-idle-sessions})
            (is
             (= "root"
                (:requested-by (mock/get-by-id ctx aggregate/reaper-id))))
            (is
             (nil?
              (:pending (record ctx)))))
          (testing "the reaper carries it out once"
            (is
             (= [session-id]
                (lifecycle/reap-requested! system)))
            (is
             (nil?
              (lifecycle/reap-requested! system)))
            (is
             (= [:stop session-id]
                (last (fixtures/calls system))))))))))

(deftest locking-keeps-a-pending-delete
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          bob
          (support/as-user ctx "bob")]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (fixtures/command! bob {:cmd-id :delete-session :id session-id})
        (fixtures/command! bob {:cmd-id :lock-session :id session-id})
        (is
         (= :delete
            (:pending (record ctx))))))))

(deftest touches-change-no-directory-row
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (fixtures/command! (support/as-user ctx "bob") {:cmd-id :stop-session :id session-id})
        (fixtures/command! (support/as-platform ctx) {:cmd-id :record-session-stopped :id session-id})
        (let [row-before
              (first (directory/rows ctx "user:bob" :sessions))]
          (fixtures/command! (support/as-platform ctx) {:cmd-id :touch-session :id session-id})
          (is
           (= row-before
              (first (directory/rows ctx "user:bob" :sessions))))
          (testing "a late touch keeps the session stopped"
            (is
             (some?
              (:stopped-at (record ctx))))))))))

(deftest activity-is-held-in-memory-and-persisted-rarely
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          touches
          (atom 0)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (let [command!
              fixtures/command!]
          (with-redefs [fixtures/command! (fn [ctx cmd]
                                            (when (= :touch-session (:cmd-id cmd))
                                              (swap! touches inc))
                                            (command! ctx cmd))]
            (dotimes [_ 5]
              (records/touch! system session-id))))
        (is
         (= 1
            @touches))
        (testing "the list shows the activity recorded in memory"
          (let [later
                (+ (System/currentTimeMillis) 60000)]
            (swap! (:sessions/state system) assoc-in [:activity session-id] later)
            (is
             (= (str (java.time.Instant/ofEpochMilli later))
                (:last-activity-at (first (mock/query (support/as-user ctx "bob") {:query-id :list-sessions})))))))))))

(deftest listing-reads-the-owner-and-team-directories
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          [own public hidden team-shared]
          (repeatedly 4 random-uuid)

          listed
          (fn [username]
            (set (map :id (mock/query (support/as-user ctx username) {:query-id :list-sessions}))))]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id own :owner "bob"})
        (fixtures/create-session! system ctx {:id public :owner "dave" :team-id "beta" :visibility :public})
        (fixtures/create-session! system ctx {:id hidden :owner "erin" :team-id "beta"})
        (fixtures/create-session! system ctx {:id team-shared :owner "carol" :visibility :team})
        (is
         (= #{own public team-shared}
            (listed "bob")))
        (is
         (= #{public}
            (listed "mallory")))
        (is
         (= #{own public hidden team-shared}
            (listed "root")))
        (is
         (= #{own team-shared}
            (set (map :id (directory/rows ctx "team:alpha" :sessions)))))
        (is
         (= #{own}
            (set (map :id (directory/rows ctx "user:bob" :sessions)))))
        (testing "a rename shows in the list"
          (fixtures/command! (support/as-user ctx "bob") {:cmd-id :update-session :id own :attrs {:name "Renamed"}})
          (is
           (= "Renamed"
              (:name (first (filter #(= own (:id %)) (mock/query (support/as-user ctx "bob") {:query-id :list-sessions})))))))
        (testing "a fork is listed for the person who forked"
          (fixtures/command! (support/as-user ctx "bob") {:cmd-id :fork-session :id fork-id :attrs {:source-id team-shared}})
          (is
           (contains? (listed "bob") fork-id))
          (is
           (not (contains? (listed "carol") fork-id))))
        (testing "a deleted session leaves every directory"
          (fixtures/command! (support/as-user ctx "bob") {:cmd-id :delete-session :id own})
          (fixtures/command! (support/as-platform ctx) {:cmd-id :record-session-deleted :id own})
          (is
           (= #{public team-shared fork-id}
              (listed "bob")))
          (is
           (= #{team-shared fork-id}
              (set (map :id (directory/rows ctx "team:alpha" :sessions)))))
          (is
           (= #{fork-id}
              (set (map :id (directory/rows ctx "user:bob" :sessions)))))
          (is
           (= #{public hidden team-shared fork-id}
              (set (map :id (mock/query (support/as-platform ctx) {:query-id :list-session-rows}))))))))))

(deftest counting-running-sessions-of-a-team
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          other-id
          #uuid "33333333-3333-3333-3333-333333333333"]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (fixtures/create-session! system ctx {:id fork-id :owner "bob"})
        (fixtures/create-session! system ctx {:id other-id :owner "dave" :team-id "beta"})
        (fixtures/set-status! system session-id :running)
        (fixtures/set-status! system other-id :running)
        (is
         (= {:count 1}
            (mock/query (support/as-platform ctx) {:query-id :count-running-sessions :team-id "alpha"})))))))
