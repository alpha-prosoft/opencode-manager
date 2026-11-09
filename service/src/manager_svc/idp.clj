(ns manager-svc.idp
  "What the manager knows of an identity provider, for jwt mode (settings
   MANAGER_JWT_*) and oidc mode (MANAGER_OIDC_*): its discovery document
   and the JSON Web Key Set its tokens are verified with, both reloadable
   (manager-svc.reload).

   Discovery: MANAGER_<MODE>_DISCOVERY_URL (a full
   .well-known/openid-configuration URL), else in oidc mode
   <MANAGER_OIDC_ISSUER>/.well-known/openid-configuration. The document
   names the issuer tokens must carry; with MANAGER_<MODE>_ISSUER set too it
   must name that one, and once loaded a reloaded document must keep it.

   Keys: MANAGER_<MODE>_JWKS_FILE (a local file, e.g. written by a sidecar),
   else MANAGER_JWT_JWKS_URL (jwt mode), else the document's jwks_uri. Both
   the document and the keys are loaded again every
   MANAGER_<MODE>_JWKS_REFRESH_SECONDS (a file also when it changes); a
   token naming a key id the set lacks reloads it at once, at most every
   MANAGER_<MODE>_JWKS_REFRESH_RETRY_SECONDS. A failed reload, or one
   without usable keys, keeps the last good one.

   A key verifies tokens of its own algorithm only: its alg, else
   MANAGER_<MODE>_JWKS_DEFAULT_ALGORITHM when that fits the key type, else
   RS256 for RSA keys and the curve's for EC keys. Supported: RS256, RS384,
   RS512, ES256, ES384, ES512."
  (:require [clojure.string :as string]
            [manager-svc.config :as config]
            [manager-svc.http :as http]
            [manager-svc.reload :as reload])
  (:import [com.auth0.jwt.algorithms Algorithm]
           [com.auth0.jwt.interfaces DecodedJWT]
           [java.math BigInteger]
           [java.security AlgorithmParameters KeyFactory]
           [java.security.interfaces ECPublicKey RSAPublicKey]
           [java.security.spec ECGenParameterSpec ECParameterSpec ECPoint ECPublicKeySpec RSAPublicKeySpec]
           [java.util Base64]))

(def algorithms
  {"RS256" {:kty "RSA" :verifier (fn [key] (Algorithm/RSA256 ^RSAPublicKey key nil))}
   "RS384" {:kty "RSA" :verifier (fn [key] (Algorithm/RSA384 ^RSAPublicKey key nil))}
   "RS512" {:kty "RSA" :verifier (fn [key] (Algorithm/RSA512 ^RSAPublicKey key nil))}
   "ES256" {:kty "EC" :crv "P-256" :verifier (fn [key] (Algorithm/ECDSA256 ^ECPublicKey key nil))}
   "ES384" {:kty "EC" :crv "P-384" :verifier (fn [key] (Algorithm/ECDSA384 ^ECPublicKey key nil))}
   "ES512" {:kty "EC" :crv "P-521" :verifier (fn [key] (Algorithm/ECDSA512 ^ECPublicKey key nil))}})

(def ^:private curves
  {"P-256" "secp256r1"
   "P-384" "secp384r1"
   "P-521" "secp521r1"})

