(ns manager-svc.edge.api
  "Login, logout and the UI's edd endpoints. /api/query forwards only the
   request's :query and /api/command only its :commands to edd, with the
   caller's token and fresh request ids; failures reach the caller as
   {:exception {:status :message}} without internal details."
  (:require [lambda.util :as util]
            [manager-svc.auth :as auth]
            [manager-svc.client :as client]
            [manager-svc.effects :as effects]
            [manager-svc.edge.server :as server]
            [manager-svc.http :as http]
            [manager-svc.workers :as workers]))

(defn caller-token
  "The verified token of a person's request, or nil."
  [settings {:keys [cookies headers]}]
  (auth/caller-token settings cookies (get headers "authorization")))

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

(defn- login
  [{:keys [settings]} request]
  (if-not (login-mode? settings)
    (server/error 404 "Login is handled by the identity provider")
    (if-let [identity
             (auth/login-identity settings (str (:username (server/read-json request))))]
      (assoc-in (server/json identity)
                [:headers "Set-Cookie"]
                (auth/set-cookie settings
                                 (auth/login-cookie settings)
                                 (auth/login-token settings identity)
                                 (* 3600 (:auth-ttl-hours settings))))
      (server/error 422 "Invalid username"))))

(defn- logout
  [{:keys [settings]} _request]
  {:status 204
   :headers {"Set-Cookie" (auth/set-cookie settings (auth/login-cookie settings) "" 0)}})

(defn- personas
  [{:keys [settings]} _request]
  (if (login-mode? settings)
    (server/json (client/query settings {:query-id :get-personas}))
    (server/error 404 "Not found")))

(def routes
  [{:method :post :path "/api/auth/login" :handler login}
   {:method :post :path "/api/auth/logout" :handler logout}
   {:method :get :path "/api/auth/personas" :handler personas}
   {:method :post :path "/api/query" :handler (forward "/query" :query)}
   {:method :post :path "/api/command" :handler (forward "/command" :commands)}
   {:method :get :path "/healthz" :handler (fn [_ _] (server/json {:status "ok"}))}])
