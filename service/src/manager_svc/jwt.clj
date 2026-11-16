(ns manager-svc.jwt
  "jwt mode (MANAGER_AUTH_MODE=jwt): people arrive with a token of the
   identity provider, as a bearer token or in the provider's cookie, and
   there is no login step. The public listener exchanges it at the door:
   the token is verified (signature, issuer, audience, expiry), its claims
   go through the claims parser and the request continues with a platform
   token minted for it. Nothing behind the listener sees a provider token."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.idp :as idp]
            [manager-svc.signin :as signin]
            [manager-svc.tokens :as tokens])
  (:import [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]))

(def request-seconds
  "How long the platform token of one request lives."
  300)

(defn jwt-mode?
  [settings]
  (= "jwt" (:auth-mode settings)))

(defn- provider
  [settings]
  (idp/provider settings :jwt))

(defn- algorithm
  [{:keys [jwt-secret] :as settings} decoded]
  (cond
    (idp/publishes-keys? (provider settings))
    (idp/verification-algorithm (provider settings) decoded)

    (not (string/blank? jwt-secret))
    (Algorithm/HMAC256 ^String jwt-secret)

    :else
    (throw (ex-info "JWT auth requires MANAGER_JWT_JWKS_URL, MANAGER_JWT_JWKS_FILE, MANAGER_JWT_DISCOVERY_URL or MANAGER_JWT_SECRET" {}))))

(defn provider-claims
  "Claims of an identity provider token with the configured (or
   discovered) issuer and audience, else nil."
  [{:keys [jwt-audience] :as settings} token]
  (when (and (not (string/blank? token))
             (not (string/blank? jwt-audience)))
    (try
      (let [issuer
            (or (idp/issuer (provider settings))
                (throw (ex-info "no issuer configured" {})))

            decoded
            (JWT/decode token)

            verifier
            (-> (JWT/require (algorithm settings decoded))
                (.withIssuer (into-array String [issuer]))
                (.withAudience (into-array String [jwt-audience]))
                (.build))

            claims
            (tokens/claims->map (.verify verifier ^String token))]
        (assoc claims :name (or (:name claims) (:preferred_username claims))))
      (catch Exception e
        (log/debug "Identity provider token refused:" (ex-message e))
        nil))))

(defn exchange
  "{:identity :token} of the person behind a provider token, the platform
   token living for the request; {:refused message} when the claims parser
   refuses them; nil for an invalid token and outside jwt mode."
  [settings token]
  (when-let [claims
             (when (jwt-mode? settings)
               (provider-claims settings token))]
    (signin/exchange settings claims request-seconds)))

(defn check-settings!
  [{:keys [jwt-issuer jwt-discovery-url jwt-audience jwt-secret] :as settings}]
  (when (jwt-mode? settings)
    (when (or (and (string/blank? jwt-issuer) (string/blank? jwt-discovery-url))
              (string/blank? jwt-audience)
              (and (not (idp/publishes-keys? (provider settings))) (string/blank? jwt-secret)))
      (throw (ex-info (str "MANAGER_AUTH_MODE=jwt requires MANAGER_JWT_AUDIENCE, MANAGER_JWT_ISSUER or "
                           "MANAGER_JWT_DISCOVERY_URL, and MANAGER_JWT_JWKS_URL, MANAGER_JWT_JWKS_FILE, "
                           "MANAGER_JWT_DISCOVERY_URL or MANAGER_JWT_SECRET")
                      {})))
    (idp/check-algorithm! settings :jwt)))
