(ns manager-svc.idp-test
  "Reloadable identity provider key material against a fake provider (an
   in-process HTTP server publishing a discovery document and a JWKS the
   test rotates), through jwt mode's token check."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [manager-svc.auth :as auth]
            [manager-svc.idp :as idp]
            [manager-svc.oidc :as oidc]
            [manager-svc.reload :as reload]
            [manager-svc.test-support :as support])
  (:import [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.security KeyPairGenerator]
           [java.security.interfaces ECPrivateKey ECPublicKey RSAPrivateKey RSAPublicKey]
           [java.security.spec ECGenParameterSpec]
           [java.time Instant]
           [java.util Base64]
           [java.util.concurrent CountDownLatch]))

(defn- rsa-pair
  []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(defn- ec-pair
  []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "EC") (.initialize (ECGenParameterSpec. "secp256r1")))))

(def k1
  (rsa-pair))

(def k2
  (rsa-pair))

(def ec1
  (ec-pair))

(defn- b64url
  [^java.math.BigInteger n]
  (let [bytes
        (.toByteArray n)

        unsigned
        (if (and (> (alength bytes) 1) (zero? (aget bytes 0)))
          (java.util.Arrays/copyOfRange bytes 1 (alength bytes))
          bytes)]
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) unsigned)))

(defn- rsa-jwk
  [pair kid & {:as extra}]
  (let [^RSAPublicKey public
        (.getPublic pair)]
    (merge {:kty "RSA" :kid kid :use "sig" :alg "RS256"
            :n (b64url (.getModulus public))
            :e (b64url (.getPublicExponent public))}
           extra)))

(defn- ec-jwk
  [pair kid]
  (let [^ECPublicKey public
        (.getPublic pair)]
    {:kty "EC" :kid kid :crv "P-256"
     :x (b64url (.getAffineX (.getW public)))
     :y (b64url (.getAffineY (.getW public)))}))

(defn- in-seconds
  [seconds]
  (.getEpochSecond (.plusSeconds (Instant/now) seconds)))

(defn- token
  [pair kid & {:keys [alg issuer] :or {alg "RS256"}}]
  (let [algorithm
        (case alg
          "RS256" (Algorithm/RSA256 ^RSAPublicKey (.getPublic pair) ^RSAPrivateKey (.getPrivate pair))
          "RS512" (Algorithm/RSA512 ^RSAPublicKey (.getPublic pair) ^RSAPrivateKey (.getPrivate pair))
          "ES256" (Algorithm/ECDSA256 ^ECPublicKey (.getPublic pair) ^ECPrivateKey (.getPrivate pair)))]
    (.sign (-> (JWT/create)
               (.withKeyId ^String kid)
               (.withPayload ^java.util.Map {"iss" issuer
                                             "aud" "manager"
                                             "sub" "alice"
                                             "tenant" "acme"
                                             "exp" (in-seconds 300)}))
           algorithm)))

(defn- respond!
  [^HttpExchange exchange status body]
  (let [bytes
        (.getBytes (json/write-value-as-string body) StandardCharsets/UTF_8)]
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- start-provider!
  "A fake provider. (:state provider) holds what it publishes: :keys (the
   JWKS's keys), :status of the JWKS endpoint, :delay-ms before it answers,
   :issuer of the discovery document; :fetches counts JWKS requests."
  []
  (let [server
        (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)

        base
        (str "http://127.0.0.1:" (.getPort (.getAddress server)))

        state
        (atom {:keys [(rsa-jwk k1 "k1")] :status 200 :issuer (str base "/idp")})

        fetches
        (atom 0)

        routes
        {"/idp/.well-known/openid-configuration"
         (fn [exchange]
           (respond! exchange 200 {:issuer (:issuer @state)
                                   :authorization_endpoint (str base "/idp/auth")
                                   :token_endpoint (str base "/idp/token")
                                   :jwks_uri (str base "/idp/certs")}))

         "/idp/certs"
         (fn [exchange]
           (swap! fetches inc)
           (when-let [delay-ms (:delay-ms @state)]
             (Thread/sleep (long delay-ms)))
           (respond! exchange (:status @state) {:keys (:keys @state)}))}]
    (.setExecutor server (java.util.concurrent.Executors/newCachedThreadPool))
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (if-let [route
                                 (get routes (.getPath (.getRequestURI exchange)))]
                          (route exchange)
                          (respond! exchange 404 {})))))
    (.start server)
    {:server server
     :issuer (str base "/idp")
     :discovery-url (str base "/idp/.well-known/openid-configuration")
     :jwks-url (str base "/idp/certs")
     :state state
     :fetches fetches}))

