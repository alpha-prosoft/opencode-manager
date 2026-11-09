(ns manager-svc.oidc-test
  "OpenID Connect login against a fake provider: an in-process HTTP server
   with discovery, JWKS, token and userinfo endpoints issuing RS256 tokens."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [manager-svc.auth :as auth]
            [manager-svc.edge.api :as edge-api]
            [manager-svc.edge.server :as server]
            [manager-svc.http :as http]
            [manager-svc.oidc :as oidc]
            [manager-svc.test-support :as support])
  (:import [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayInputStream]
           [java.net InetSocketAddress URI URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.security KeyPairGenerator]
           [java.security.interfaces RSAPrivateKey RSAPublicKey]
           [java.time Instant]
           [java.util Base64 UUID]))

(defn- key-pair
  []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(def provider-keys
  (key-pair))

(def stranger-keys
  (key-pair))

(defn- b64url
  [^bytes data]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) data))

(defn- unsigned
  ^bytes [^java.math.BigInteger n]
  (let [bytes
        (.toByteArray n)]
    (if (zero? (aget bytes 0))
      (java.util.Arrays/copyOfRange bytes 1 (alength bytes))
      bytes)))

(defn- jwks
  []
  (let [^RSAPublicKey public
        (.getPublic provider-keys)]
    {:keys [{:kty "RSA" :kid "k1" :use "sig" :alg "RS256"
             :n (b64url (unsigned (.getModulus public)))
             :e (b64url (unsigned (.getPublicExponent public)))}]}))

