(ns manager-svc.credentials.authorization-test
  "Whose credential values a person may set, delete and see; nobody reaches
   another tenant's."
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.credentials.fixtures :as fixtures :refer [as with-platform]]
            [manager-svc.credentials.mcp :as mcp]
            [manager-svc.credentials.spec :as spec]
            [manager-svc.credentials.vault :as vault]
            [manager-svc.http :as http]
            [manager-svc.test-support :as support]))

(defn- execute
  [ctx cmd-id attrs]
  (let [{:keys [success error status]}
        (fixtures/execute ctx cmd-id attrs)]
    (cond
      success :ok
      status status
      :else error)))

(defn- states
  [username]
  (into {} (map (juxt :id :state)) (:credentials (mock/query (as username) {:query-id :get-my-credentials}))))

(defn- pending
  [username]
  (:pending (mock/query (as username) {:query-id :get-my-credentials})))

(defn- status-of
  [f]
  (try
    (f)
    :ok
    (catch clojure.lang.ExceptionInfo e
      (:status (ex-data e)))))

(deftest a-person-sees-and-deletes-only-their-own-values
  (mock/with-mock-dal
    (with-platform
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

(deftest values-never-cross-tenants
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (fn [_] {:status 200 :headers {} :body ""})]
        (execute (as "root") :set-tenant-credential {:credential-id "github" :secret "acme-tenant"})
        (execute (as "dave") :set-team-credential {:credential-id "bitbucket" :team-id "alpha" :secret "acme-alpha-0001"})
        (execute (as "alice") :set-my-credential {:credential-id "github" :secret "acme-alice"})
        (testing "globex's alpha admin sets globex's alpha value, not acme's"
          (is
           (= :ok
              (execute (as "gina") :set-team-credential {:credential-id "bitbucket" :team-id "alpha" :secret "globex-alpha-0002"})))))
      (testing "hank of globex sees no acme value, his teams' or tenant's"
        (is
         (= {"github" :missing "jira" :missing "bitbucket" :team "nexus" :missing "llm-key" :missing}
            (states "hank"))))
      (testing "each tenant's team page shows its own value"
        (is
         (= ["…0001" "…0002"]
            (mapv (fn [username]
                    (some #(when (= "bitbucket" (:id %)) (get-in % [:value :hint]))
                          (mock/query (as username) {:query-id :list-team-credentials :team-id "alpha"})))
                  ["dave" "gina"]))))
      (testing "acme's beta does not exist in globex"
        (is
         (= 404
            (status-of #(mock/query (as "gina") {:query-id :list-team-credentials :team-id "beta"})))))
      (testing "globex's admin deletes nothing of acme"
        (execute (as "gina") :delete-tenant-credential {:credential-id "github"})
        (is
         (= :tenant
            (get (states "bob") "github")))))))

(deftest a-value-is-deleted-whether-or-not-its-credential-is-still-declared
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (fn [_] {:status 200 :headers {} :body ""})]
        (execute (as "alice") :set-my-credential {:credential-id "github" :secret "alice-token"}))
      (testing "tenant.json dropped the credential; alice still removes her value"
        (with-redefs [spec/credential (fn [_ _] nil)]
          (is
           (= :ok
              (execute (as "alice") :delete-my-credential {:credential-id "github"}))))
        (is
         (= :missing
            (get (states "alice") "github"))))
      (testing "deleting what was never stored changes nothing"
        (is
         (= :ok
            (execute (as "alice") :delete-my-credential {:credential-id "nope"})))))))

(deftest a-tenant-value-that-no-longer-opens-is-still-listed
  (mock/with-mock-dal
    (with-platform
      (with-redefs [http/request! (fn [_] {:status 200 :headers {} :body ""})]
        (execute (as "root") :set-tenant-credential {:credential-id "github" :secret "acme-tenant"}))
      (with-redefs [vault/unseal (fn [& _] (throw (javax.crypto.AEADBadTagException. "another key")))]
        (is
         (= :tenant
            (get (states "bob") "github")))
        (is
         (= [true]
            (mapv :credential-ready
                  (filter #(= "github" (:id %))
                          (with-redefs [mcp/ping (fn [_] {:reachable true :detail "HTTP 200"})]
                            (mock/query (as "root") {:query-id :list-mcp-servers}))))))))))

(deftest seen-prompts-belong-to-the-person-who-saw-them
  (mock/with-mock-dal
    (with-platform
      (execute (as "alice") :mark-credentials-seen {:credentials ["github" "bitbucket"] :username "bob"})
      (is
       (= {"alice" ["jira"] "bob" ["github" "jira" "bitbucket"]}
          {"alice" (pending "alice") "bob" (pending "bob")}))
      (testing "the platform marks them for the person it names"
        (support/apply! (support/as-platform (fixtures/ctx))
                        {:cmd-id :mark-credentials-seen :id (random-uuid)
                         :attrs {:tenant "acme" :username "bob" :credentials ["github" "bitbucket"]}})
        (is
         (= ["jira"]
            (pending "bob"))))
      (testing "someone of the same name in another tenant has prompts of their own"
        (is
         (= ["github" "jira" "bitbucket"]
            (:pending (mock/query (support/as-user (fixtures/ctx) "alice" {:tenant "globex" :teams {"alpha" :member}})
                                  {:query-id :get-my-credentials}))))))))
