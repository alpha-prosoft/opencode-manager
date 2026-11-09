(ns manager-svc.oidc
  "Browser login through any OpenID Connect provider (MANAGER_AUTH_MODE=oidc):
   the authorization code flow with PKCE (S256), state and nonce.

     GET /api/auth/oidc/login?next=/runs
         remembers state, nonce, PKCE verifier and next in a signed,
         HttpOnly cookie (10 min) and sends the browser to the provider
     GET /api/auth/oidc/callback?code&state
         checks state against the cookie, exchanges the code at the token
         endpoint, verifies the ID token (signature from the JWKS, issuer,
         audience = client id, nonce, expiry), merges the claims of the
         configured sources and runs them through the claims parser; the
         edge then issues the login cookie as in login mode

   The endpoints come from the discovery document, MANAGER_OIDC_DISCOVERY_URL
   or <MANAGER_OIDC_ISSUER>/.well-known/openid-configuration; its issuer must
   be MANAGER_OIDC_ISSUER when that is set. The document and the keys are
   reloadable (manager-svc.idp). The client secret is optional: without one
   the manager is a public client and PKCE alone protects the code.
   MANAGER_OIDC_CLAIMS_SOURCE lists
   where the claims come from, comma separated, later ones winning: id_token
   (always read), access_token (a JWT of the same issuer) and userinfo.
   A refused login leaves its message in the same cookie for the login page."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.auth :as auth]
            [manager-svc.config :as config]
            [manager-svc.hash :as hash]
            [manager-svc.http :as http]
            [manager-svc.idp :as idp])
  (:import [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [com.auth0.jwt.exceptions JWTVerificationException]
           [java.net URI URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]))