(defn- decode-form
  [text]
  (into {}
        (for [pair (string/split (str text) #"&")
              :let [[k v] (string/split pair #"=" 2)]
              :when (seq k)]
          [(keyword (URLDecoder/decode k StandardCharsets/UTF_8))
           (URLDecoder/decode (str v) StandardCharsets/UTF_8)])))

(defn- sign-token
  [{:keys [keys kid claims]}]
  (let [keys
        (or keys provider-keys)]
    (.sign (-> (JWT/create)
               (.withKeyId ^String (or kid "k1"))
               (.withPayload ^java.util.Map claims))
           (Algorithm/RSA256 ^RSAPublicKey (.getPublic keys) ^RSAPrivateKey (.getPrivate keys)))))

(defn- in-seconds
  [seconds]
  (.getEpochSecond (.plusSeconds (Instant/now) seconds)))

(defn- s256
  [verifier]
  (oidc/code-challenge verifier))

(defn- token-response
  [{:keys [issuer] :as provider} {:keys [nonce claims]}]
  (let [{:keys [id-token access-token signing]}
        @(:tweaks provider)

        id-claims
        (merge {"iss" issuer "aud" "manager" "azp" "manager" "nonce" nonce "exp" (in-seconds 300)}
               claims
               id-token)

        access-claims
        (merge {"iss" issuer "aud" "account" "exp" (in-seconds 300)}
               claims
               access-token)]
    {:access_token (sign-token {:claims access-claims})
     :id_token (sign-token (merge signing {:claims (into {} (remove (comp nil? val)) id-claims)}))
     :token_type "Bearer"}))

(defn- respond!
  [^HttpExchange exchange status body]
  (let [bytes
        (.getBytes (json/write-value-as-string body) StandardCharsets/UTF_8)]
    (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- basic-of
  [^HttpExchange exchange]
  (some-> (.getFirst (.getRequestHeaders exchange) "Authorization")
          (string/replace #"^Basic " "")
          (->> (.decode (Base64/getDecoder)))
          (String. StandardCharsets/UTF_8)))

(defn- token-endpoint
  [{:keys [codes requests] :as provider} ^HttpExchange exchange]
  (let [form
        (decode-form (slurp (.getRequestBody exchange)))

        grant
        (get @codes (:code form))]
    (swap! requests conj (assoc form :basic (basic-of exchange)))
    (swap! codes dissoc (:code form))
    (if (and grant
             (= "authorization_code" (:grant_type form))
             (= (:redirect-uri grant) (:redirect_uri form))
             (= (some->> (:client-secret provider) (str "manager:")) (basic-of exchange))
             (= (:challenge grant) (s256 (:code_verifier form))))
      (respond! exchange 200 (token-response provider grant))
      (respond! exchange 400 {:error "invalid_grant" :error_description "Code not valid"}))))

(defn- start-provider!
  "A fake provider; (:issuer provider) is its issuer URL."
  [& {:keys [end-session? discovered-issuer client-secret] :or {end-session? true client-secret "s3cret"}}]
  (let [server
        (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)

        base
        (str "http://127.0.0.1:" (.getPort (.getAddress server)))

        issuer
        (str base "/realms/test")

        provider
        {:server server
         :issuer issuer
         :client-secret client-secret
         :codes (atom {})
         :requests (atom [])
         :tweaks (atom {})
         :userinfo (atom {})}

        routes
        {"/realms/test/.well-known/openid-configuration"
         (fn [exchange]
           (respond! exchange 200 (cond-> {:issuer (or discovered-issuer issuer)
                                           :authorization_endpoint (str issuer "/auth")
                                           :token_endpoint (str issuer "/token")
                                           :jwks_uri (str issuer "/certs")
                                           :userinfo_endpoint (str issuer "/userinfo")}
                                    end-session? (assoc :end_session_endpoint (str issuer "/logout")))))

         "/realms/test/certs"
         (fn [exchange] (respond! exchange 200 (jwks)))

         "/realms/test/token"
         (fn [exchange] (token-endpoint provider exchange))

         "/realms/test/userinfo"
         (fn [exchange] (respond! exchange 200 @(:userinfo provider)))}]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (if-let [route
                                 (get routes (.getPath (.getRequestURI exchange)))]
                          (route exchange)
                          (respond! exchange 404 {:error "not found"})))))
    (.start server)
    provider))

(defn- stop-provider!
  [{:keys [^HttpServer server]}]
  (.stop server 0))

(defmacro with-provider
  [[binding & opts] & body]
  `(let [~binding (start-provider! ~@opts)]
     (try
       ~@body
       (finally
         (stop-provider! ~binding)))))

(defn- settings-for
  [provider & {:as overrides}]
  (merge support/settings
         {:auth-mode "oidc"
          :oidc-issuer (:issuer provider)
          :oidc-client-id "manager"
          :oidc-client-secret "s3cret"
          :oidc-scopes "openid profile email"
          :oidc-claims-source "id_token"}
         overrides))

(defn- call
  [settings method path & {:keys [query cookies body]}]
  (let [request
        {:method method
         :path path
         :query (or query {})
         :headers {}
         :cookies (or cookies {})
         :body (ByteArrayInputStream. (.getBytes (str body) StandardCharsets/UTF_8))}]
    ((:handler (server/route edge-api/routes request)) {:settings settings} request)))

(defn- set-cookies
  [response]
  (let [value
        (get-in response [:headers "Set-Cookie"])]
    (if (sequential? value) value [value])))

(defn- cookie
  [response cookie-name]
  (some #(second (re-find (re-pattern (str "^" cookie-name "=([^;]*)")) (str %))) (set-cookies response)))

(defn- query-of
  [url]
  (decode-form (.getRawQuery (URI. url))))

(def alice-claims
  {"sub" "8d6f0e2a-alice"
   "preferred_username" "alice"
   "email" "alice@acme.test"
   "name" "Alice"
   "tenant" "acme"
   "groups" ["/acme/demo/admin" "/acme/payments"]
   "realm_access" {"roles" ["tenant-admin"]}})

(defn- start-login
  [settings next]
  (let [response
        (call settings :get "/api/auth/oidc/login" :query {:next next})

        location
        (get-in response [:headers "Location"])]
    {:response response
     :location location
     :params (query-of location)
     :state-cookie (cookie response "oc_oidc")}))

(defn- authorize!
  "What the provider does once the person logged in: a code for the
   authorization request params."
  [provider params claims]
  (let [code
        (str (UUID/randomUUID))]
    (swap! (:codes provider) assoc code {:challenge (:code_challenge params)
                                         :nonce (:nonce params)
                                         :redirect-uri (:redirect_uri params)
                                         :claims claims})
    code))

(defn- log-in
  "The whole round trip as a browser makes it; the callback response."
  [provider settings claims & {:keys [next state] :or {next "/runs"}}]
  (let [{:keys [params state-cookie]}
        (start-login settings next)

        code
        (authorize! provider params claims)]
    (call settings :get "/api/auth/oidc/callback"
          :query {:code code :state (or state (:state params))}
          :cookies {"oc_oidc" state-cookie})))

(defn- refusal
  "The message the login page shows after a refused callback."
  [settings callback]
  (let [response
        (call settings :get "/api/auth/config" :cookies {"oc_oidc" (cookie callback "oc_oidc")})]
    (:error (json/read-value (:body response) json/keyword-keys-object-mapper))))

(deftest logging-in-starts-an-authorization-code-request-with-pkce
  (with-provider [provider]
    (let [settings
          (settings-for provider)

          {:keys [response location params state-cookie]}
          (start-login settings "/runs?team=demo")

          pending
          (oidc/read-state settings state-cookie)]
      (is
       (= 302
          (:status response)))
      (is
       (string/starts-with? location (str (:issuer provider) "/auth?")))
      (is
       (= {:response_type "code"
           :client_id "manager"
           :redirect_uri "http://localhost/api/auth/oidc/callback"
           :scope "openid profile email"
           :code_challenge_method "S256"}
          (select-keys params [:response_type :client_id :redirect_uri :scope :code_challenge_method])))
      (testing "the cookie remembers state, nonce, verifier and where to go"
        (is
         (= [(:state params) (:nonce params) "/runs?team=demo"]
            [(:state pending) (:nonce pending) (:next pending)]))
        (is
         (= (:code_challenge params)
            (oidc/code-challenge (:verifier pending))))
        (is
         (every? #(string/includes? (first (set-cookies response)) %) ["HttpOnly" "SameSite=Lax" "Max-Age=600"])))
      (testing "every login has its own state and nonce"
        (is
         (not= (:state params)
               (:state (:params (start-login settings "/")))))))))

(deftest a-provider-login-issues-the-login-cookie-with-the-parsed-claims
  (with-provider [provider]
    (let [settings
          (settings-for provider)

          response
          (log-in provider settings alice-claims :next "/runs")

          token-request
          (last @(:requests provider))]
      (is
       (= 302
          (:status response)))
      (is
       (= "/login?next=%2Fruns&complete=1"
          (get-in response [:headers "Location"])))
      (is
       (= {:sub "alice"
           :email "alice@acme.test"
           :name "Alice"
           :tenant "acme"
           :teams {"demo" :admin "payments" :member}
           :tenant-admin true
           :operator false}
          (auth/person settings (cookie response "oc_auth"))))
      (testing "the state cookie is used up"
        (is
         (= ""
            (cookie response "oc_oidc"))))
      (testing "the code was exchanged with the client secret, the redirect URL and the PKCE verifier"
        (is
         (= ["authorization_code" "manager:s3cret" "http://localhost/api/auth/oidc/callback"]
            [(:grant_type token-request) (:basic token-request) (:redirect_uri token-request)]))))))

(deftest a-public-client-logs-in-with-pkce-alone
  (with-provider [provider :client-secret nil]
    (let [settings
          (settings-for provider :oidc-client-secret nil)

          response
          (log-in provider settings alice-claims)

          token-request
          (last @(:requests provider))]
      (is
       (= "alice"
          (:sub (auth/person settings (cookie response "oc_auth")))))
      (is
       (= [nil "manager"]
          [(:basic token-request) (:client_id token-request)]))
      (testing "a confidential client's code needs its secret"
        (with-provider [confidential]
          (let [without-secret
                (settings-for confidential :oidc-client-secret nil)]
            (is
             (string/includes? (refusal without-secret (log-in confidential without-secret alice-claims))
                               "refused the code"))))))))

(deftest the-login-works-with-a-discovery-url-alone
  (with-provider [provider]
    (let [settings
          (settings-for provider
                        :oidc-issuer nil
                        :oidc-discovery-url (str (:issuer provider) "/.well-known/openid-configuration"))]
      (is
       (= "alice"
          (:sub (auth/person settings (cookie (log-in provider settings alice-claims) "oc_auth")))))
      (testing "the ID token's issuer is the document's"
        (reset! (:tweaks provider) {:id-token {"iss" "https://evil.example"}})
        (is
         (string/includes? (refusal settings (log-in provider settings alice-claims)) "is not valid"))))))

(deftest the-callback-needs-the-browser-that-started-the-login
  (with-provider [provider]
    (let [settings
          (settings-for provider)]
      (testing "another state"
        (let [response
              (log-in provider settings alice-claims :state "forged")]
          (is
           (nil? (cookie response "oc_auth")))
          (is
           (string/includes? (refusal settings response) "started in another browser"))))
      (testing "no state cookie"
        (let [{:keys [params]}
              (start-login settings "/")

              response
              (call settings :get "/api/auth/oidc/callback"
                    :query {:code (authorize! provider params alice-claims) :state (:state params)})]
          (is
           (= [302 nil]
              [(:status response) (cookie response "oc_auth")]))))
      (testing "a code is exchanged once"
        (let [{:keys [params state-cookie]}
              (start-login settings "/")

              code
              (authorize! provider params alice-claims)

              callback
              #(call settings :get "/api/auth/oidc/callback"
                     :query {:code code :state (:state params)}
                     :cookies {"oc_oidc" state-cookie})]
          (is
           (some? (cookie (callback) "oc_auth")))
          (is
           (string/includes? (refusal settings (callback)) "refused the code")))))))

(deftest the-id-token-must-be-for-this-client-from-this-issuer-with-this-nonce
  (with-provider [provider]
    (let [settings
          (settings-for provider)

          refused-with
          (fn [tweaks]
            (reset! (:tweaks provider) tweaks)
            (let [response
                  (log-in provider settings alice-claims)]
              [(cookie response "oc_auth") (refusal settings response)]))]
      (doseq [[case-name tweaks expected]
              [["another issuer" {:id-token {"iss" "https://evil.example"}} "is not valid"]
               ["another audience" {:id-token {"aud" "other-app"}} "is not valid"]
               ["another authorized party" {:id-token {"azp" "other-app"}} "issued to another client"]
               ["another nonce" {:id-token {"nonce" "replayed"}} "is not valid"]
               ["no nonce" {:id-token {"nonce" nil}} "is not valid"]
               ["expired" {:id-token {"exp" (in-seconds -600)}} "is not valid"]
               ["no expiry" {:id-token {"exp" nil}} "is not valid"]
               ["a key outside the JWKS" {:signing {:keys stranger-keys}} "is not valid"]
               ["an unknown key id" {:signing {:kid "k9"}} "signing key is unknown"]]]
        (testing case-name
          (let [[login-cookie message]
                (refused-with tweaks)]
            (is
             (nil? login-cookie))
            (is
             (string/includes? (str message) expected)
             message)))))))

(deftest the-claims-parser-decides-who-gets-in
  (with-provider [provider]
    (let [settings
          (settings-for provider)

          response
          (log-in provider settings {"sub" "nobody" "preferred_username" "nobody" "groups" []} :next "/teams")]
      (is
       (= "/login?next=%2Fteams"
          (get-in response [:headers "Location"])))
      (is
       (nil? (cookie response "oc_auth")))
      (is
       (= "Login refused: your account belongs to no tenant"
          (refusal settings response)))
      (testing "the login page shows the reason once"
        (is
         (= ""
            (cookie (call settings :get "/api/auth/config" :cookies {"oc_oidc" (cookie response "oc_oidc")}) "oc_oidc")))))))

(deftest operators-plug-in-their-own-claims-parser
  (with-provider [provider]
    (let [file
          (doto (java.io.File/createTempFile "oidc-parser" ".clj")
            (.deleteOnExit))

          _
          (spit file (str "(ns custom.oidc-parser)\n"
                          "(defn parse [claims]\n"
                          "  {:username (:uid claims)\n"
                          "   :tenant (:org claims)\n"
                          "   :teams (for [m (:memberships claims)\n"
                          "                :let [[team role] (clojure.string/split m #\":\")]]\n"
                          "            {:id team :role (keyword role)})})\n"))

          settings
          (settings-for provider
                        :claims-parser "custom.oidc-parser/parse"
                        :claims-parser-file (.getPath file))

          response
          (log-in provider settings {"sub" "x-1" "uid" "zoe" "org" "globex" "memberships" ["research:admin" "lab:member"]})]
      (is
       (= {:sub "zoe" :tenant "globex" :teams {"lab" :member "research" :admin}}
          (select-keys (auth/person settings (cookie response "oc_auth")) [:sub :tenant :teams]))))))

(deftest claims-can-come-from-userinfo-or-the-access-token
  (with-provider [provider]
    (let [id-only
          {"sub" "u-bob" "preferred_username" "bob" "tenant" "acme"}]
      (testing "userinfo adds the groups the ID token leaves out"
        (reset! (:userinfo provider) {"sub" "u-bob" "groups" ["/acme/demo"]})
        (let [settings
              (settings-for provider :oidc-claims-source "id_token,userinfo")]
          (is
           (= {"demo" :member}
              (:teams (auth/person settings (cookie (log-in provider settings id-only) "oc_auth")))))))
      (testing "userinfo of someone else is refused"
        (reset! (:userinfo provider) {"sub" "u-mallory" "groups" ["/acme/demo/admin"]})
        (let [settings
              (settings-for provider :oidc-claims-source "userinfo")

              response
              (log-in provider settings id-only)]
          (is
           (nil? (cookie response "oc_auth")))
          (is
           (string/includes? (refusal settings response) "different people"))))
      (testing "the access token's verified claims"
        (reset! (:tweaks provider) {:access-token {"realm_access" {"roles" ["tenant-admin"]}}})
        (let [settings
              (settings-for provider :oidc-claims-source "access_token")]
          (is
           (true?
            (:tenant-admin (auth/person settings (cookie (log-in provider settings id-only) "oc_auth"))))))))))

(deftest a-provider-that-is-down-or-answers-for-another-issuer-sends-people-back-with-a-reason
  (with-provider [provider :discovered-issuer "https://evil.example/realms/test"]
    (let [settings
          (settings-for provider)

          {:keys [response]}
          (start-login settings "/runs")]
      (is
       (= "/login?next=%2Fruns"
          (get-in response [:headers "Location"])))
      (is
       (string/includes? (refusal settings {:headers {"Set-Cookie" (get-in response [:headers "Set-Cookie"])}})
                         "identity provider can't be reached")))))

(deftest an-unexpected-failure-names-no-internal-host
  (with-provider [provider]
    (let [settings
          (settings-for provider)

          {:keys [params state-cookie]}
          (start-login settings "/runs")

          code
          (authorize! provider params alice-claims)

          callback
          (with-redefs [http/request! (fn [_] (throw (java.net.ConnectException. "idp.internal:8443 refused")))]
            (call settings :get "/api/auth/oidc/callback"
                  :query {:code code :state (:state params)}
                  :cookies {"oc_oidc" state-cookie}))]
      (is
       (nil? (cookie callback "oc_auth")))
      (is
       (= oidc/provider-unreachable
          (refusal settings callback))))))

(deftest logging-out-ends-the-provider-session-too
  (with-provider [provider]
    (let [settings
          (settings-for provider :public-port 8080)

          id-token
          (cookie (log-in provider settings alice-claims) "oc_oidc_hint")

          response
          (call settings :post "/api/auth/logout" :cookies {"oc_oidc_hint" id-token})

          redirect
          (:redirect (json/read-value (:body response) json/keyword-keys-object-mapper))]
      (is
       (= 200
          (:status response)))
      (is
       (= ""
          (cookie response "oc_auth")))
      (is
       (string/starts-with? redirect (str (:issuer provider) "/logout?")))
      (is
       (= {:client_id "manager" :id_token_hint id-token :post_logout_redirect_uri "http://localhost:8080/login"}
          (query-of redirect)))
      (testing "the ID token was kept for this, and is dropped with the login"
        (is
         (= "eyJ"
            (subs id-token 0 3)))
        (is
         (= ""
            (cookie response "oc_oidc_hint"))))))
  (with-provider [provider :end-session? false]
    (is
     (= 204
        (:status (call (settings-for provider) :post "/api/auth/logout"))))))

(deftest next-stays-on-the-manager
  (let [settings
        (assoc support/settings :public-port 8080)]
    (doseq [[value expected]
            [["/runs?team=demo" "/runs?team=demo"]
             ["http://localhost:8080/teams/demo" "/teams/demo"]
             ["//evil.example/x" "/"]
             ["/\\evil.example" "/"]
             ["https://evil.example/" "/"]
             ["http://localhost:8080.evil.example/" "/"]
             ["/%2F%2Fevil.example" "/"]
             ["/login?next=/x" "/"]
             ["javascript:alert(1)" "/"]
             [nil "/"]]]
      (is
       (= expected
          (oidc/safe-next settings value))
       value))))

(deftest the-ui-learns-the-auth-mode
  (doseq [mode ["login" "jwt" "oidc"]]
    (is
     (= {:mode mode}
        (json/read-value (:body (call (assoc support/settings :auth-mode mode) :get "/api/auth/config"))
                         json/keyword-keys-object-mapper))))
  (testing "provider login is offered in oidc mode only"
    (is
     (= [404 404]
        [(:status (call support/settings :get "/api/auth/oidc/login"))
         (:status (call support/settings :get "/api/auth/oidc/callback"))])))
  (testing "login mode's username login is not offered in oidc mode"
    (is
     (= 404
        (:status (call (assoc support/settings :auth-mode "oidc") :post "/api/auth/login" :body "{\"username\":\"bob\"}"))))))

(deftest oidc-settings-are-checked-at-start
  (let [settings
        {:auth-mode "oidc" :oidc-issuer "https://idp.example/realms/x" :oidc-client-id "manager" :oidc-claims-source "id_token"}]
    (is
     (nil? (oidc/check-settings! settings)))
    (is
     (thrown? clojure.lang.ExceptionInfo
              (oidc/check-settings! (dissoc settings :oidc-issuer))))
    (is
     (thrown? clojure.lang.ExceptionInfo
              (oidc/check-settings! (dissoc settings :oidc-client-id))))
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown cookie"
                       (oidc/check-settings! (assoc settings :oidc-claims-source "id_token, cookie"))))
    (testing "the client secret may come from a file"
      (let [file
            (doto (java.io.File/createTempFile "oidc-secret" "")
              (.deleteOnExit))]
        (spit file "from-file\n")
        (is
         (= "from-file"
            (oidc/client-secret {:oidc-client-secret-file (.getPath file)})))
        (is
         (= "direct"
            (oidc/client-secret {:oidc-client-secret "direct" :oidc-client-secret-file (.getPath file)})))))
    (is
     (nil? (auth/check-settings! (merge support/settings settings))))))
