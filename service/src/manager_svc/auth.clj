(ns manager-svc.auth
  "Identity of HTTP requests and the keys the platform signs with.

   Tokens the manager issues, each with its own audience:
     login     the manager host's cookie (login mode), 12 h
     service   workers and the edge calling edd as the platform
     handoff   a one-time, 60 s token carrying a person to a session host
     session   a session host's cookie, bound to that session; only
               forward-auth of that session host accepts it

   In jwt mode people bring a token of the identity provider (bearer or the
   provider's cookie); its issuer and audience are checked and its subject
   must be a valid username. The username \"platform\" is the platform
   itself and is never a person."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [edd.core :as edd]
            [manager-svc.files :as files]
            [manager-svc.hash :as hash])
  (:import [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [com.auth0.jwt.exceptions JWTVerificationException]
           [com.auth0.jwk JwkProviderBuilder]
           [java.net URL]
           [java.security SecureRandom]
           [java.security.interfaces RSAPublicKey]
           [java.time Instant]
           [java.util Base64 Date UUID]))

(def handoff-param "oc_handoff")
(def login-audience "opencode-manager")
(def handoff-audience "opencode-manager-handoff")
(def service-audience "opencode-manager-service")
(def session-audience "opencode-manager-session")
(def handoff-ttl-seconds 60)
(def session-ttl-seconds 3600)
(def username-pattern #"^[a-z0-9][a-z0-9._-]{0,63}$")

(def platform-user
  "The platform's own username: owner of what platform runs start."
  "platform")

(defn platform-user?
  [username]
  (= platform-user username))

(defn person-name?
  "A username a person may have."
  [username]
  (boolean (and (string? username)
                (re-matches username-pattern username)
                (not (platform-user? username)))))

(defn- secure?
  [settings]
  (= "https" (:public-scheme settings)))

(defn login-cookie
  "Name of the manager host's login cookie; __Host- on https, so session
   subdomains can't set or shadow it."
  [settings]
  (if (secure? settings) "__Host-oc_auth" "oc_auth"))

(defn session-cookie
  [settings]
  (if (secure? settings) "__Host-oc_session" "oc_session"))

(defn set-cookie
  "A Set-Cookie value for a host-only cookie."
  [settings cookie-name value max-age]
  (str cookie-name "=" value
       "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" max-age
       (when (secure? settings) "; Secure")))

(defn- random-key
  []
  (let [bytes
        (byte-array 48)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (Base64/getUrlEncoder) bytes)))

(defonce ^:private stored-keys
  (atom {}))

(defn signing-key
  "MANAGER_SECRET_KEY, else a key generated once into the data directory."
  [{:keys [secret-key data-dir]}]
  (if-not (string/blank? secret-key)
    secret-key
    (let [location
          (str data-dir "/signing.key")]
      (or (get @stored-keys location)
          (let [stored
                (files/read-or-create! location random-key)]
            (swap! stored-keys assoc location stored)
            stored)))))

(defn- token-key
  "The JWT key, derived from the signing key so tokens and sandbox keys never
   share a key."
  [settings]
  (hash/hmac-hex (signing-key settings) "purpose:jwt"))

(defn- parse-uuid-str
  [value]
  (try
    (UUID/fromString (str value))
    (catch IllegalArgumentException _
      nil)))

(defn derived-key
  "A sandbox's key for purpose: <session-id>.<hex HMAC>."
  [settings purpose session-id]
  (str session-id "." (hash/hmac-hex (signing-key settings) (str purpose ":" session-id))))

(defn derived-key-owner
  "The session id (a UUID) key was derived for with one of purposes, or nil."
  [settings key purposes]
  (when-let [session-id
             (parse-uuid-str (first (string/split (str key) #"\." 2)))]
    (when (some #(hash/equal? (derived-key settings % session-id) key) purposes)
      session-id)))

(defn identity-of
  [settings {:keys [sub email name]}]
  {:sub sub
   :email (or email (str sub "@" (:email-domain settings)))
   :name (or name sub)})

(defn issue
  ([settings identity audience ttl-seconds]
   (issue settings identity audience ttl-seconds {}))
  ([settings {:keys [sub email name]} audience ttl-seconds claims]
   (let [builder
         (-> (JWT/create)
             (.withSubject sub)
             (.withClaim "email" ^String email)
             (.withClaim "name" ^String name)
             (.withAudience (into-array String [audience]))
             (.withExpiresAt (Date/from (.plusSeconds (Instant/now) (long ttl-seconds)))))

         with-claims
         (reduce-kv (fn [b k v] (.withClaim b (clojure.core/name k) (str v))) builder claims)]
     (.sign with-claims (Algorithm/HMAC256 ^String (token-key settings))))))

(defn- claims->map
  [decoded]
  (assoc (into {}
               (map (fn [[k claim]] [(keyword k) (.asString claim)]))
               (.getClaims decoded))
         :expires-at (some-> (.getExpiresAtAsInstant decoded) (.getEpochSecond))))

(defn read-token
  [settings token audience]
  (when-not (string/blank? token)
    (try
      (let [verifier
            (-> (JWT/require (Algorithm/HMAC256 ^String (token-key settings)))
                (.withAudience (into-array String [audience]))
                (.build))]
        (claims->map (.verify verifier ^String token)))
      (catch JWTVerificationException _
        nil))))

(defn login-token
  [settings identity]
  (issue settings identity login-audience (* 3600 (:auth-ttl-hours settings))))

(defn service-token
  "Token workers and the edge use to call edd as the platform itself."
  [settings]
  (issue settings {:sub platform-user :email "platform@internal" :name "Platform"} service-audience 3600))

(defonce ^:private used-handoffs
  (atom {}))

(defn handoff-token
  [settings identity session-id]
  (issue settings identity handoff-audience handoff-ttl-seconds {:sid session-id :jti (UUID/randomUUID)}))

(defn- first-use?
  [{:keys [jti expires-at]}]
  (let [now
        (.getEpochSecond (Instant/now))

        [before _]
        (swap-vals! used-handoffs
                    (fn [used]
                      (assoc (into {} (filter (fn [[_ expiry]] (> expiry now))) used)
                             jti expires-at)))]
    (and (some? jti) (not (contains? before jti)))))

(defn read-handoff
  "The identity of a handoff token for session-id; a token works once."
  [settings token session-id]
  (let [claims
        (read-token settings token handoff-audience)]
    (when (and claims (= session-id (:sid claims)) (first-use? claims))
      (identity-of settings claims))))

(defn session-token
  [settings identity session-id]
  (issue settings identity session-audience session-ttl-seconds {:sid session-id}))

(defn read-session-token
  "Claims of a session host's token when it was issued for session-id."
  [settings token session-id]
  (let [claims
        (read-token settings token session-audience)]
    (when (and claims (= session-id (:sid claims)))
      claims)))

(defn login-identity
  [settings username]
  (let [sub
        (string/lower-case (string/trim (str username)))]
    (when (person-name? sub)
      {:sub sub
       :email (str sub "@" (:email-domain settings))
       :name (string/trim username)})))

(def ^:private jwk-providers
  (memoize (fn [url] (.build (JwkProviderBuilder. (URL. url))))))

(defn- external-algorithm
  [{:keys [jwt-jwks-url jwt-secret]} decoded]
  (cond
    (not (string/blank? jwt-jwks-url))
    (let [jwk
          (.get (jwk-providers jwt-jwks-url) (.getKeyId decoded))]
      (Algorithm/RSA256 ^RSAPublicKey (.getPublicKey jwk) nil))

    (not (string/blank? jwt-secret))
    (Algorithm/HMAC256 ^String jwt-secret)

    :else
    (throw (ex-info "JWT auth requires MANAGER_JWT_JWKS_URL or MANAGER_JWT_SECRET" {}))))

(defn read-external
  "Claims of an identity provider token with the configured issuer and
   audience whose subject is a valid username, else nil."
  [{:keys [jwt-audience jwt-issuer] :as settings} token]
  (when (and (not (string/blank? token))
             (not (string/blank? jwt-audience))
             (not (string/blank? jwt-issuer)))
    (try
      (let [decoded
            (JWT/decode token)

            verifier
            (-> (JWT/require (external-algorithm settings decoded))
                (.withIssuer (into-array String [jwt-issuer]))
                (.withAudience (into-array String [jwt-audience]))
                (.build))

            claims
            (claims->map (.verify verifier ^String token))]
        (when (person-name? (:sub claims))
          (assoc claims :name (or (:name claims) (:preferred_username claims)))))
      (catch Exception _
        nil))))

(defn parse-cookies
  "Cookies of a Cookie header. A name sent more than once is left out, so a
   cookie set by another host can't shadow the real one."
  [header]
  (if (string/blank? header)
    {}
    (let [pairs
          (keep (fn [part]
                  (let [[k v] (string/split (string/trim part) #"=" 2)]
                    (when (and (seq k) v) [k v])))
                (string/split header #";"))

          repeated
          (set (keep (fn [[k n]] (when (> n 1) k)) (frequencies (map first pairs))))]
      (into {} (remove (comp repeated first)) pairs))))

(defn bearer
  "The token of an Authorization: Bearer header value."
  [authorization]
  (when (and authorization (string/starts-with? (string/lower-case authorization) "bearer "))
    (not-empty (string/trim (subs authorization 7)))))

(defn- jwt-mode?
  [settings]
  (= "jwt" (:auth-mode settings)))

(defn caller-token
  "The verified token a person's request carries, as edd should see it: the
   login cookie, else in jwt mode the bearer token or the provider's cookie."
  [settings cookies authorization]
  (let [login
        (get cookies (login-cookie settings))

        external
        (when (jwt-mode? settings)
          (or (bearer authorization) (get cookies (:jwt-cookie settings))))]
    (cond
      (read-token settings login login-audience) login
      (read-external settings external) external
      :else nil)))

(defn person
  "The identity behind a person's token, or nil."
  [settings token]
  (when-let [claims
             (or (read-token settings token login-audience)
                 (when (jwt-mode? settings)
                   (read-external settings token)))]
    (when (person-name? (:sub claims))
      (identity-of settings claims))))

(defn resolve-identity
  "Identity of a request from its cookies and Authorization header."
  [settings cookies authorization]
  (person settings (caller-token settings cookies authorization)))

(defn- header
  [req header-name]
  (some (fn [[k v]] (when (= header-name (string/lower-case (name k))) v))
        (:headers req)))

(defn edd-user
  "The edd user for a token in X-Authorization: the platform itself
   (non-interactive) or a person."
  [settings token]
  (if-let [service
           (read-token settings token service-audience)]
    {:id (:sub service)
     :email (:email service)
     :role :non-interactive
     :roles [:non-interactive]
     :realm :main}
    (when-let [{:keys [sub email name]}
               (person settings token)]
      {:id sub
       :email email
       :name name
       :role :user
       :roles [:user]
       :realm :main})))

(defn resolve-user
  [settings]
  (fn [{:keys [req]}]
    (or (edd-user settings (header req "x-authorization"))
        (throw (ex-info "Not authenticated" {:status 401 :message "Not authenticated"})))))

(defn register
  [ctx settings]
  (edd/reg-feature ctx :manager/auth
                   :resolve-user (resolve-user settings)))

(defn user-id
  "Username of the person behind an edd request."
  [ctx]
  (get-in ctx [:meta :user :id]))

(defn platform?
  [ctx]
  (= :non-interactive (get-in ctx [:meta :user :role])))

(defn check-settings!
  "Refuses settings that would make authentication unsafe."
  [{:keys [auth-mode jwt-issuer jwt-audience jwt-jwks-url jwt-secret]}]
  (case auth-mode
    "login" (log/warn "MANAGER_AUTH_MODE=login: anyone can log in as anyone without a password. Use it for local development only.")
    "jwt" (when (or (string/blank? jwt-issuer)
                    (string/blank? jwt-audience)
                    (and (string/blank? jwt-jwks-url) (string/blank? jwt-secret)))
            (throw (ex-info "MANAGER_AUTH_MODE=jwt requires MANAGER_JWT_ISSUER, MANAGER_JWT_AUDIENCE and MANAGER_JWT_JWKS_URL or MANAGER_JWT_SECRET" {})))
    (throw (ex-info (str "Unknown MANAGER_AUTH_MODE " auth-mode) {}))))
