(ns manager-svc.listeners-test
  "The public and the sandbox listener share no route: sandboxes reach only
   the gateways, result tools and telemetry, people never reach those."
  (:require [clojure.test :refer [deftest is testing]]
            [edd.undertow.core :as undertow]
            [lambda.util :as util]
            [manager-svc.edge.listener :as listener]
            [manager-svc.effects :as effects]
            [manager-svc.main :as main]
            [manager-svc.module :as module]
            [manager-svc.service :as service]
            [manager-svc.system :as system]
            [manager-svc.test-support :as support])
  (:import [java.net HttpURLConnection ServerSocket URL]
           [java.util.concurrent ExecutorService]))

(defn- request
  [method path]
  {:method method :path path :headers {} :cookies {}})

(defn- handlers
  [routes]
  (set (map :handler routes)))

(defn- served-by
  "The paths of requests that routes answer with one of handlers."
  [routes handler-set requests]
  (vec (for [[method path] requests
             :let [found (listener/route routes (request method path))]
             :when (contains? handler-set (:handler found))]
         [method path])))

(def sandbox-requests
  [[:get "/llm/v1/models"]
   [:post "/llm/v1/chat/completions"]
   [:post "/mcp"]
   [:post "/mcp/"]
   [:post "/mcp-gw/bitbucket"]
   [:get "/mcp-gw/bitbucket"]
   [:post "/api/telemetry"]])

(def public-requests
  [[:post "/api/query"]
   [:post "/api/command"]
   [:post "/api/auth/login"]
   [:post "/api/auth/logout"]
   [:get "/api/auth/personas"]
   [:put "/api/credentials/GITHUB"]
   [:put "/api/admin/credentials/GITHUB"]
   [:get "/api/sessions/11111111-1111-1111-1111-111111111111/audit/export"]
   [:get "/metrics"]
   [:get "/api/gateway/auth"]
   [:get "/sessions/bob/11111111-1111-1111-1111-111111111111"]
   [:get "/"]
   [:get "/healthz"]])