(defmacro with-provider
  [[binding] & body]
  `(let [~binding (start-provider!)]
     (try
       ~@body
       (finally
         (.stop ^HttpServer (:server ~binding) 0)))))

(defn- jwt-settings
  [overrides]
  (merge support/settings
         {:auth-mode "jwt" :jwt-audience "manager" :jwt-secret nil :jwt-issuer nil :jwt-jwks-url nil}
         overrides))

(defn- subject
  [settings token]
  (:sub (auth/read-external settings token)))

(defmacro with-clock
  "Runs body with reload/now-ms reading the atom clock (milliseconds)."
  [clock & body]
  `(with-redefs [reload/now-ms (fn [] @~clock)]
     ~@body))

(deftest jwt-mode-finds-its-keys-and-issuer-through-a-discovery-url
  (with-provider [provider]
    (let [settings
          (jwt-settings {:jwt-discovery-url (:discovery-url provider)})]
      (is
       (nil? (auth/check-settings! settings)))
      (is
       (= "alice"
          (subject settings (token k1 "k1" :issuer (:issuer provider)))))
      (testing "the issuer comes from the document"
        (is
         (nil? (subject settings (token k1 "k1" :issuer "https://elsewhere.example")))))
      (testing "a configured issuer must be the document's"
        (is
         (= "alice"
            (subject (assoc settings :jwt-issuer (str (:issuer provider) "/")) (token k1 "k1" :issuer (:issuer provider))))))
      (testing "a configured issuer the document doesn't name refuses every token"
        (let [mismatched
              (assoc settings :jwt-issuer "https://other.example")]
          (is
           (nil? (subject mismatched (token k1 "k1" :issuer (:issuer provider)))))
          (is
           (thrown-with-msg? clojure.lang.ExceptionInfo #"names issuer .*, not MANAGER_JWT_ISSUER https://other.example"
                             (idp/discovery (idp/provider mismatched :jwt)))))))))

(deftest oidc-mode-takes-a-discovery-url-instead-of-the-issuer
  (with-provider [provider]
    (let [settings
          (merge support/settings
                 {:auth-mode "oidc" :oidc-issuer nil :oidc-discovery-url (:discovery-url provider) :oidc-client-id "manager"})]
      (is
       (nil? (oidc/check-settings! settings)))
      (is
       (= (str (:issuer provider) "/auth")
          (:authorization_endpoint (oidc/discovery settings))))
      (testing "with the issuer too, the document must name it"
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"not MANAGER_OIDC_ISSUER https://other.example"
                           (oidc/discovery (assoc settings :oidc-issuer "https://other.example")))))
      (testing "neither is refused at start"
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"MANAGER_OIDC_ISSUER or MANAGER_OIDC_DISCOVERY_URL"
                           (oidc/check-settings! (dissoc settings :oidc-discovery-url))))))))

(deftest a-reloaded-discovery-document-keeps-its-issuer
  (with-provider [provider]
    (let [clock
          (atom 0)

          settings
          (jwt-settings {:jwt-discovery-url (:discovery-url provider) :jwt-jwks-refresh-seconds 60})

          original
          (:issuer provider)]
      (with-clock clock
        (is
         (= original
            (idp/issuer (idp/provider settings :jwt))))
        (swap! (:state provider) assoc :issuer "https://hijacked.example")
        (reset! clock 61000)
        (is
         (= original
            (idp/issuer (idp/provider settings :jwt))))))))

