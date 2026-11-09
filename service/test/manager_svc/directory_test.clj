(ns manager-svc.directory-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.common :as common]
            [edd.core :as edd]
            [edd.security :as security]
            [edd.test.fixture.dal :as mock]
            [manager-svc.directory :as directory]
            [manager-svc.test-support :as support]))

(defn- ctx
  []
  (support/as-platform (security/register (support/ctx))))

(defn- upsert!
  [ctx identity collection row]
  (support/apply! ctx (directory/upsert identity collection row)))

(defn- remove!
  [ctx identity collection row-id version]
  (support/apply! ctx (directory/removal identity collection row-id version)))

(defn- directory-of
  [ctx identity]
  (common/get-by-id ctx {:id (common/get-aggregate-id-by-identity ctx identity)}))

(defn- run-row
  [n version]
  {:id (str "run-" n) :version version :status :running :tenant "acme"})

(deftest the-first-row-creates-the-directory-and-its-identity
  (mock/with-mock-dal
    (let [ctx
          (ctx)]
      (upsert! ctx "/team/acme/alpha" :runs (run-row 1 1))
      (let [id
            (common/get-aggregate-id-by-identity ctx "/team/acme/alpha")]
        (is
         (uuid? id))
        (is
         (= {:identity "/team/acme/alpha"
             :collections {:runs [(run-row 1 1)]}}
            (select-keys (directory-of ctx "/team/acme/alpha") [:identity :collections])))
        (testing "a second row goes to the same directory"
          (upsert! ctx "/team/acme/alpha" :runs (run-row 2 1))
          (is
           (= id
              (common/get-aggregate-id-by-identity ctx "/team/acme/alpha")))
          (is
           (= [(run-row 1 1) (run-row 2 1)]
              (directory/rows ctx "/team/acme/alpha" :runs))))
        (testing "its tenant lists it, and the platform lists the tenant"
          (is
           (= ["/team/acme/alpha"]
              (directory/identities ctx "acme" "team")))
          (is
           (= ["acme"]
              (directory/tenants ctx))))))))

(deftest upserts-are-idempotent-and-keep-the-newest-version
  (mock/with-mock-dal
    (let [ctx
          (ctx)]
      (upsert! ctx "/user/alice" :runs (run-row 1 2))
      (upsert! ctx "/user/alice" :runs (run-row 1 2))
      (upsert! ctx "/user/alice" :runs (assoc (run-row 1 1) :status :queued))
      (is
       (= [(run-row 1 2)]
          (directory/rows ctx "/user/alice" :runs)))
      (is
       (= 1
          (count (filter #(and (= :directory-row-upserted (:event-id %)) (= :runs (get-in % [:attrs :collection])))
                         (mock/peek-state :event-store)))))
      (upsert! ctx "/user/alice" :runs (assoc (run-row 1 3) :status :completed))
      (is
       (= [(assoc (run-row 1 3) :status :completed)]
          (directory/rows ctx "/user/alice" :runs))))))

(deftest a-full-collection-drops-its-oldest-row
  (mock/with-mock-dal
    (with-redefs [directory/max-rows 3]
      (let [ctx
            (ctx)]
        (doseq [n (range 5)]
          (upsert! ctx "/team/acme/alpha" :runs (run-row n 1)))
        (upsert! ctx "/team/acme/alpha" :runs (run-row 3 2))
        (is
         (= [(run-row 2 1) (run-row 3 2) (run-row 4 1)]
            (directory/rows ctx "/team/acme/alpha" :runs)))))))

(deftest the-tenant-index-is-never-capped
  (mock/with-mock-dal
    (with-redefs [directory/max-rows 2]
      (let [ctx
            (ctx)]
        (doseq [team ["a" "b" "c" "d"]]
          (upsert! ctx (directory/team "acme" team) :runs (run-row 1 1)))
        (is
         (= ["/team/acme/a" "/team/acme/b" "/team/acme/c" "/team/acme/d"]
            (directory/identities ctx "acme" "team")))))))

