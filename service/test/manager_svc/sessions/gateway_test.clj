(ns manager-svc.sessions.gateway-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.auth :as auth]
            [manager-svc.sessions.api :as api]
            [manager-svc.sessions.fixtures :as fixtures]
            [manager-svc.opencode :as opencode]
            [manager-svc.sessions.access :as access]
            [manager-svc.sessions.gateway :as gateway]
            [manager-svc.sessions.live :as live]
            [manager-svc.sessions.records :as records]
            [manager-svc.test-support :as support]
            [manager-svc.workers :as workers]))

(def session-id #uuid "11111111-1111-1111-1111-111111111111")
(def other-id #uuid "99999999-9999-9999-9999-999999999999")

(defn- identity-of
  [username]
  {:sub username :email (str username "@users.local") :name username})

(defn- login-cookie
  [system username]
  (str (auth/login-cookie (:settings system)) "="
       (auth/login-token (:settings system) (identity-of username))))

(defn- session-cookie
  ([system username]
   (session-cookie system username session-id))
  ([system username sid]
   (str (auth/session-cookie (:settings system)) "="
        (auth/session-token (:settings system) (identity-of username) (str sid)))))

(defn- forward-auth-with
  [system uri headers]
  (gateway/forward-auth system {:method :get
                                :path "/api/gateway/auth"
                                :headers (merge {"x-forwarded-host" (str session-id ".example.test")
                                                 "x-forwarded-uri" uri}
                                                headers)}))

(defn- forward-auth
  [system uri & [username]]
  (forward-auth-with system uri (if username {"cookie" (session-cookie system username)} {})))

(defn- handoff
  [system username]
  (auth/handoff-token (:settings system) (identity-of username) (str session-id)))

(deftest forward-auth-decisions
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (testing "unknown sessions are not found"
          (is
           (= 404
              (:status (forward-auth system "/")))))
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :team})
        (testing "without a login the person is sent through the manager's entry"
          (is
           (= {:status 302 :headers {"Location" (str "http://example.test/sessions/bob/" session-id)}}
              (forward-auth system "/"))))
        (testing "people who may not open it go to its page in the manager"
          (is
           (= (str "http://example.test/sessions/" session-id)
              (get-in (forward-auth system "/" "mallory") [:headers "Location"]))))
        (testing "a valid handoff sets the session-host cookie and drops the token from the URL"
          (let [response
                (forward-auth system (str "/abc?x=1&oc_handoff=" (handoff system "carol")))]
            (is
             (= [302 (str "http://" session-id ".example.test/abc?x=1")]
                [(:status response) (get-in response [:headers "Location"])]))
            (is
             (string/starts-with? (get-in response [:headers "Set-Cookie"]) "oc_session="))))
        (testing "a handoff works once"
          (let [token
                (handoff system "carol")]
            (is
             (= 302
                (:status (forward-auth system (str "/?oc_handoff=" token)))))
            (is
             (= 401
                (:status (forward-auth system (str "/?oc_handoff=" token)))))))
        (testing "a handoff for someone who may not open the session is refused"
          (is
           (= 401
              (:status (forward-auth system (str "/?oc_handoff=" (handoff system "mallory"))))))
          (is
           (= 401
              (:status (forward-auth system "/?oc_handoff=garbage")))))
        (testing "a stopped session is started and the person waits"
          (let [response
                (forward-auth system "/" "carol")]
            (is
             (= 503
                (:status response)))
            (is
             (= :start
                (:pending (api/get-session system session-id))))))
        (testing "a running session that answers gets the OpenCode password"
          (fixtures/set-status! system session-id :running)
          (with-redefs [opencode/healthy? (fn [_ _] true)]
            (is
             (= {:status 200
                 :headers {"Authorization" (opencode/basic-auth (api/secret-key system session-id))}}
                (forward-auth system "/" "carol")))
            (testing "a session cookie close to expiry is renewed through Traefik"
              (let [old
                    (auth/issue (:settings system) (identity-of "carol") auth/session-audience 60 {:sid (str session-id)})

                    response
                    (forward-auth-with system "/" {"cookie" (str "oc_session=" old)})]
                (is
                 (= 200
                    (:status response)))
                (is
                 (string/starts-with? (get-in response [:headers "Set-Cookie"]) "oc_session="))))))
        (testing "a running session that does not answer yet keeps the person waiting"
          (fixtures/set-status! system session-id :starting)
          (live/forget-ready! system session-id)
          (with-redefs [opencode/healthy? (fn [_ _] false)]
            (is
             (= 503
                (:status (forward-auth system "/" "carol"))))))))))

