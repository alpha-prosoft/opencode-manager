(ns manager-svc.credentials.authorization-test
  "Whose credential values a person may set, delete and see."
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.edge :as edge]
            [manager-svc.credentials.fixtures :as fixtures :refer [with-platform]]
            [manager-svc.http :as http]
            [manager-svc.test-support :as support]))

(defn- as
  [username]
  (support/as-user (fixtures/ctx) username))

(defn- execute
  [ctx cmd-id attrs]
  (try
    (let [sent
          (if (= :set-my-credential cmd-id)
            (:attrs (edge/sealed-command fixtures/settings :user (:credential-id attrs) (auth/user-id ctx)
                                         (select-keys attrs [:secret :username])))
            attrs)

          response
          (support/apply! ctx {:cmd-id cmd-id :id (random-uuid) :attrs sent})]
      (if (:error response)
        (:error response)
        :ok))
    (catch clojure.lang.ExceptionInfo e
      (:status (ex-data e)))))

(defn- states
  [username]
  (into {} (map (juxt :id :state)) (:credentials (mock/query (as username) {:query-id :get-my-credentials}))))

(defn- pending
  [username]
  (:pending (mock/query (as username) {:query-id :get-my-credentials})))

(deftest a-person-sees-and-deletes-only-their-own-values
  (mock/with-mock-dal
    (with-platform {:teams {"alice" #{"alpha"} "bob" #{"alpha"} "carol" #{"alpha"}}}
      (with-redefs [http/request! (fn [_] {:status 200 :headers {} :body ""})]
        (execute (as "alice") :set-my-credential {:credential-id "github" :secret "alice-token"})
        (execute (as "bob") :set-my-credential {:credential-id "github" :secret "bob-token"}))
      (is
       (= {"alice" :own "bob" :own "carol" :missing}
          (into {} (for [username ["alice" "bob" "carol"]]
                     [username (get (states username) "github")]))))
      (execute (as "bob") :delete-my-credential {:credential-id "github"})
      (testing "bob's delete removes his value only"
        (is
         (= {"alice" :own "bob" :missing}
            (into {} (for [username ["alice" "bob"]]
                       [username (get (states username) "github")]))))))))

(deftest unknown-credentials-cannot-be-deleted
  (mock/with-mock-dal
    (with-platform {}
      (is
       (= 404
          (execute (as "alice") :delete-my-credential {:credential-id "nope"}))))))

(deftest seen-prompts-belong-to-the-person-who-saw-them
  (mock/with-mock-dal
    (with-platform {:teams {"alice" #{"alpha"} "bob" #{"alpha"}}}
      (execute (as "alice") :mark-credentials-seen {:credentials ["github"] :username "bob"})
      (is
       (= {"alice" ["jira"] "bob" ["github" "jira"]}
          {"alice" (pending "alice") "bob" (pending "bob")}))
      (testing "the platform marks them for the person it names"
        (support/apply! (support/as-platform (fixtures/ctx))
                        {:cmd-id :mark-credentials-seen :id (random-uuid)
                         :attrs {:username "bob" :credentials ["github"]}})
        (is
         (= ["jira"]
            (pending "bob")))))))
