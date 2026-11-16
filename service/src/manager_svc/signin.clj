(ns manager-svc.signin
  "Signing in: the one place a person's platform token is issued.

     a provider's claims ──claims parser──► identity ──► platform token

   However a person arrives, the claims parser (manager-svc.claims) reads
   their claims once, here, and the platform token (manager-svc.tokens)
   carries what it found. Everything after that knows the person only by
   that token (tokens/person):

     login mode  the test login, with the claims of the dev users file
     oidc mode   the provider's callback, with the claims manager-svc.oidc
                 verified
     jwt mode    the public listener, with the claims of the provider token
                 a request carries (manager-svc.jwt)"
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.claims :as claims]
            [manager-svc.names :as names]
            [manager-svc.tokens :as tokens]))

(def auth-modes
  #{"login" "oidc" "jwt"})

(defn exchange
  "{:identity :token} of the person a provider's claims describe, the
   platform token valid for ttl-seconds; {:refused message} when the claims
   parser refuses them."
  [settings claims ttl-seconds]
  (let [{:keys [identity refused]}
        (claims/parse settings claims)]
    (if identity
      {:identity identity
       :token (tokens/platform-token settings identity ttl-seconds)}
      {:refused refused})))

(defn login-seconds
  "How long a login lasts (MANAGER_AUTH_TTL_HOURS)."
  [settings]
  (* 3600 (:auth-ttl-hours settings)))

(defn login
  "exchange for a login: the token goes into the login cookie."
  [settings claims]
  (exchange settings claims (login-seconds settings)))

(defn test-login
  "login as username with its claims of the dev users file (login mode);
   nil for an invalid username."
  [settings username]
  (let [sub
        (string/lower-case (string/trim (str username)))]
    (when (names/person-name? sub)
      (login settings (claims/dev-claims settings sub)))))

(defn check-settings!
  "Refuses an unknown auth mode and a claims parser that does not load.
   What jwt and oidc mode need is checked by manager-svc.jwt and
   manager-svc.oidc."
  [{:keys [auth-mode] :as settings}]
  (when-not (contains? auth-modes auth-mode)
    (throw (ex-info (str "Unknown MANAGER_AUTH_MODE " auth-mode) {})))
  (claims/check-settings! settings)
  (when (= "login" auth-mode)
    (log/warn "MANAGER_AUTH_MODE=login: anyone can log in as anyone without a password. Use it for local development only.")))