(deftest session-hosts-accept-only-their-own-cookie
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          entry
          (str "http://example.test/sessions/bob/" session-id)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :team})
        (testing "the manager's login cookie is not accepted on a session host"
          (is
           (= entry
              (get-in (forward-auth-with system "/" {"cookie" (login-cookie system "bob")}) [:headers "Location"]))))
        (testing "a session cookie of another session is not accepted"
          (is
           (= entry
              (get-in (forward-auth-with system "/" {"cookie" (session-cookie system "bob" other-id)}) [:headers "Location"]))))
        (testing "a shadowing second cookie of the same name voids both"
          (is
           (= entry
              (get-in (forward-auth-with system "/" {"cookie" (str (session-cookie system "bob") "; "
                                                                   (session-cookie system "mallory"))})
                      [:headers "Location"]))))
        (testing "only the forwarded host counts, never the Host header"
          (is
           (= 404
              (:status (gateway/forward-auth system {:method :get
                                                     :path "/api/gateway/auth"
                                                     :headers {"host" (str session-id ".example.test")
                                                               "cookie" (session-cookie system "bob")}})))))))))

(deftest a-pending-start-is-requested-once
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          kicked
          (atom [])

          commands
          (atom 0)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (let [command!
              records/command!]
          (with-redefs [workers/kick! (fn [& args] (swap! kicked conj (vec args)))
                        records/command! (fn [& args] (swap! commands inc) (apply command! args))]
            (dotimes [_ 3]
              (forward-auth system "/" "bob"))))
        (is
         (= 1
            @commands))
        (is
         (= [[:sessions-lifecycle]]
            @kicked))))))

(deftest access-decisions-are-remembered-briefly
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          checks
          (atom 0)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (fixtures/set-status! system session-id :running)
        (let [can-open?
              access/can-open?]
          (with-redefs [opencode/healthy? (fn [_ _] true)
                        access/can-open? (fn [& args] (swap! checks inc) (apply can-open? args))]
            (dotimes [_ 3]
              (forward-auth system "/" "bob"))
            (testing "a change of the session's sharing asks again"
              (fixtures/command! (support/as-user ctx "bob") {:cmd-id :update-session :id session-id :attrs {:visibility :team}})
              (forward-auth system "/" "bob"))))
        (is
         (= 2
            @checks))))))

(deftest entering
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)

          enter
          (fn [username path & [query]]
            (gateway/enter system {:method :get
                                   :path path
                                   :query (or query {})
                                   :headers (if username {"cookie" (login-cookie system username)} {})}))]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob" :visibility :private})
        (let [path
              (str "/sessions/bob/" session-id)]
          (is
           (= (str "/login?next=%2Fsessions%2Fbob%2F" session-id)
              (get-in (enter nil path) [:headers "Location"])))
          (is
           (= 404
              (:status (enter "mallory" path))))
          (is
           (= 404
              (:status (enter "bob" (str "/sessions/alice/" session-id)))))
          (testing "a team admin who may only look is sent to the session page"
            (is
             (= (str "/sessions/" session-id)
                (get-in (enter "alice" path) [:headers "Location"]))))
          (testing "the owner is handed over to the session host"
            (let [location
                  (get-in (enter "bob" path {:conversation "ses_abcdefgh12"}) [:headers "Location"])

                  token
                  (second (string/split location #"oc_handoff="))]
              (is
               (string/starts-with? location (str "http://" session-id ".example.test/L3Nlc3Npb24vd29ya3NwYWNl/session/ses_abcdefgh12?oc_handoff=")))
              (is
               (= "bob"
                  (:sub (auth/read-handoff (:settings system) token (str session-id)))))))
          (is
           (= 422
              (:status (enter "bob" path {:conversation "../etc"}))))
          (testing "a malformed name is not found, not an error"
            (is
             (= 404
                (:status (enter "bob" (str "/sessions/%zz/" session-id)))))))))))

(deftest waking
  (mock/with-mock-dal
    (let [system
          (fixtures/system)

          ctx
          (fixtures/ctx system)]
      (fixtures/with-domains ctx
        (fixtures/create-session! system ctx {:id session-id :owner "bob"})
        (testing "requests for the manager itself pass through"
          (is
           (nil?
            (gateway/wake system {:host "example.test" :path "/api/query" :headers {}}))))
        (testing "the forward-auth endpoint is never woken"
          (is
           (nil?
            (gateway/wake system {:host (str session-id ".example.test") :path "/api/gateway/auth" :headers {}}))))
        (is
         (= 503
            (:status (gateway/wake system {:host (str session-id ".example.test")
                                           :path "/"
                                           :headers {"cookie" (session-cookie system "bob")}}))))
        (is
         (= :start
            (:pending (api/get-session system session-id))))))))

(deftest handoff-stripping
  (is
   (= "/a/b?x=1&y=2"
      (gateway/without-handoff "/a/b?x=1&oc_handoff=tok&y=2")))
  (is
   (= "/"
      (gateway/without-handoff "?oc_handoff=tok")))
  (testing "malformed percent-encoding is no error"
    (is
     (nil?
      (gateway/handoff-of "/?x=%zz")))))
