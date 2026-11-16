(ns manager-svc.tokens
  "The tokens the manager issues and reads. Each has its own audience, so
   none is accepted in another's place:

     platform  who a person is on the platform: the login cookie's token and
               all edd ever sees of a person. Only manager-svc.signin issues
               it; it never goes into a sandbox, to a session host, an MCP
               server or anyone else outside the manager
     service   the platform itself calling edd (workers, effects)
     handoff   a one-time, 60 s token carrying a person to a session host
     session   a session host's cookie, bound to that session; only
               forward-auth of that session host accepts it

   A person's token carries their identity: {:sub :email :name :tenant
   :teams {team-id :admin|:member} :tenant-admin :operator}."
  (:require [clojure.string :as string]
            [jsonista.core :as json]
            [manager-svc.hash :as hash]
            [manager-svc.names :as names]
            [manager-svc.signing :as signing])
  (:import [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [com.auth0.jwt.exceptions JWTVerificationException]
           [java.time Instant]
           [java.util Date UUID]))

(def handoff-param "oc_handoff")
(def platform-audience "opencode-manager")
(def service-audience "opencode-manager-service")
(def handoff-audience "opencode-manager-handoff")
(def session-audience "opencode-manager-session")
(def service-ttl-seconds 3600)
(def handoff-ttl-seconds 60)
(def session-ttl-seconds 3600)

(defn now-seconds
  []
  (.getEpochSecond (Instant/now)))

(defn- token-key
  "The JWT key, derived from the signing key so tokens and sandbox keys never
   share a key."
  [settings]
  (hash/hmac-hex (signing/signing-key settings) "purpose:jwt"))

(defn sign
  "A token of the manager for audience holding claims; values become
   strings, nil values are left out."
  [settings audience ttl-seconds claims]
  (let [builder
        (-> (JWT/create)
            (.withAudience (into-array String [audience]))
            (.withExpiresAt (Date/from (.plusSeconds (Instant/now) (long ttl-seconds)))))

        with-claims
        (reduce-kv (fn [b k v] (if (some? v) (.withClaim b (name k) (str v)) b))
                   builder
                   claims)]
    (.sign with-claims (Algorithm/HMAC256 ^String (token-key settings)))))

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
  "The claims of a token the manager signed for audience, else nil."
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

(defn- role-names
  [teams]
  (into {} (map (fn [[team-id role]] [team-id (name role)])) teams))

(defn- identity-claims
  [{:keys [sub email name tenant teams tenant-admin operator]}]
  {:sub sub
   :email email
   :name name
   :tenant tenant
   :teams (json/write-value-as-string (role-names teams))
   :tenant_admin (when tenant-admin "true")
   :operator (when operator "true")})

(defn- teams-of
  [encoded]
  (try
    (into (sorted-map)
          (map (fn [[team-id role]] [team-id (keyword role)]))
          (json/read-value (str encoded)))
    (catch Exception _
      {})))

(defn identity-of
  "The identity in the claims of a person's token."
  [{:keys [sub email name tenant teams tenant_admin operator]}]
  {:sub sub
   :email email
   :name name
   :tenant (not-empty tenant)
   :teams (teams-of teams)
   :tenant-admin (= "true" tenant_admin)
   :operator (= "true" operator)})

(defn- issue
  [settings identity audience ttl-seconds claims]
  (sign settings audience ttl-seconds (merge (identity-claims identity) claims)))

(defn platform-token
  "The platform token of identity, valid for ttl-seconds."
  [settings identity ttl-seconds]
  (issue settings identity platform-audience ttl-seconds {}))

(defn person
  "The identity a platform token carries, or nil."
  [settings token]
  (when-let [claims
             (read-token settings token platform-audience)]
    (when (names/person-name? (:sub claims))
      (identity-of claims))))

(defn service-token
  "The platform's own token for edd."
  [settings]
  (sign settings service-audience service-ttl-seconds {:sub names/platform-user}))

(defn service-token?
  [settings token]
  (some? (read-token settings token service-audience)))

(defonce ^:private used-handoffs
  (atom {}))

(defn handoff-token
  [settings identity session-id]
  (issue settings identity handoff-audience handoff-ttl-seconds {:sid session-id :jti (UUID/randomUUID)}))

(defn- first-use?
  [{:keys [jti expires-at]}]
  (let [now
        (now-seconds)

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
      (identity-of claims))))

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
