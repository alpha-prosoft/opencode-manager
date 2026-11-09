(ns manager-svc.sessions.authorization-test
  "Who may run each session command and query, as the handlers decide it."
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.sessions.fixtures :as fixtures]
            [manager-svc.test-support :as support]))

(def session-id #uuid "33333333-3333-3333-3333-333333333333")

(defn- outcome
  "The status a command or query fails with, or :ok."
  [f]
  (try
    (let [result
          (f)]
      (if-let [failure (and (map? result) (or (:error result) (:exception result)))]
        (or (:status failure) (:message failure) failure)
        :ok))
    (catch clojure.lang.ExceptionInfo e
      (or (:status (ex-data e))
          (some-> (ex-cause e) ex-data :status)
          (ex-message e)))))

(defn- command
  [ctx username cmd]
  (outcome #(fixtures/command! (support/as-user ctx username) cmd)))

(defn- query
  [ctx username q]
  (outcome #(mock/query (support/as-user ctx username) q)))

(defmacro ^:private with-session
  "Runs body with ctx bound to a fresh mock DAL holding one session."
  [[ctx session] & body]
  `(mock/with-mock-dal
     (let [system#
           (fixtures/system)

           ~ctx
           (fixtures/ctx system#)]
       (fixtures/with-domains ~ctx
         (fixtures/create-session! system# ~ctx (merge {:id session-id} ~session))
         ~@body))))

(deftest people-who-cannot-see-a-session-are-told-it-does-not-exist
  (with-session [ctx {:owner "bob" :visibility :private}]
    (is
     (= {:get-session 404 :list-conversations 404}
        {:get-session (query ctx "carol" {:query-id :get-session :session-id session-id})
         :list-conversations (query ctx "carol" {:query-id :list-conversations :session-id session-id})}))
    (is
     (= {:update-session 404 :start-session 404 :stop-session 404 :delete-session 404 :lock-session 404}
        {:update-session (command ctx "carol" {:cmd-id :update-session :id session-id :attrs {:name "Mine"}})
         :start-session (command ctx "carol" {:cmd-id :start-session :id session-id})
         :stop-session (command ctx "carol" {:cmd-id :stop-session :id session-id})
         :delete-session (command ctx "carol" {:cmd-id :delete-session :id session-id})
         :lock-session (command ctx "carol" {:cmd-id :lock-session :id session-id})}))
    (testing "a team member cannot fork a private session they cannot see"
      (is
       (= 404
          (command ctx "carol" {:cmd-id :fork-session :id (random-uuid) :attrs {:source-id session-id}}))))))

(deftest owners-team-admins-and-platform-admins-manage-a-session
  (doseq [manager ["bob" "alice" "root"]]
    (with-session [ctx {:owner "bob" :visibility :team}]
      (is
       (= [manager {:update-session :ok :stop-session :ok :delete-session :ok}]
          [manager {:update-session (command ctx manager {:cmd-id :update-session :id session-id :attrs {:visibility :public}})
                    :stop-session (command ctx manager {:cmd-id :stop-session :id session-id})
                    :delete-session (command ctx manager {:cmd-id :delete-session :id session-id})}])))))

(deftest team-members-see-team-sessions-but-do-not-manage-them
  (with-session [ctx {:owner "bob" :visibility :team}]
    (is
     (= {:get-session :ok :start-session :ok :update-session 403 :stop-session 403 :delete-session 403 :lock-session 403}
        {:get-session (query ctx "carol" {:query-id :get-session :session-id session-id})
         :start-session (command ctx "carol" {:cmd-id :start-session :id session-id})
         :update-session (command ctx "carol" {:cmd-id :update-session :id session-id :attrs {:visibility :public}})
         :stop-session (command ctx "carol" {:cmd-id :stop-session :id session-id})
         :delete-session (command ctx "carol" {:cmd-id :delete-session :id session-id})
         :lock-session (command ctx "carol" {:cmd-id :lock-session :id session-id})}))))

(deftest only-the-owner-works-in-a-session-with-personal-credentials
  (binding [fixtures/*personal* #{"bob"}]
    (with-session [ctx {:owner "bob" :visibility :team}]
      (is
       (= {"carol" 409 "alice" 409 "bob" :ok}
          (into {} (for [username ["carol" "alice" "bob"]]
                     [username (command ctx username {:cmd-id :start-session :id session-id})]))))
      (testing "others continue in a fork with their own credentials"
        (is
         (= :ok
            (command ctx "carol" {:cmd-id :fork-session :id (random-uuid) :attrs {:source-id session-id}})))))))

(deftest sessions-of-platform-runs-are-worked-in-by-their-team-only
  (with-session [ctx {:owner "platform" :visibility :public}]
    (is
     (= {"mallory" 409 "carol" :ok "root" :ok}
        (into {} (for [username ["mallory" "carol" "root"]]
                   [username (command ctx username {:cmd-id :start-session :id session-id})]))))
    (testing "people outside the team still see it"
      (is
       (= {:status :ok :open-blocked :team-only}
          {:status (query ctx "mallory" {:query-id :get-session :session-id session-id})
           :open-blocked (:open-blocked (mock/query (support/as-user ctx "mallory")
                                                    {:query-id :get-session :session-id session-id}))})))
    (testing "and cannot fork it, since forks belong to the team"
      (is
       (= 403
          (command ctx "mallory" {:cmd-id :fork-session :id (random-uuid) :attrs {:source-id session-id}}))))))

(deftest a-locked-session-can-still-be-forked
  (with-session [ctx {:owner "bob" :visibility :team}]
    (command ctx "bob" {:cmd-id :lock-session :id session-id})
    (is
     (= {:start-session 409 :fork-session :ok}
        {:start-session (command ctx "carol" {:cmd-id :start-session :id session-id})
         :fork-session (command ctx "carol" {:cmd-id :fork-session :id (random-uuid) :attrs {:source-id session-id}})}))))

(deftest the-platform-may-start-a-session-but-not-a-locked-one
  (with-session [ctx {:owner "bob" :visibility :private}]
    (let [platform
          (support/as-platform ctx)]
      (is
       (= :ok
          (outcome #(fixtures/command! platform {:cmd-id :start-session :id session-id}))))
      (command ctx "bob" {:cmd-id :lock-session :id session-id})
      (is
       (= 409
          (outcome #(fixtures/command! platform {:cmd-id :start-session :id session-id})))))))
