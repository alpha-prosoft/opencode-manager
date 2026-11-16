(ns manager-svc.edge.login
  "Login (login mode's username login, oidc mode's provider login), logout
   and the auth mode the UI shows."
  (:require [clojure.tools.logging :as log]
            [manager-svc.claims :as claims]
            [manager-svc.cookies :as cookies]
            [manager-svc.edge.http :as edge-http]
            [manager-svc.http :as http]
            [manager-svc.oidc :as oidc]
            [manager-svc.signin :as signin]))

(defn- login-mode?
  [settings]
  (= "login" (:auth-mode settings)))

(defn- login-cookie-header
  [settings token]
  (cookies/set-cookie settings (cookies/login-cookie settings) token (signin/login-seconds settings)))

(defn- state-cookie-header
  [settings value max-age]
  (cookies/set-cookie settings (oidc/state-cookie settings) value max-age))

(defn- login
  [{:keys [settings]} request]
  (if-not (login-mode? settings)
    (edge-http/error 404 "Login is handled by the identity provider")
    (let [{:keys [identity token refused]}
          (signin/test-login settings (:username (edge-http/read-json request)))]
      (cond
        identity
        (assoc-in (edge-http/json identity) [:headers "Set-Cookie"] (login-cookie-header settings token))

        refused
        (edge-http/error 403 refused)

        :else
        (edge-http/error 422 "Invalid username")))))

(defn- logout
  [{:keys [settings]} {:keys [cookies]}]
  (let [cleared
        (cookies/set-cookie settings (cookies/login-cookie settings) "" 0)

        provider-logout
        (when (oidc/oidc-mode? settings)
          (oidc/logout-location settings (get cookies (oidc/hint-cookie settings))))]
    (if provider-logout
      (assoc-in (edge-http/json {:redirect provider-logout})
                [:headers "Set-Cookie"]
                [cleared (cookies/set-cookie settings (oidc/hint-cookie settings) "" 0)])
      {:status 204
       :headers {"Set-Cookie" cleared}})))

(defn- personas
  [{:keys [settings]} _request]
  (if (login-mode? settings)
    (edge-http/json (claims/personas settings))
    (edge-http/error 404 "Not found")))

(defn- auth-config
  "{:mode \"login\"|\"oidc\"|\"jwt\"} and, once, the message of a refused
   provider login."
  [{:keys [settings]} {:keys [cookies]}]
  (let [refused
        (when (oidc/oidc-mode? settings)
          (:error (oidc/read-state settings (get cookies (oidc/state-cookie settings)))))]
    (cond-> (edge-http/json (cond-> {:mode (:auth-mode settings)}
                              refused (assoc :error refused)))
      refused (assoc-in [:headers "Set-Cookie"] (state-cookie-header settings "" 0)))))

(defn- redirect
  [location set-cookies]
  {:status 302
   :headers {"Location" location
             "Cache-Control" "no-store"
             "Set-Cookie" set-cookies}})

(defn- login-page
  [next & {:keys [complete?]}]
  (str "/login?" (http/query-string (cond-> {:next next}
                                      complete? (assoc :complete "1")))))

(defn- refuse-provider-login
  [settings message next]
  (redirect (login-page next)
            [(state-cookie-header settings (oidc/error-token settings message next) oidc/error-ttl-seconds)]))

(defn- provider-login
  [{:keys [settings]} {:keys [query]}]
  (if-not (oidc/oidc-mode? settings)
    (edge-http/error 404 "Not found")
    (try
      (let [{:keys [location state-token]}
            (oidc/authorization-request settings (:next query))]
        (redirect location [(state-cookie-header settings state-token oidc/state-ttl-seconds)]))
      (catch Exception e
        (log/warn "OpenID Connect login could not start" (ex-message e))
        (refuse-provider-login settings
                               "Login is unavailable: the identity provider can't be reached. Please try again later."
                               (oidc/safe-next settings (:next query)))))))

(defn- provider-callback
  "The provider's answer: a person it names is signed in, gets the login
   cookie and finishes on the login page (which offers setup), anyone else
   returns to the login page with the reason."
  [{:keys [settings]} {:keys [query cookies]}]
  (if-not (oidc/oidc-mode? settings)
    (edge-http/error 404 "Not found")
    (let [{:keys [claims id-token next refused]}
          (oidc/complete settings query (get cookies (oidc/state-cookie settings)))

          signed-in
          (when claims
            (signin/login settings claims))]
      (if-let [token
               (:token signed-in)]
        (redirect (login-page next :complete? true)
                  [(login-cookie-header settings token)
                   (state-cookie-header settings "" 0)
                   (cookies/set-cookie settings (oidc/hint-cookie settings) id-token (signin/login-seconds settings))])
        (refuse-provider-login settings (or refused (:refused signed-in)) next)))))

(def routes
  [{:method :post :path "/api/auth/login" :handler login}
   {:method :post :path "/api/auth/logout" :handler logout}
   {:method :get :path "/api/auth/personas" :handler personas}
   {:method :get :path "/api/auth/config" :handler auth-config}
   {:method :get :path oidc/login-path :handler provider-login}
   {:method :get :path oidc/callback-path :handler provider-callback}])
