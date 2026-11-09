(ns manager-svc.auth
  "Identity of HTTP requests and the keys the platform signs with.

   Tokens the manager issues, each with its own audience:
     login     the manager host's cookie (login mode), 12 h
     service   workers and the edge calling edd as the platform
     handoff   a one-time, 60 s token carrying a person to a session host
     session   a session host's cookie, bound to that session; only
               forward-auth of that session host accepts it

   In jwt mode people bring a token of the identity provider (bearer or the
   provider's cookie); its issuer and audience are checked and its claims go
   through the claims parser (manager-svc.claims). In oidc mode people log
   in through the provider (manager-svc.oidc) and get the login cookie, as
   in login mode. The username
   \"platform\" is the platform itself and is never a person.

   A person's identity, and so every token the manager issues for them,
   carries what the parser found at login: {:sub :email :name :tenant
   :teams {team-id :admin|:member} :tenant-admin :operator}."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [edd.core :as edd]
            [jsonista.core :as json]
            [manager-svc.claims :as claims]
            [manager-svc.files :as files]
            [manager-svc.hash :as hash]
            [manager-svc.idp :as idp]
            [manager-svc.names :as names])
  (:import [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [com.auth0.jwt.exceptions JWTVerificationException]
           [java.security SecureRandom]
           [java.time Instant]
           [java.util Base64 Date UUID]))

(def handoff-param "oc_handoff")
(def login-audience "opencode-manager")
(def handoff-audience "opencode-manager-handoff")
(def service-audience "opencode-manager-service")
(def session-audience "opencode-manager-session")
(def handoff-ttl-seconds 60)
(def session-ttl-seconds 3600)

(def platform-user
  names/platform-user)

(def platform-user?
  names/platform-user?)

(defn person-name?
  "A username a person may have."
  [username]
  (boolean (and (string? username)
                (re-matches names/username-pattern username)
                (not (platform-user? username)))))

(defn- secure?
  [settings]
  (= "https" (:public-scheme settings)))

(defn host-cookie
  "Name of a cookie of the manager host; __Host- on https, so session
   subdomains can't set or shadow it."
  [settings base-name]
  (if (secure? settings) (str "__Host-" base-name) base-name))

(defn login-cookie
  [settings]
  (host-cookie settings "oc_auth"))

(defn session-cookie
  [settings]
  (host-cookie settings "oc_session"))

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

(defn- teams-of
  [encoded]
  (try
    (into (sorted-map)
          (map (fn [[team-id role]] [team-id (keyword role)]))
          (json/read-value (str encoded)))
    (catch Exception _
      {})))

(defn identity-of
  "The identity in the claims of a token the manager issued."
  [settings {:keys [sub email name tenant teams tenant_admin operator]}]
  {:sub sub
   :email (or email (str sub "@" (:email-domain settings)))
   :name (or name sub)
   :tenant (not-empty tenant)
   :teams (if (map? teams) teams (teams-of teams))
   :tenant-admin (= "true" (str tenant_admin))
   :operator (= "true" (str operator))})

(defn- identity-claims
  [{:keys [tenant teams tenant-admin operator]}]
  (cond-> {:teams (json/write-value-as-string (into {} (map (fn [[k v]] [k (clojure.core/name v)])) teams))}
    tenant (assoc :tenant tenant)
    tenant-admin (assoc :tenant_admin "true")
    operator (assoc :operator "true")))

(defn sign
  "A token of the manager for audience holding claims; values become
   strings, nil values are left out."
  [settings audience ttl-seconds claims]
  (let [builder
        (-> (JWT/create)
            (.withAudience (into-array String [audience]))
            (.withExpiresAt (Date/from (.plusSeconds (Instant/now) (long ttl-seconds)))))

        with-claims
        (reduce-kv (fn [b k v] (if (some? v) (.withClaim b (clojure.core/name k) (str v)) b))
                   builder
                   claims)]
    (.sign with-claims (Algorithm/HMAC256 ^String (token-key settings)))))

(defn issue
  ([settings identity audience ttl-seconds]
   (issue settings identity audience ttl-seconds {}))
  ([settings {:keys [sub email name] :as identity} audience ttl-seconds claims]
   (sign settings audience ttl-seconds (merge {:sub sub :email email :name name}
                                              (identity-claims identity)
                                              claims))))

(defn- clojure-value
  [value]
  (cond
    (instance? java.util.Map value) (into {} (map (fn [[k v]] [(keyword k) (clojure-value v)])) value)
    (instance? java.util.List value) (mapv clojure-value value)
    :else value))

(defn- claim-value
  [claim]
  (or (.asString claim)
      (clojure-value (.as claim Object))))

(defn claims->map
  "The claims of a decoded token, keyword keys, with :expires-at."
  [decoded]
  (assoc (into {}
               (map (fn [[k claim]] [(keyword k) (claim-value claim)]))
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
  "The session host's token of identity for session-id; the gateway stops
   honouring it at the epoch second `until`, when the login it came from ends."
  [settings identity session-id until]
  (issue settings identity session-audience session-ttl-seconds {:sid (str session-id) :until until}))

(defn read-session-token
  "Claims of a session host's token when it was issued for session-id."
  [settings token session-id]
  (let [claims
        (read-token settings token session-audience)]
    (when (and claims (= session-id (:sid claims)))
      claims)))

(defn login-identity
  "{:identity} of a test login (login mode) for username, its claims taken
   from the dev users file and parsed like a token's; {:refused message}
   when the parser refuses them, nil for an invalid username."
  [settings username]
  (let [sub
        (string/lower-case (string/trim (str username)))]
    (when (person-name? sub)
      (claims/parse settings (claims/dev-claims settings sub)))))

(defn- jwks?
  [{:keys [jwt-jwks-url jwt-jwks-file jwt-discovery-url]}]
  (some (complement string/blank?) [jwt-jwks-url jwt-jwks-file jwt-discovery-url]))

(defn- external-algorithm
  [{:keys [jwt-secret] :as settings} decoded]
  (cond
    (jwks? settings)
    (idp/verification-algorithm (idp/provider settings :jwt) decoded)

    (not (string/blank? jwt-secret))
    (Algorithm/HMAC256 ^String jwt-secret)

    :else
    (throw (ex-info "JWT auth requires MANAGER_JWT_JWKS_URL, MANAGER_JWT_JWKS_FILE, MANAGER_JWT_DISCOVERY_URL or MANAGER_JWT_SECRET" {}))))

(defn read-external
  "Claims of an identity provider token with the configured (or
   discovered) issuer and audience, else nil."
  [{:keys [jwt-audience] :as settings} token]
  (when (and (not (string/blank? token))
             (not (string/blank? jwt-audience)))
    (try
      (let [issuer
            (or (idp/issuer (idp/provider settings :jwt))
                (throw (ex-info "no issuer configured" {})))

            decoded
            (JWT/decode token)

            verifier
            (-> (JWT/require (external-algorithm settings decoded))
                (.withIssuer (into-array String [issuer]))
                (.withAudience (into-array String [jwt-audience]))
                (.build))

            claims
            (claims->map (.verify verifier ^String token))]
        (assoc claims :name (or (:name claims) (:preferred_username claims))))
      (catch Exception e
        (log/debug "Identity provider token refused:" (ex-message e))
        nil))))

(defn reload-keys!
  "Loads the identity provider's discovery document and keys again now
   (jwt and oidc modes): {:discovery outcome :keys outcome}, nil in other
   modes or with a shared secret."
  [{:keys [auth-mode] :as settings}]
  (case auth-mode
    "jwt" (when (jwks? settings) (idp/reload-now! (idp/provider settings :jwt)))
    "oidc" (idp/reload-now! (idp/provider settings :oidc))
    nil))

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

(defn authenticate
  "{:identity} behind a person's token, {:refused message} when the claims
   parser refuses an identity provider token, nil for no valid token."
  [settings token]
  (if-let [claims
           (read-token settings token login-audience)]
    (when (person-name? (:sub claims))
      {:identity (identity-of settings claims)})
    (when-let [external
               (when (jwt-mode? settings)
                 (read-external settings token))]
      (claims/parse settings external))))

(defn person
  "The identity behind a person's token, or nil."
  [settings token]
  (:identity (authenticate settings token)))

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
    (let [{:keys [identity refused]}
          (authenticate settings token)]
      (cond
        refused
        (throw (ex-info refused {:status 403 :message refused}))

        identity
        (merge (select-keys identity [:email :name :tenant :teams :tenant-admin :operator])
               {:id (:sub identity)
                :role :user
                :roles [:user]
                :realm :main})))))

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
  "Refuses settings that would make authentication unsafe, and a claims
   parser that does not load. oidc mode's settings are checked by
   manager-svc.oidc/check-settings!."
  [{:keys [auth-mode jwt-issuer jwt-discovery-url jwt-audience jwt-secret] :as settings}]
  (claims/check-settings! settings)
  (case auth-mode
    "login" (log/warn "MANAGER_AUTH_MODE=login: anyone can log in as anyone without a password. Use it for local development only.")
    "oidc" nil
    "jwt" (do
            (when (or (and (string/blank? jwt-issuer) (string/blank? jwt-discovery-url))
                      (string/blank? jwt-audience)
                      (and (not (jwks? settings)) (string/blank? jwt-secret)))
              (throw (ex-info (str "MANAGER_AUTH_MODE=jwt requires MANAGER_JWT_AUDIENCE, MANAGER_JWT_ISSUER or "
                                   "MANAGER_JWT_DISCOVERY_URL, and MANAGER_JWT_JWKS_URL, MANAGER_JWT_JWKS_FILE, "
                                   "MANAGER_JWT_DISCOVERY_URL or MANAGER_JWT_SECRET")
                              {})))
            (idp/check-algorithm! settings :jwt))
    (throw (ex-info (str "Unknown MANAGER_AUTH_MODE " auth-mode) {}))))