(defn- trimmed
  [issuer]
  (some-> issuer str (string/replace #"/+$" "") not-empty))

(defn- setting-name
  [mode k]
  (str "MANAGER_" (string/upper-case (name mode)) "_" (string/upper-case (string/replace (name k) "-" "_"))))

(defn- setting
  [settings mode k]
  (let [key
        (keyword (str (name mode) "-" (name k)))

        value
        (get settings key (get config/defaults key))]
    (if (string? value) (not-empty (string/trim value)) value)))

(defn check-algorithm!
  "Refuses a default algorithm the manager can't verify with."
  [settings mode]
  (when-let [value
             (setting settings mode :jwks-default-algorithm)]
    (when-not (contains? algorithms value)
      (throw (ex-info (str (setting-name mode :jwks-default-algorithm) " " value " is not supported (use "
                           (string/join ", " (sort (keys algorithms))) ")")
                      {})))))

(defn provider
  "The identity provider of mode (:jwt or :oidc) as the settings describe it."
  [settings mode]
  (let [issuer
        (setting settings mode :issuer)

        jwks-url
        (when (= :jwt mode) (setting settings mode :jwks-url))

        jwks-file
        (setting settings mode :jwks-file)

        discovery-url
        (or (setting settings mode :discovery-url)
            (when (and (= :oidc mode) issuer)
              (str (trimmed issuer) "/.well-known/openid-configuration")))]
    {:mode mode
     :issuer issuer
     :issuer-setting (setting-name mode :issuer)
     :discovery-url discovery-url
     :jwks-url jwks-url
     :jwks-file jwks-file
     :required (cond-> []
                 (= :oidc mode) (conj :authorization_endpoint :token_endpoint)
                 (not (or jwks-url jwks-file)) (conj :jwks_uri))
     :refresh-ms (* 1000 (setting settings mode :jwks-refresh-seconds))
     :retry-ms (* 1000 (setting settings mode :jwks-refresh-retry-seconds))
     :default-algorithm (setting settings mode :jwks-default-algorithm)}))

(defn- checked-document
  [{:keys [issuer issuer-setting required]} previous document]
  (let [named
        (trimmed (:issuer document))

        pinned
        (trimmed (:issuer previous))

        missing
        (filter #(string/blank? (str (get document %))) required)]
    (cond
      (not (map? document))
      (throw (ex-info "the discovery document is not a JSON object" {}))

      (nil? named)
      (throw (ex-info "the discovery document names no issuer" {}))

      (and issuer (not= (trimmed issuer) named))
      (throw (ex-info (str "the provider's discovery document names issuer " (:issuer document)
                           ", not " issuer-setting " " issuer)
                      {}))

      (and pinned (not= pinned named))
      (throw (ex-info (str "the discovery document now names issuer " (:issuer document) " instead of " pinned) {}))

      (seq missing)
      (throw (ex-info (str "the discovery document has no " (string/join ", " (map name missing))) {}))

      :else
      document)))

(defn- discovery-cell
  [{:keys [discovery-url refresh-ms retry-ms] :as provider}]
  (reload/cell [:discovery (select-keys provider [:discovery-url :issuer :required :refresh-ms :retry-ms])]
               (fn []
                 {:name (str "OpenID Connect discovery (" discovery-url ")")
                  :refresh-ms refresh-ms
                  :retry-ms retry-ms
                  :load (fn [previous]
                          (checked-document provider previous (http/get-json discovery-url "OpenID Connect discovery")))})))

(defn discovery
  "The provider's discovery document, nil when none is configured. Throws
   when it never loaded."
  [{:keys [discovery-url] :as provider}]
  (when discovery-url
    (reload/value! (discovery-cell provider))))

(defn issuer
  "The issuer tokens must carry: the discovery document's (which must match
   a configured one), else the configured one."
  [provider]
  (or (:issuer (discovery provider))
      (:issuer provider)))

(defn- unsigned
  ^BigInteger [value]
  (BigInteger. 1 ^bytes (.decode (Base64/getUrlDecoder) (str value))))

(defn- public-key
  [{:keys [kty n e crv x y]}]
  (case kty
    "RSA" (.generatePublic (KeyFactory/getInstance "RSA") (RSAPublicKeySpec. (unsigned n) (unsigned e)))
    "EC" (let [parameters
               (doto (AlgorithmParameters/getInstance "EC")
                 (.init (ECGenParameterSpec. (get curves crv))))

               spec
               (.getParameterSpec parameters ECParameterSpec)]
           (.generatePublic (KeyFactory/getInstance "EC") (ECPublicKeySpec. (ECPoint. (unsigned x) (unsigned y)) spec)))))

(defn- fits?
  [{:keys [kty crv]} alg]
  (let [{alg-kty :kty alg-crv :crv}
        (get algorithms alg)]
    (and (= kty alg-kty)
         (or (nil? alg-crv) (= crv alg-crv)))))

(defn- algorithm-of
  [{:keys [kty crv alg] :as jwk} default-algorithm]
  (cond
    alg alg
    (and default-algorithm (fits? jwk default-algorithm)) default-algorithm
    (= "RSA" kty) "RS256"
    (= "EC" kty) (some (fn [[name spec]] (when (= crv (:crv spec)) name)) algorithms)
    :else nil))

(defn usable-keys
  "[{:kid :alg :key}] of the signing keys of a JWKS the manager can verify
   with; others are left out."
  [jwks default-algorithm]
  (vec (for [jwk (when (map? jwks) (:keys jwks))
             :when (and (map? jwk) (not= "enc" (:use jwk)))
             :let [alg (algorithm-of jwk default-algorithm)]
             :when (and (contains? algorithms alg) (fits? jwk alg))
             :let [key (try
                         (public-key jwk)
                         (catch Exception _
                           nil))]
             :when key]
         {:kid (:kid jwk) :alg alg :key key})))

(defn- keyset-source
  [{:keys [jwks-file jwks-url] :as provider}]
  (cond
    jwks-file {:file jwks-file}
    jwks-url {:url jwks-url}
    :else {:url (:jwks_uri (discovery provider))}))

(defn- read-keys
  [{:keys [file url]} default-algorithm]
  (let [jwks
        (if file
          (http/read-json (slurp file))
          (http/get-json url "Fetching the JWKS"))

        usable
        (usable-keys jwks default-algorithm)]
    (when (empty? usable)
      (throw (ex-info "the key set has no usable signing keys" {})))
    usable))

(defn- keyset-cell
  [{:keys [refresh-ms retry-ms default-algorithm] :as provider}]
  (let [{:keys [file url] :as source}
        (keyset-source provider)]
    (reload/cell [:jwks source default-algorithm refresh-ms retry-ms]
                 (fn []
                   (cond-> {:name (str "JWKS (" (or file url) ")")
                            :refresh-ms refresh-ms
                            :retry-ms retry-ms
                            :load (fn [_] (read-keys source default-algorithm))}
                     file (assoc :stamp (reload/file-stamp file)))))))

(defn- key-named
  [keys kid]
  (if kid
    (some #(when (= kid (:kid %)) %) keys)
    (when (= 1 (count keys)) (first keys))))

(defn verification-algorithm
  "The algorithm verifying decoded with the provider's key it names (its
   kid, or the only key without one). An unknown kid reloads the keys
   first, at most every retry interval."
  [provider ^DecodedJWT decoded]
  (let [cell
        (keyset-cell provider)

        kid
        (.getKeyId decoded)

        found
        (or (key-named (reload/value! cell) kid)
            (when kid
              (reload/refresh! cell)
              (key-named (reload/value cell) kid)))]
    (when-not found
      (throw (ex-info (str "signing key is unknown (kid " kid ")") {:unusable-key true})))
    (when-not (= (:alg found) (.getAlgorithm decoded))
      (throw (ex-info (str "signed with " (.getAlgorithm decoded) ", but its key is for " (:alg found))
                      {:unusable-key true})))
    ((get-in algorithms [(:alg found) :verifier]) (:key found))))

(defn- outcome
  [f]
  (try
    (f)
    (catch Exception e
      {:status :failed :error (ex-message e)})))

(defn reload-now!
  "Loads the provider's discovery document and keys again now:
   {:discovery outcome :keys outcome}."
  [{:keys [discovery-url] :as provider}]
  (cond-> {}
    discovery-url (assoc :discovery (outcome #(reload/reload-now! (discovery-cell provider))))
    true (assoc :keys (outcome #(reload/reload-now! (keyset-cell provider))))))