(def login-path "/api/auth/oidc/login")
(def callback-path "/api/auth/oidc/callback")
(def state-audience "opencode-manager-oidc")
(def state-ttl-seconds 600)
(def error-ttl-seconds 120)
(def leeway-seconds 60)
(def claims-sources #{"id_token" "access_token" "userinfo"})

(defn oidc-mode?
  [settings]
  (= "oidc" (:auth-mode settings)))

(defn state-cookie
  [settings]
  (auth/host-cookie settings "oc_oidc"))

(defn hint-cookie
  "Holds the ID token of the login, sent back to the provider as
   id_token_hint at logout so it ends the session without asking."
  [settings]
  (auth/host-cookie settings "oc_oidc_hint"))

(defn sources
  "The claims sources of MANAGER_OIDC_CLAIMS_SOURCE, in merge order."
  [{:keys [oidc-claims-source]}]
  (vec (distinct (remove string/blank? (map string/trim (string/split (str oidc-claims-source) #","))))))

(defn check-settings!
  [{:keys [oidc-issuer oidc-discovery-url oidc-client-id] :as settings}]
  (when (oidc-mode? settings)
    (when (or (and (string/blank? oidc-issuer) (string/blank? oidc-discovery-url))
              (string/blank? oidc-client-id))
      (throw (ex-info (str "MANAGER_AUTH_MODE=oidc requires MANAGER_OIDC_ISSUER or MANAGER_OIDC_DISCOVERY_URL, "
                           "and MANAGER_OIDC_CLIENT_ID")
                      {})))
    (idp/check-algorithm! settings :oidc)
    (when-let [unknown
               (seq (remove claims-sources (sources settings)))]
      (throw (ex-info (str "MANAGER_OIDC_CLAIMS_SOURCE: unknown " (string/join ", " unknown)
                           " (use id_token, access_token, userinfo)")
                      {})))))

(defn client-secret
  "MANAGER_OIDC_CLIENT_SECRET, else the trimmed MANAGER_OIDC_CLIENT_SECRET_FILE, else nil (a public client)."
  [{:keys [oidc-client-secret oidc-client-secret-file]}]
  (cond
    (not (string/blank? oidc-client-secret)) oidc-client-secret
    (not (string/blank? oidc-client-secret-file)) (not-empty (string/trim (slurp oidc-client-secret-file)))
    :else nil))

(defn public-origin
  [settings]
  (config/public-url settings (:domain settings)))

(defn redirect-url
  [{:keys [oidc-redirect-url] :as settings}]
  (or (not-empty oidc-redirect-url)
      (str (public-origin settings) callback-path)))

(defn post-logout-url
  "The login page on the origin of the redirect URL."
  [settings]
  (let [uri
        (URI. (redirect-url settings))]
    (str (.getScheme uri) "://" (.getRawAuthority uri) "/login")))

(def ^:private encoded-separators
  #"(?i)%(?:2f|5c|[01][0-9a-f]|7f)")

(defn safe-next
  "Where to go after logging in: a path on the manager's own origin (an
   absolute URL of that origin becomes its path), never the login page;
   anything else is home."
  [settings value]
  (let [origin
        (public-origin settings)

        path
        (if (and (string? value) (string/starts-with? value (str origin "/")))
          (subs value (count origin))
          value)]
    (if (and (string? path)
             (re-matches #"/(?![/\\])[^\x00-\x20\x7f\\]*" path)
             (not (re-find encoded-separators path))
             (not (re-find #"^/login(?:[/?#]|$)" path)))
      path
      "/")))

(defn- random-token
  []
  (let [bytes
        (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn code-challenge
  "The S256 PKCE challenge of verifier."
  [verifier]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.digest (MessageDigest/getInstance "SHA-256")
                            (.getBytes (str verifier) StandardCharsets/US_ASCII))))

(defn provider
  [settings]
  (idp/provider settings :oidc))

(defn discovery
  "The provider's discovery document (reloaded, the last good one kept)."
  [settings]
  (idp/discovery (provider settings)))

(defn- with-query
  [url params]
  (str url (if (string/includes? url "?") "&" "?") (http/query-string params)))

(defn authorization-request
  "{:location :state-token}: the provider's authorization URL for a browser
   logging in and returning to next, and what its cookie must remember."
  [{:keys [oidc-client-id oidc-scopes] :as settings} next]
  (let [document
        (discovery settings)

        state
        (random-token)

        nonce
        (random-token)

        verifier
        (random-token)]
    {:location (with-query (:authorization_endpoint document)
                 {:response_type "code"
                  :client_id oidc-client-id
                  :redirect_uri (redirect-url settings)
                  :scope oidc-scopes
                  :state state
                  :nonce nonce
                  :code_challenge (code-challenge verifier)
                  :code_challenge_method "S256"})
     :state-token (auth/sign settings state-audience state-ttl-seconds
                             {:state state
                              :nonce nonce
                              :verifier verifier
                              :next (safe-next settings next)})}))

(defn error-token
  "What the state cookie holds after a refused login: the message and where
   the person was going."
  [settings message next]
  (auth/sign settings state-audience error-ttl-seconds {:error message :next (safe-next settings next)}))

(defn read-state
  "The claims of a state cookie: {:state :nonce :verifier :next} of a login
   under way or {:error :next} of a refused one; nil when invalid."
  [settings token]
  (auth/read-token settings token state-audience))

(defn- form-encode
  [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- basic-authorization
  [client-id secret]
  (str "Basic " (.encodeToString (Base64/getEncoder)
                                 (.getBytes (str (form-encode client-id) ":" (form-encode secret))
                                            StandardCharsets/UTF_8))))

(defn- provider-error
  [response]
  (let [body
        (try
          (http/json-body response)
          (catch Exception _
            nil))]
    (some-> (or (:error_description body) (:error body)) str)))

(defn- refusal
  "A failed login whose reason the person may read."
  [message]
  (ex-info message {:login-refusal true}))

(defn- exchange!
  [{:keys [oidc-client-id] :as settings} document verifier code]
  (let [secret
        (client-secret settings)

        response
        (http/request! {:method :post
                        :url (:token_endpoint document)
                        :headers (cond-> {"Content-Type" "application/x-www-form-urlencoded"
                                          "Accept" "application/json"}
                                   secret (assoc "Authorization" (basic-authorization oidc-client-id secret)))
                        :body (http/query-string {:grant_type "authorization_code"
                                                  :code code
                                                  :redirect_uri (redirect-url settings)
                                                  :code_verifier verifier
                                                  :client_id oidc-client-id})
                        :timeout-seconds 15})]
    (when-not (http/ok? response)
      (throw (refusal (str "the provider refused the code (HTTP " (:status response)
                           (some->> (provider-error response) (str ": "))
                           ")"))))
    (http/json-body response)))

(defn- verified-claims
  "The claims of a token the provider signed (a key of its JWKS) for its
   issuer; with :audience it must name it, with :nonce carry it."
  [settings document what token {:keys [audience nonce]}]
  (when (string/blank? token)
    (throw (refusal (str "the provider sent no " what))))
  (try
    (let [decoded
          (JWT/decode ^String token)

          algorithm
          (try
            (idp/verification-algorithm (provider settings) decoded)
            (catch clojure.lang.ExceptionInfo e
              (throw (if (:unusable-key (ex-data e))
                       (refusal (str "the " what "'s " (ex-message e)))
                       e))))

          verifier
          (cond-> (-> (JWT/require algorithm)
                      (.withIssuer (into-array String [(:issuer document)]))
                      (.withClaimPresence "exp")
                      (.acceptLeeway leeway-seconds))
            audience (.withAudience (into-array String [audience]))
            nonce (.withClaim "nonce" ^String nonce))

          claims
          (auth/claims->map (.verify (.build verifier) ^String token))]
      (when (and audience (:azp claims) (not= audience (:azp claims)))
        (throw (refusal (str "the " what " was issued to another client"))))
      (dissoc claims :expires-at))
    (catch JWTVerificationException e
      (throw (refusal (str "the " what " is not valid (" (ex-message e) ")"))))))

(defn- source-claims
  [settings document tokens source]
  (case source
    "id_token" {}
    "access_token" (verified-claims settings document "access token" (:access_token tokens) {})
    "userinfo" (http/get-json (:userinfo_endpoint document) "The userinfo request"
                              {"Authorization" (str "Bearer " (:access_token tokens))})))

(defn provider-claims
  "{:claims :id-token}: the merged claims of the person the code belongs to
   and their ID token."
  [{:keys [oidc-client-id] :as settings} {:keys [verifier nonce]} code]
  (let [document
        (discovery settings)

        tokens
        (exchange! settings document verifier code)

        id-claims
        (verified-claims settings document "ID token" (:id_token tokens) {:audience oidc-client-id :nonce nonce})

        extra
        (map #(source-claims settings document tokens %) (sources settings))]
    (doseq [claims extra
            :when (and (:sub claims) (not= (:sub claims) (:sub id-claims)))]
      (throw (refusal "the provider's claims sources name different people")))
    {:claims (apply merge id-claims extra)
     :id-token (:id_token tokens)}))

(defn- refused
  [message next]
  {:refused message :next next})

(def provider-unreachable
  "Login failed: the identity provider could not be reached. Please try again later.")

(defn- provider-failure
  "What the login page says of a failed exchange: the reason this namespace
   or the provider gave. Anything else may name internal hosts, so it is
   only logged."
  [e]
  (if (:login-refusal (ex-data e))
    (str "Login failed: " (ex-message e))
    provider-unreachable))

(defn complete
  "The provider's answer to the callback (its query parameters), checked
   against the browser's state cookie: {:claims :id-token :next} of the
   person, or {:refused message :next}."
  [settings params state-token]
  (let [{:keys [state next] :as pending}
        (read-state settings state-token)

        next
        (or next "/")]
    (cond
      (not (and state (string? (:state params)) (hash/equal? state (:state params))))
      (refused "Login failed: the sign-in expired or was started in another browser. Please try again." next)

      (:error params)
      (refused (str "Login failed at the identity provider: " (or (:error_description params) (:error params))) next)

      (string/blank? (:code params))
      (refused "Login failed: the identity provider sent no authorization code" next)

      :else
      (try
        (assoc (provider-claims settings pending (:code params)) :next next)
        (catch Exception e
          (log/warn "OpenID Connect login failed" (ex-message e))
          (refused (provider-failure e) next))))))

(defn logout-location
  "Where the browser goes to end its session at the provider, nil when the
   provider has no end_session_endpoint or can't be reached."
  [{:keys [oidc-client-id] :as settings} id-token]
  (when-let [endpoint
             (try
               (:end_session_endpoint (discovery settings))
               (catch Exception e
                 (log/warn "OpenID Connect discovery failed at logout" (ex-message e))
                 nil))]
    (with-query endpoint {:client_id oidc-client-id
                          :id_token_hint (not-empty id-token)
                          :post_logout_redirect_uri (post-logout-url settings)})))
