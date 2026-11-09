(ns manager-svc.edge.api
  "Login (login mode's username login, oidc mode's provider login), logout,
   the auth mode the UI shows, and the UI's edd endpoints. /api/query forwards only the
   request's :query and /api/command only its :commands to edd, with the
   caller's token and fresh request ids; failures reach the caller as
   {:exception {:status :message}} without internal details."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [lambda.util :as util]
            [manager-svc.auth :as auth]
            [manager-svc.claims :as claims]
            [manager-svc.client :as client]
            [manager-svc.effects :as effects]
            [manager-svc.edge.server :as server]
            [manager-svc.errors :as errors]
            [manager-svc.http :as http]
            [manager-svc.oidc :as oidc]
            [manager-svc.workers :as workers]))

(defn caller-token
  "The verified token of a person's request, or nil."
  [settings {:keys [cookies headers]}]
  (auth/caller-token settings cookies (get headers "authorization")))

(defn person!
  "[token identity] of a person's request; 401 without a valid login."
  [settings request]
  (let [token
        (or (caller-token settings request)
            (errors/fail! 401 "Not authenticated"))

        identity
        (or (auth/person settings token)
            (errors/fail! 401 "Not authenticated"))]
    [token identity]))

(defn json-object!
  "The request's JSON body as a map; 422 with message when it is anything
   else."
  [request message]
  (let [text
        (server/body-text request)

        parsed
        (when-not (string/blank? text)
          (try
            (util/to-edn text)
            (catch Exception _
              nil)))]
    (if (map? parsed)
      parsed
      (errors/invalid! message))))

(defn- failure-of
  [body]
  (client/failure (try
                    (http/read-json body)
                    (catch Exception _
                      {:exception "edd answered with something other than JSON"}))))

(defn send!
  "Sends an edd request as the person token belongs to and answers with its
   response. A successful body passes through untouched; a failure becomes
   {:exception {:status :message}}."
  [settings path token request]
  (let [{:keys [status body]}
        (client/post-raw settings path token (util/to-json (client/envelope request)))

        failure
        (failure-of body)]
    {:status status
     :headers {"Content-Type" "application/json"}
     :body (if failure
             (util/to-json {:exception failure})
             body)}))

(defn- request-of
  [kind body]
  (case kind
    :query (when (map? (:query body)) {:query (:query body)})
    :commands (when (and (sequential? (:commands body)) (every? map? (:commands body)))
                {:commands (vec (:commands body))})))

(defn command-done!
  "What follows every person's command: effects settle so the caller reads
   its own writes, and reactive jobs run."
  []
  (effects/settle!)
  (workers/kick!))

(defn command!
  "Sends commands to edd as the person token belongs to, for the edge's own
   endpoints, and answers with edd's response once effects settled. A
   failure throws {:status :message}, which the listener answers as that
   HTTP status with {:detail message}."
  [settings token commands]
  (let [{:keys [body]}
        (client/post-raw settings "/command" token (util/to-json (client/envelope {:commands (vec commands)})))

        failure
        (failure-of body)]
    (command-done!)
    (when failure
      (throw (ex-info (:message failure) failure)))
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body body}))

(defn- parsed-body
  [request]
  (let [text
        (server/body-text request)]
    (try
      (util/to-edn text)
      (catch Exception _
        nil))))

(defn- forward
  [path kind]
  (fn [{:keys [settings]} request]
    (if-let [token
             (caller-token settings request)]
      (if-let [edd-request
               (request-of kind (parsed-body request))]
        (let [response
              (send! settings path token edd-request)]
          (when (= :commands kind)
            (command-done!))
          response)
        (server/error 400 (str "The body must hold " (name kind))))
      (server/error 401 "Not authenticated"))))

(defn- login-mode?
  [settings]
  (= "login" (:auth-mode settings)))

(defn- login-cookie-header
  [settings identity]
  (auth/set-cookie settings
                   (auth/login-cookie settings)
                   (auth/login-token settings identity)
                   (* 3600 (:auth-ttl-hours settings))))

(defn- state-cookie-header
  [settings value max-age]
  (auth/set-cookie settings (oidc/state-cookie settings) value max-age))

(defn- login
  [{:keys [settings]} request]
  (if-not (login-mode? settings)
    (server/error 404 "Login is handled by the identity provider")
    (let [{:keys [identity refused] :as result}
          (auth/login-identity settings (str (:username (server/read-json request))))]
      (cond
        identity
        (assoc-in (server/json identity) [:headers "Set-Cookie"] (login-cookie-header settings identity))

        refused
        (server/error 403 refused)

        (nil? result)
        (server/error 422 "Invalid username")))))

(defn- logout
  [{:keys [settings]} {:keys [cookies]}]
  (let [cleared
        (auth/set-cookie settings (auth/login-cookie settings) "" 0)

        provider-logout
        (when (oidc/oidc-mode? settings)
          (oidc/logout-location settings (get cookies (oidc/hint-cookie settings))))]
    (if provider-logout
      (assoc-in (server/json {:redirect provider-logout})
                [:headers "Set-Cookie"]
                [cleared (auth/set-cookie settings (oidc/hint-cookie settings) "" 0)])
      {:status 204
       :headers {"Set-Cookie" cleared}})))

(defn- personas
  [{:keys [settings]} _request]
  (if (login-mode? settings)
    (server/json (claims/personas settings))
    (server/error 404 "Not found")))

(defn- auth-config
  "{:mode \"login\"|\"oidc\"|\"jwt\"} and, once, the message of a refused
   provider login."
  [{:keys [settings]} {:keys [cookies]}]
  (let [refused
        (when (oidc/oidc-mode? settings)
          (:error (oidc/read-state settings (get cookies (oidc/state-cookie settings)))))]
    (cond-> (server/json (cond-> {:mode (:auth-mode settings)}
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
    (server/error 404 "Not found")
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
  "The provider's answer: a person the claims parser accepts gets the login
   cookie and finishes on the login page (which offers setup), anyone else
   returns to the login page with the reason."
  [{:keys [settings]} {:keys [query cookies]}]
  (if-not (oidc/oidc-mode? settings)
    (server/error 404 "Not found")
    (let [{:keys [claims id-token next refused]}
          (oidc/complete settings query (get cookies (oidc/state-cookie settings)))

          parsed
          (when claims
            (claims/parse settings claims))]
      (if-let [identity
               (:identity parsed)]
        (redirect (login-page next :complete? true)
                  [(login-cookie-header settings identity)
                   (state-cookie-header settings "" 0)
                   (auth/set-cookie settings (oidc/hint-cookie settings) id-token
                                    (* 3600 (:auth-ttl-hours settings)))])
        (refuse-provider-login settings (or refused (:refused parsed)) next)))))

(def routes
  [{:method :post :path "/api/auth/login" :handler login}
   {:method :post :path "/api/auth/logout" :handler logout}
   {:method :get :path "/api/auth/personas" :handler personas}
   {:method :get :path "/api/auth/config" :handler auth-config}
   {:method :get :path oidc/login-path :handler provider-login}
   {:method :get :path oidc/callback-path :handler provider-callback}
   {:method :post :path "/api/query" :handler (forward "/query" :query)}
   {:method :post :path "/api/command" :handler (forward "/command" :commands)}
   {:method :get :path "/healthz" :handler (fn [_ _] (server/json {:status "ok"}))}])