(deftest a-rotated-key-is-picked-up-when-a-token-names-it
  (with-provider [provider]
    (let [clock
          (atom 0)

          settings
          (jwt-settings {:jwt-issuer (:issuer provider) :jwt-jwks-url (:jwks-url provider)})]
      (with-clock clock
        (is
         (= "alice"
            (subject settings (token k1 "k1" :issuer (:issuer provider)))))
        (swap! (:state provider) assoc :keys [(rsa-jwk k1 "k1") (rsa-jwk k2 "k2")])
        (swap! clock + 60000)
        (is
         (= "alice"
            (subject settings (token k2 "k2" :issuer (:issuer provider)))))
        (is
         (= 2
            @(:fetches provider)))))))

(deftest unknown-key-ids-reload-at-most-once-per-retry-interval
  (with-provider [provider]
    (let [clock
          (atom 0)

          settings
          (jwt-settings {:jwt-issuer (:issuer provider) :jwt-jwks-url (:jwks-url provider)})

          invented
          (fn [kid] (subject settings (token k2 kid :issuer (:issuer provider))))]
      (with-clock clock
        (is
         (nil? (invented "x1")))
        (swap! clock + 10000)
        (doseq [kid ["x2" "x3" "x4"]]
          (is
           (nil? (invented kid))))
        (is
         (= 2
            @(:fetches provider)))
        (testing "after the retry interval a new kid may reload again"
          (swap! (:state provider) assoc :keys [(rsa-jwk k2 "k2")])
          (swap! clock + 10000)
          (is
           (= "alice"
              (subject settings (token k2 "k2" :issuer (:issuer provider)))))
          (is
           (= 3
              @(:fetches provider))))))))

(deftest concurrent-requests-share-one-reload
  (with-provider [provider]
    (let [clock
          (atom 0)

          settings
          (jwt-settings {:jwt-issuer (:issuer provider) :jwt-jwks-url (:jwks-url provider)})

          _
          (with-clock clock
            (subject settings (token k1 "k1" :issuer (:issuer provider))))

          _
          (swap! (:state provider) assoc :keys [(rsa-jwk k1 "k1") (rsa-jwk k2 "k2")] :delay-ms 300)

          _
          (reset! clock 60000)

          rotated
          (token k2 "k2" :issuer (:issuer provider))

          start
          (CountDownLatch. 1)

          callers
          (doall (for [_ (range 20)]
                   (future
                     (.await start)
                     (subject settings rotated))))]
      (with-clock clock
        (.countDown start)
        (is
         (= (repeat 20 "alice")
            (map deref callers))))
      (is
       (= 2
          @(:fetches provider))))))

(deftest a-failed-or-empty-reload-keeps-the-last-good-keys
  (with-provider [provider]
    (let [clock
          (atom 0)

          settings
          (jwt-settings {:jwt-issuer (:issuer provider) :jwt-jwks-url (:jwks-url provider) :jwt-jwks-refresh-seconds 60})

          valid
          (token k1 "k1" :issuer (:issuer provider))]
      (with-clock clock
        (is
         (= "alice"
            (subject settings valid)))
        (doseq [[case-name change] [["the provider fails" {:status 500}]
                                    ["no keys" {:status 200 :keys []}]
                                    ["only unusable keys" {:status 200 :keys [{:kty "oct" :kid "s" :k "c2VjcmV0"}
                                                                              (rsa-jwk k2 "enc" :use "enc")
                                                                              (rsa-jwk k2 "ps" :alg "PS256")]}]]]
          (testing case-name
            (swap! (:state provider) merge change)
            (swap! clock + 61000)
            (let [before
                  @(:fetches provider)]
              (is
               (= "alice"
                  (subject settings valid)))
              (is
               (= (inc before)
                  @(:fetches provider))))))))))

