(ns manager-svc.credentials.gateway-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [lambda.util :as util]
            [manager-svc.credentials.api :as api]
            [manager-svc.credentials.fixtures :as fixtures]
            [manager-svc.credentials.gateway :as gateway]
            [manager-svc.observability.api :as observability-api]
            [manager-svc.platform.api :as platform-api]
            [manager-svc.sessions.api :as sessions-api])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.io ByteArrayInputStream ByteArrayOutputStream OutputStream]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- start-upstream
  "A fake MCP server: records requests and answers with (respond request)."
  [respond]
  (let [received
        (atom [])

        server
        (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [request
                              {:method (.getRequestMethod exchange)
                               :authorization (.getFirst (.getRequestHeaders exchange) "Authorization")
                               :body (slurp (.getRequestBody exchange))}]
                          (swap! received conj request)
                          ((respond request) exchange)))))
    (.start server)
    {:server server
     :received received
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/mcp")}))

(defn- json-reply
  [body]
  (fn [exchange]
    (let [bytes
          (.getBytes ^String (util/to-json body) "UTF-8")]
      (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
      (.add (.getResponseHeaders exchange) "Mcp-Session-Id" "upstream-session")
      (.sendResponseHeaders exchange 200 (alength bytes))
      (with-open [out (.getResponseBody exchange)]
        (.write out bytes)))))

(defn- spec-with
  [url]
  (update fixtures/tenant-spec :mcp-servers
          (fn [servers] (mapv #(assoc % :url url) servers))))

(def session
  {:id #uuid "11111111-1111-1111-1111-111111111111" :sub "alice" :tenant "acme" :team-id "alpha"})

(defn- request
  [method path body & {:keys [key] :or {key "good-key"}}]
  {:method method
   :path path
   :headers (cond-> {"content-type" "application/json" "accept" "application/json, text/event-stream"}
              key (assoc "x-platform-key" key))
   :body (ByteArrayInputStream. (.getBytes ^String (or body "") "UTF-8"))})

(defn- rpc
  [method params]
  (util/to-json {:jsonrpc "2.0" :id 7 :method method :params params}))

(defn- body-text
  [{:keys [body]}]
  (if (fn? body)
    (let [out
          (ByteArrayOutputStream.)]
      (body out)
      (.toString out "UTF-8"))
    body))

(defn- call
  [req]
  (try
    ((:handler (first gateway/sandbox-routes)) {:settings fixtures/settings} req)
    (catch clojure.lang.ExceptionInfo e
      {:status (:status (ex-data e)) :error (ex-message e)})))

(defn- with-gateway*
  [url f]
  (let [audit
        (atom [])]
    (with-redefs [platform-api/tenant-spec (fn [_ tenant] (when (= "acme" tenant) (spec-with url)))
                  sessions-api/session-by-key (fn [_ k purposes] (when (and (= "good-key" k) (= #{"tools"} purposes)) session))
                  sessions-api/mcp-servers (fn [_ _] ["jira"])
                  api/resolve-credential (fn [_ {:keys [tenant team-id user]} credential-id]
                                           (when (and (= "jira" credential-id) (= "alice" user)
                                                      (= ["acme" "alpha"] [tenant team-id]))
                                             {:value {:secret "pw" :username "alice"} :source :user}))
                  observability-api/append-audit! (fn [_ session-id type _ payload]
                                                    (swap! audit conj [session-id type payload]))]
      (f audit))))

(deftest gateway-authenticates-and-enforces-allow-lists
  (let [{:keys [server url received]}
        (start-upstream (fn [_] (json-reply {:jsonrpc "2.0" :id 7 :result {}})))]
    (try
      (with-gateway* url
        (fn [_audit]
          (is
           (= {:status 401 :error "Invalid gateway key"}
              (call (request :post "/mcp-gw/jira" (rpc "ping" {}) :key "bad"))))
          (is
           (= {:status 401 :error "Invalid gateway key"}
              (call (request :post "/mcp-gw/jira" (rpc "ping" {}) :key nil))))
          (is
           (= {:status 403 :error "This harness may not use MCP server github"}
              (call (request :post "/mcp-gw/github" (rpc "ping" {})))))
          (with-redefs [sessions-api/session-by-key (fn [_ _ _] (assoc session :team-id "beta"))
                        platform-api/tenant-spec (constantly (assoc-in (spec-with url) [:mcp-servers 0 :teams] ["alpha"]))]
            (is
             (= {:status 404 :error "MCP server jira is not available"}
                (call (request :post "/mcp-gw/jira" (rpc "ping" {}))))))
          (testing "servers are looked up in the session's tenant"
            (with-redefs [sessions-api/session-by-key (fn [_ _ _] (assoc session :tenant "globex"))]
              (is
               (= {:status 404 :error "MCP server jira is not available"}
                  (call (request :post "/mcp-gw/jira" (rpc "ping" {})))))))
          (testing "a tool outside the server's allow-list never reaches it"
            (let [response
                  (call (request :post "/mcp-gw/jira" (rpc "tools/call" {:name "delete_project" :arguments {}})))]
              (is
               (= 200
                  (:status response)))
              (is
               (= {:jsonrpc "2.0" :id 7 :error {:code -32001 :message "Tool delete_project is not allowed on Jira MCP"}}
                  (util/to-edn (body-text response))))
              (is
               (empty? @received))))))
      (finally
        (.stop server 0)))))

(deftest gateway-injects-the-credential-and-records-audit
  (let [{:keys [server url received]}
        (start-upstream (fn [_] (json-reply {:jsonrpc "2.0" :id 7 :result {:content [{:type "text" :text "ok"}]}})))]
    (try
      (with-gateway* url
        (fn [audit]
          (let [response
                (call (request :post "/mcp-gw/jira/" (rpc "tools/call" {:name "search" :arguments {:q "bug"}})))]
            (is
             (= 200
                (:status response)))
            (is
             (= {"content-type" "application/json" "mcp-session-id" "upstream-session"}
                (:headers response)))
            (is
             (= {:jsonrpc "2.0" :id 7 :result {:content [{:type "text" :text "ok"}]}}
                (util/to-edn (body-text response))))
            (is
             (= "Basic YWxpY2U6cHc="
                (:authorization (first @received))))
            (is
             (= [[(:id session) "mcp" {:server "jira" :tool "search" :arguments {:q "bug"} :credential "jira" :source :user}]]
                @audit)))))
      (finally
        (.stop server 0)))))

(deftest gateway-filters-listed-tools
  (let [{:keys [server url]}
        (start-upstream (fn [_] (json-reply {:jsonrpc "2.0" :id 7
                                             :result {:tools [{:name "search"} {:name "delete_project"} {:name "get_issue"}]}})))]
    (try
      (with-gateway* url
        (fn [_audit]
          (is
           (= ["search" "get_issue"]
              (mapv :name (get-in (util/to-edn (body-text (call (request :post "/mcp-gw/jira" (rpc "tools/list" {})))))
                                  [:result :tools]))))))
      (finally
        (.stop server 0)))))

(deftest gateway-without-a-credential-answers-with-an-rpc-error
  (let [{:keys [server url received]}
        (start-upstream (fn [_] (json-reply {})))]
    (try
      (with-gateway* url
        (fn [_audit]
          (with-redefs [sessions-api/session-by-key (fn [_ _ _] (assoc session :sub "bob"))]
            (is
             (= "Jira MCP needs your jira credential. Add it under Accounts in the platform."
                (get-in (util/to-edn (body-text (call (request :post "/mcp-gw/jira" (rpc "tools/call" {:name "search"})))))
                        [:error :message])))
            (is
             (empty? @received)))))
      (finally
        (.stop server 0)))))

(deftest gateway-streams-event-streams
  (let [release
        (CountDownLatch. 1)

        {:keys [server url]}
        (start-upstream
         (fn [_]
           (fn [exchange]
             (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
             (.sendResponseHeaders exchange 200 0)
             (with-open [out (.getResponseBody exchange)]
               (.write out (.getBytes "data: {\"n\":1}\n\n"))
               (.flush out)
               (.await release 5 TimeUnit/SECONDS)
               (.write out (.getBytes "data: {\"n\":2}\n\n"))))))]
    (try
      (with-gateway* url
        (fn [_audit]
          (let [response
                (call (request :get "/mcp-gw/jira" nil))

                seen
                (promise)

                out
                (proxy [OutputStream] []
                  (write
                    ([b] nil)
                    ([b off len]
                     (let [text
                           (String. ^bytes b (int off) (int len) "UTF-8")]
                       (when (string/includes? text "\"n\":1")
                         (deliver seen text))))))

                streaming
                (future ((:body response) out))]
            (is
             (= "text/event-stream"
                (get-in response [:headers "content-type"])))
            (testing "the first event arrives before the upstream finishes"
              (is
               (string/includes? (deref seen 5000 "timed out") "\"n\":1")))
            (.countDown release)
            @streaming)))
      (finally
        (.stop server 0)))))

(deftest an-unrecorded-call-is-not-forwarded
  (let [{:keys [server url received]}
        (start-upstream (fn [_] (json-reply {:jsonrpc "2.0" :id 7 :result {}})))]
    (try
      (with-gateway* url
        (fn [_audit]
          (with-redefs [observability-api/append-audit! (fn [& _] (throw (ex-info "edd down" {:status 500})))]
            (is
             (= "The platform could not record this call in the audit trail; try again"
                (get-in (util/to-edn (body-text (call (request :post "/mcp-gw/jira" (rpc "tools/call" {:name "search"})))))
                        [:error :message])))
            (is
             (empty? @received)))))
      (finally
        (.stop server 0)))))

(deftest platform-owned-sessions-use-team-and-tenant-values-only
  (let [{:keys [server url]}
        (start-upstream (fn [_] (json-reply {:jsonrpc "2.0" :id 7 :result {}})))

        asked-for
        (atom [])]
    (try
      (with-gateway* url
        (fn [_audit]
          (with-redefs [sessions-api/session-by-key (fn [_ _ _] (assoc session :sub "platform"))
                        api/resolve-credential (fn [_ owner-of _]
                                                 (swap! asked-for conj owner-of)
                                                 {:value {:secret "team"} :source :team})]
            (call (request :post "/mcp-gw/jira" (rpc "tools/call" {:name "search"})))
            (is
             (= [{:tenant "acme" :team-id "alpha" :user nil}]
                @asked-for)))))
      (finally
        (.stop server 0)))))

(deftest foreign-json-is-relayed-unchanged
  (let [{:keys [server url]}
        (start-upstream (fn [_]
                          (fn [exchange]
                            (let [bytes
                                  (.getBytes "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"tools\":[{\"name\":\"search\",\"description\":\":keyword-like #tag\"}]}}" "UTF-8")]
                              (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
                              (.sendResponseHeaders exchange 200 (alength bytes))
                              (with-open [out (.getResponseBody exchange)]
                                (.write out bytes))))))]
    (try
      (with-gateway* url
        (fn [_audit]
          (is
           (string/includes? (body-text (call (request :post "/mcp-gw/jira" (rpc "tools/list" {}))))
                             "\":keyword-like #tag\""))))
      (finally
        (.stop server 0)))))