(deftest the-sandbox-listener-serves-only-sandbox-routes
  (is
   (= []
      (served-by (module/sandbox-routes) (handlers (module/routes)) public-requests)))
  (is
   (= []
      (keep #(listener/route (module/sandbox-routes) (apply request %)) public-requests))))

(deftest the-public-listener-serves-no-sandbox-route
  (is
   (= []
      (served-by (module/routes) (handlers (module/sandbox-routes)) sandbox-requests))))

(deftest every-sandbox-route-answers-on-the-sandbox-listener
  (is
   (= (count sandbox-requests)
      (count (keep #(listener/route (module/sandbox-routes) (apply request %)) sandbox-requests)))))

(defn- stop
  [ctx runtime]
  (doseq [component (vals (system/of ctx))
          :when (instance? ExecutorService component)]
    (.shutdownNow ^ExecutorService component))
  (effects/stop!)
  (undertow/stop runtime)
  (reset! effects/broker {}))

(defn- free-port
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- with-manager
  "Calls f with the runtime of the started manager: memory store, no jobs,
   free ports."
  [f]
  (let [ctx
        (service/init {} (assoc support/settings
                                :data-dir (str (support/temp-dir))
                                :public-port-internal 0
                                :public-bind-host "127.0.0.1"
                                :sandbox-port 0
                                :edd-port (free-port)
                                :sandbox-bind-host "127.0.0.1"))

        manager
        (main/start ctx)]
    (try
      (f manager)
      (finally
        (stop ctx manager)))))

(defn- send!
  "{:status :body :set-cookie} of a request to a listener of the manager."
  [manager listener method path & {:keys [body cookie headers]}]
  (let [connection
        ^HttpURLConnection (.openConnection (URL. (str "http://127.0.0.1:"
                                                       (undertow/port manager listener)
                                                       path)))]
    (.setRequestMethod connection method)
    (.setRequestProperty connection "X-Requested-With" "manager-web")
    (when cookie
      (.setRequestProperty connection "Cookie" cookie))
    (doseq [[header-name value] headers]
      (.setRequestProperty connection header-name value))
    (when body
      (.setDoOutput connection true)
      (with-open [out (.getOutputStream connection)]
        (.write out (.getBytes ^String body "UTF-8"))))
    (let [status
          (.getResponseCode connection)]
      {:status status
       :set-cookie (.getHeaderField connection "Set-Cookie")
       :body (some-> (if (< status 400) (.getInputStream connection) (.getErrorStream connection))
                     (slurp :encoding "UTF-8"))})))

(defn- status-of
  [manager listener [method path]]
  (:status (send! manager listener (.toUpperCase (name method)) path :body (when-not (= :get method) "{}"))))

(deftest the-started-listeners-share-nothing
  (with-manager
    (fn [manager]
      (testing "the manager listens for people, sandboxes and, on loopback, itself"
        (is
         (= #{:default :public :sandbox}
            (set (keys (:servers manager))))))
      (testing "sandboxes reach no public route"
        (is
         (= #{404}
            (set (map #(status-of manager :sandbox %) public-requests)))))
      (testing "people reach no sandbox route: a POST finds nothing, a GET the UI"
        (is
         (= #{404}
            (set (map #(status-of manager :public %) (filter #(= :post (first %)) sandbox-requests)))))
        (is
         (= "UI not built"
            (:body (send! manager :public "GET" "/llm/v1/models"))))
        (is
         (= 401
            (status-of manager :sandbox [:post "/api/telemetry"])))))))

(deftest the-public-listener-forwards-edd-requests
  (with-manager
    (fn [manager]
      (let [query
            "{\"query\":{\"query-id\":\"get-me\"},\"meta\":{\"user\":{\"id\":\"platform\"}}}"

            login
            (send! manager :public "POST" "/api/auth/login" :body "{\"username\":\"alice\"}")

            cookie
            (first (.split (str (:set-cookie login)) ";"))

            answered
            (send! manager :public "POST" "/api/query" :cookie cookie :body query)]
        (is
         (= 401
            (:status (send! manager :public "POST" "/api/query" :body query))))
        (is
         (= [200 "alice"]
            [(:status answered) (get-in (util/to-edn (:body answered)) [:result :sub])]))
        (testing "a failure keeps only its status and message"
          (is
           (= {:exception {:status 500 :message "Internal error"}}
              (util/to-edn (:body (send! manager :public "POST" "/api/query"
                                         :cookie cookie
                                         :body "{\"query\":{\"query-id\":\"no-such-query\"}}"))))))
        (testing "the sandbox listener doesn't"
          (is
           (= 404
              (:status (send! manager :sandbox "POST" "/api/query" :cookie cookie :body query)))))))))

(deftest no-sandbox-route-asks-for-a-person
  (is
   (= []
      (filterv :caller (module/sandbox-routes)))))

(defn- presented
  "The ways a request can carry token as a credential."
  [token]
  [{"Authorization" (str "Bearer " token)}
   {"X-Platform-Key" token}
   {"X-Authorization" token}
   {"X-Api-Key" token}
   {"Cookie" (str "oc_auth=" token)}])

(deftest a-platform-token-opens-nothing-on-the-sandbox-listener
  (with-manager
    (fn [manager]
      (let [login
            (send! manager :public "POST" "/api/auth/login" :body "{\"username\":\"alice\"}")

            token
            (second (re-find #"oc_auth=([^;]+)" (str (:set-cookie login))))

            statuses
            (for [[method path] sandbox-requests
                  headers (presented token)]
              (:status (send! manager :sandbox (.toUpperCase (name method)) path
                              :headers headers
                              :body (when-not (= :get method) "{}"))))]
        (testing "the token is a person's on the public listener"
          (is
           (= 200
              (:status (send! manager :public "POST" "/api/query"
                              :cookie (str "oc_auth=" token)
                              :body "{\"query\":{\"query-id\":\"get-me\"}}")))))
        (is
         (= #{401}
            (set statuses)))))))