(deftest a-local-jwks-file-is-read-again-when-it-changes
  (let [clock
        (atom 0)

        file
        (io/file (support/temp-dir) "jwks.json")

        publish!
        (fn [jwks modified]
          (spit file (json/write-value-as-string {:keys jwks}))
          (.setLastModified file modified))

        settings
        (jwt-settings {:jwt-issuer "https://idp.example" :jwt-jwks-file (.getPath file) :jwt-jwks-refresh-retry-seconds 0})]
    (publish! [(rsa-jwk k1 "k1")] 1000000)
    (with-clock clock
      (is
       (= "alice"
          (subject settings (token k1 "k1" :issuer "https://idp.example"))))
      (testing "a sidecar rotates the key"
        (publish! [(rsa-jwk k2 "k1")] 2000000)
        (swap! clock + 1)
        (is
         (= "alice"
            (subject settings (token k2 "k1" :issuer "https://idp.example"))))
        (is
         (nil? (subject settings (token k1 "k1" :issuer "https://idp.example")))))
      (testing "a half-written file keeps the last good keys"
        (spit file "{\"keys\": [")
        (.setLastModified file 3000000)
        (swap! clock + 1)
        (is
         (= "alice"
            (subject settings (token k2 "k1" :issuer "https://idp.example"))))))))

(deftest keys-without-alg-use-the-default-algorithm
  (with-provider [provider]
    (swap! (:state provider) assoc :keys [(dissoc (rsa-jwk k1 "ping") :alg)])
    (let [settings
          (jwt-settings {:jwt-issuer (:issuer provider)
                         :jwt-jwks-url (:jwks-url provider)
                         :jwt-jwks-refresh-retry-seconds 0})

          rs512
          (token k1 "ping" :alg "RS512" :issuer (:issuer provider))]
      (testing "RS256 when not configured"
        (is
         (nil? (subject settings rs512)))
        (is
         (= "alice"
            (subject settings (token k1 "ping" :issuer (:issuer provider))))))
      (testing "the configured one"
        (let [ping
              (assoc settings :jwt-jwks-default-algorithm "RS512")]
          (is
           (= "alice"
              (subject ping rs512)))
          (is
           (nil? (subject ping (token k1 "ping" :issuer (:issuer provider)))))))
      (testing "EC keys follow their curve"
        (swap! (:state provider) assoc :keys [(ec-jwk ec1 "ec")])
        (is
         (= "alice"
            (subject (assoc settings :jwt-jwks-default-algorithm "RS512")
                     (token ec1 "ec" :alg "ES256" :issuer (:issuer provider)))))))))

(deftest unsupported-default-algorithms-stop-the-manager
  (let [settings
        (jwt-settings {:jwt-issuer "https://idp.example" :jwt-jwks-url "https://idp.example/certs"})]
    (is
     (nil? (auth/check-settings! (assoc settings :jwt-jwks-default-algorithm "RS512"))))
    (doseq [algorithm ["none" "HS256" "PS256" "rs256"]]
      (is
       (thrown-with-msg? clojure.lang.ExceptionInfo #"MANAGER_JWT_JWKS_DEFAULT_ALGORITHM .* is not supported"
                         (auth/check-settings! (assoc settings :jwt-jwks-default-algorithm algorithm)))
       algorithm))
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"MANAGER_OIDC_JWKS_DEFAULT_ALGORITHM none is not supported"
                       (oidc/check-settings! {:auth-mode "oidc"
                                              :oidc-issuer "https://idp.example"
                                              :oidc-client-id "manager"
                                              :oidc-claims-source "id_token"
                                              :oidc-jwks-default-algorithm "none"})))))

(deftest operators-reload-keys-now
  (with-provider [provider]
    (let [settings
          (jwt-settings {:jwt-discovery-url (:discovery-url provider)})]
      (is
       (= {:discovery {:status :reloaded} :keys {:status :reloaded}}
          (auth/reload-keys! settings)))
      (swap! (:state provider) assoc :status 500)
      (is
       (= :failed
          (get-in (auth/reload-keys! settings) [:keys :status])))
      (is
       (= "alice"
          (subject settings (token k1 "k1" :issuer (:issuer provider))))))))