(deftest identities-name-their-kind-and-tenant
  (is
   (= "/tenant/acme"
      (directory/tenant "acme")))
  (is
   (= "/team/acme/demo"
      (directory/team "acme" "demo")))
  (is
   (= "/user/bob@acme.test"
      (directory/user "bob@acme.test")))
  (is
   (= {:kind "team" :tenant "acme" :name "demo"}
      (directory/parse "/team/acme/demo")))
  (is
   (= {:kind "user" :name "bob"}
      (directory/parse "/user/bob")))
  (is
   (= {:kind "tenant" :tenant "acme" :name "acme"}
      (directory/parse "/tenant/acme"))))

(deftest people-join-the-tenants-their-rows-belong-to
  (mock/with-mock-dal
    (let [ctx
          (ctx)]
      (upsert! ctx "/user/bob" :runs (run-row 1 1))
      (upsert! ctx "/user/bob" :runs (run-row 2 1))
      (upsert! ctx "/user/gina" :runs (assoc (run-row 3 1) :tenant "globex"))
      (upsert! ctx "/team/globex/research" :runs (assoc (run-row 3 1) :tenant "globex"))
      (is
       (= ["/user/bob"]
          (directory/identities ctx "acme" "user")))
      (is
       (= ["/user/gina"]
          (directory/identities ctx "globex" "user")))
      (is
       (= ["/team/globex/research"]
          (directory/identities ctx "globex" "team")))
      (is
       (= ["acme" "globex"]
          (directory/tenants ctx)))
      (is
       (= ["/user/bob" "/user/gina"]
          (directory/all-identities ctx "user")))
      (is
       (= 3
          (count (filter #(= :directory-tenant-joined (:event-id %)) (mock/peek-state :event-store))))
       "bob joins acme once, gina globex once, the team its own tenant once")
      (testing "reads within a tenant keep only its rows"
        (upsert! ctx "/user/bob" :runs (assoc (run-row 4 1) :tenant "globex"))
        (is
         (= #{"run-1" "run-2"}
            (set (map :id (directory/tenant-rows ctx "acme" ["/user/bob"] :runs)))))
        (is
         (= #{"/user/bob" "/user/gina"}
            (set (directory/identities ctx "globex" "user"))))))))

(deftest a-command-on-a-new-entity-creates-it-first
  (is
   (= [{:identity "/team/acme/demo"}
       {:event-id :directory-created :attrs {:identity "/team/acme/demo"}}
       {:event-id :directory-tenant-joined :attrs {:tenant "acme" :identity "/team/acme/demo"}}
       {:event-id :team-synced :attrs {}}]
      (directory/with-creation nil "/team/acme/demo" [{:event-id :team-synced :attrs {}}])))
  (is
   (= [{:event-id :team-synced :attrs {}}]
      (directory/with-creation {:identity "/team/acme/demo" :tenants ["acme"]} "/team/acme/demo"
        [{:event-id :team-synced :attrs {}}])))
  (is
   (= [{:identity "/tenant/acme"}
       {:event-id :directory-created :attrs {:identity "/tenant/acme"}}
       {:event-id :tenant-synced :attrs {}}]
      (directory/with-creation nil "/tenant/acme" [{:event-id :tenant-synced :attrs {}}]))))

(deftest removed-rows-stay-removed-for-older-versions
  (mock/with-mock-dal
    (let [ctx
          (ctx)]
      (upsert! ctx "/team/acme/alpha" :sessions (run-row 1 1))
      (upsert! ctx "/team/acme/alpha" :sessions (run-row 2 1))
      (remove! ctx "/team/acme/alpha" :sessions "run-1" 3)
      (is
       (= [(run-row 2 1)]
          (directory/rows ctx "/team/acme/alpha" :sessions)))
      (testing "a late upsert of an older version"
        (upsert! ctx "/team/acme/alpha" :sessions (run-row 1 2))
        (is
         (= [(run-row 2 1)]
            (directory/rows ctx "/team/acme/alpha" :sessions))))
      (testing "a newer version comes back"
        (upsert! ctx "/team/acme/alpha" :sessions (run-row 1 4))
        (is
         (= [(run-row 2 1) (run-row 1 4)]
            (directory/rows ctx "/team/acme/alpha" :sessions))))
      (testing "a removal before the first upsert leaves a tombstone"
        (remove! ctx "/team/acme/beta" :sessions "run-1" 3)
        (upsert! ctx "/team/acme/beta" :sessions (run-row 1 2))
        (is
         (= []
            (directory/rows ctx "/team/acme/beta" :sessions)))
        (upsert! ctx "/team/acme/beta" :sessions (run-row 1 4))
        (is
         (= [(run-row 1 4)]
            (directory/rows ctx "/team/acme/beta" :sessions)))))))

(deftest a-concurrent-first-create-lands-in-the-winning-directory
  (mock/with-mock-dal
    (let [ctx
          (ctx)

          raced
          (assoc (directory/upsert "/team/acme/alpha" :runs (run-row 2 1))
                 :request-id #uuid "00000000-0000-0000-0000-000000000002"
                 :interaction-id #uuid "00000000-0000-0000-0000-000000000002")]
      (upsert! ctx "/team/acme/alpha" :runs (run-row 1 1))
      (testing "a request that resolved the identity before the first one stored it"
        (with-redefs [common/get-aggregate-id-by-identity (constantly nil)]
          (is
           (thrown-with-msg? clojure.lang.ExceptionInfo #"Identity already exists"
                             (mock/apply-cmd ctx raced)))))
      (is
       (= 1
          (count (filter #(= "/team/acme/alpha" (:identity %)) (mock/peek-state :identity-store)))))
      (testing "its redelivery resolves the identity"
        (mock/apply-cmd ctx raced)
        (is
         (= [(run-row 1 1) (run-row 2 1)]
            (directory/rows ctx "/team/acme/alpha" :runs)))))))

(deftest rows-of-several-directories-keep-the-newest-copy
  (mock/with-mock-dal
    (let [ctx
          (ctx)]
      (upsert! ctx "/user/alice" :sessions (run-row 1 1))
      (upsert! ctx "/team/acme/alpha" :sessions (run-row 1 2))
      (upsert! ctx "/team/acme/alpha" :sessions (run-row 2 1))
      (is
       (= #{(run-row 1 2) (run-row 2 1)}
          (set (directory/rows-of ctx ["/user/alice" "/team/acme/alpha" "/team/acme/missing"] :sessions))))
      (is
       (= []
          (directory/rows-of ctx [] :sessions))))))

(deftest people-cannot-write-directories
  (mock/with-mock-dal
    (let [response
          (mock/handle-cmd (support/as-user (security/register (support/ctx)) "mallory")
                           (directory/upsert "/team/acme/alpha" :runs (run-row 1 1)))]
      (is
       (= "Forbidden"
          (get-in response [:error :message]))))))

(deftest a-directory-command-without-an-identity-fails-cleanly
  (mock/with-mock-dal
    (let [response
          (mock/handle-cmd (support/as-user (security/register (support/ctx)) "mallory")
                           {:cmd-id :upsert-directory-row :attrs {}})]
      (is
       (some? (:error response)))
      (is
       (not= "Internal error"
             (get-in response [:error :message]))))))

(deftest row-effects-carry-an-aggregate-id-for-the-command-store
  (mock/with-mock-dal
    (let [thing-id
          #uuid "44444444-4444-4444-4444-444444444444"

          ctx
          (-> (support/ctx)
              (edd/reg-cmd :make-thing (fn [_ _] {:event-id :thing-made :attrs {}}) :auth {:role [:non-interactive]})
              (edd/reg-event :thing-made (fn [aggregate _] (assoc aggregate :made true)))
              (directory/reg-rows #{:thing-made}
                                  (fn [thing] [(directory/upsert "/team/acme/alpha" :things (select-keys thing [:id :version]))]))
              (security/register)
              (support/as-platform))

          {:keys [effects]}
          (mock/handle-cmd (assoc ctx :no-summary true) {:cmd-id :make-thing :id thing-id})]
      (is
       (= [[{:cmd-id :upsert-directory-row
             :id thing-id
             :attrs {:identity "/team/acme/alpha" :collection :things :row {:id thing-id :version 1}}}]]
          (mapv :commands effects)))
      (support/settle! ctx)
      (is
       (= [{:id thing-id :version 1}]
          (directory/rows ctx "/team/acme/alpha" :things))))))
