(ns manager-svc.listeners-test
  "The public and the sandbox listener share no route: sandboxes reach only
   the gateways, result tools and telemetry, people never reach those."
  (:require [clojure.test :refer [deftest is]]
            [manager-svc.edge.server :as server]
            [manager-svc.module :as module]))

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
             :let [found (server/route routes (request method path))]
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
      (keep #(server/route (module/sandbox-routes) (apply request %)) public-requests))))

(deftest the-public-listener-serves-no-sandbox-route
  (is
   (= []
      (served-by (module/routes) (handlers (module/sandbox-routes)) sandbox-requests))))

(deftest every-sandbox-route-answers-on-the-sandbox-listener
  (is
   (= (count sandbox-requests)
      (count (keep #(server/route (module/sandbox-routes) (apply request %)) sandbox-requests)))))
