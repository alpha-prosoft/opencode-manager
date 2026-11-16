(ns manager-svc.platform-token-test
  "The platform token never leaves the manager: what goes into a sandbox and
   what the gateways send to LLM providers and MCP servers carries none,
   whatever the sandbox sent. (The sandbox listener: listeners-test; webhook
   test calls: webhooks.edge-test; session hosts: sessions.gateway-test.)"
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.credentials.fixtures :as credentials-fixtures]
            [manager-svc.credentials.gateway :as mcp-gateway]
            [manager-svc.http :as http]
            [manager-svc.observability.api :as observability]
            [manager-svc.observability.gateway :as llm-gateway]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.sandbox :as sandbox]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.signin :as signin]
            [manager-svc.test-support :as support])
  (:import [java.io ByteArrayInputStream]))

(def settings
  support/settings)

(def session
  {:id #uuid "11111111-1111-1111-1111-111111111111"
   :sub "alice"
   :tenant "acme"
   :team-id "alpha"
   :sandbox {:llm-connection "up" :llm-models ["gpt-4o"]}})

(defn- platform-token
  []
  (:token (signin/test-login settings "alice")))

(defn- stream
  [text]
  (ByteArrayInputStream. (.getBytes ^String text "UTF-8")))

(defn- sandbox-request
  "A sandbox's request with its key in header-name, carrying a platform
   token wherever a credential could ride."
  [method path header-name sandbox-key body]
  (let [token
        (platform-token)]
    {:method method
     :path path
     :headers {header-name sandbox-key
               "content-type" "application/json"
               "accept" "application/json"
               "cookie" (str "oc_auth=" token)
               "x-authorization" token
               "proxy-authorization" (str "Bearer " token)}
     :cookies {"oc_auth" token}
     :body (stream body)}))

(defn- sent-by
  "The requests f makes the manager send out, each answered 200."
  [f]
  (let [sent
        (atom [])]
    (with-redefs [http/request! (fn [request]
                                  (swap! sent conj request)
                                  {:status 200
                                   :headers {"content-type" "application/json"}
                                   :body (stream "{}")})]
      (f))
    @sent))

(deftest the-check-finds-a-platform-token
  (let [token
        (platform-token)]
    (is
     (= [token]
        (support/platform-tokens-in {:headers {"Authorization" (str "Bearer " token)}})))
    (is
     (= [token]
        (support/platform-tokens-in {:env {"TOKEN" token}})))))

(deftest edd-handlers-never-hold-the-token
  (is
   (= []
      (support/platform-tokens-in (auth/edd-user settings (platform-token))))))

(deftest a-sandbox-is-made-without-a-platform-token
  (let [made
        (sandbox/sandbox (assoc settings :sandbox-service-url "http://manager:8001")
                         {:session-id (:id session) :agent-id "a" :agent {} :prompt "p"
                          :harness {:llm {:connection "main" :models ["model-a"]} :mcp [:jenkins]}
                          :result-tool "submit_review" :password "pw"
                          :skills {"lint/SKILL.md" "skill"}})]
    (is
     (seq (:env made)))
    (is
     (= []
        (support/platform-tokens-in made)))))

(deftest the-llm-gateway-sends-the-provider-only-its-own-secret
  (let [sent
        (with-redefs [sessions/session-by-key (fn [_ k _] (when (= "session-key" k) session))
                      platform/llm-connection (fn [_ _ _] {:id "up" :base-url "http://llm.example/v1" :secret "up-secret"})
                      platform/team (fn [_ _ _] {:id "alpha"})
                      credentials/secret (fn [_ _ _] "sk-test")
                      observability/record-usage! (fn [_ _])]
          (sent-by #(llm-gateway/chat-completions {:settings settings}
                                                  (sandbox-request :post "/llm/v1/chat/completions"
                                                                   "authorization" "Bearer session-key"
                                                                   "{\"model\":\"gpt-4o\",\"messages\":[]}"))))]
    (is
     (= [{"Content-Type" "application/json" "Accept" "application/json" "Authorization" "Bearer sk-test"}]
        (mapv :headers sent)))
    (is
     (= []
        (support/platform-tokens-in sent)))))

(deftest the-mcp-gateway-sends-the-server-only-its-credential
  (let [sent
        (with-redefs [platform/tenant-spec (fn [_ _] credentials-fixtures/tenant-spec)
                      sessions/session-by-key (fn [_ k _] (when (= "session-key" k) session))
                      sessions/mcp-servers (fn [_ _] ["jira"])
                      credentials/resolve-credential (fn [& _] {:value {:secret "pw" :username "alice"} :source :user})
                      observability/append-audit! (fn [& _])]
          (sent-by #(mcp-gateway/forward {:settings credentials-fixtures/settings}
                                         (sandbox-request :post "/mcp-gw/jira"
                                                          "x-platform-key" "session-key"
                                                          "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}"))))]
    (is
     (= [{"content-type" "application/json" "accept" "application/json" "Authorization" "Basic YWxpY2U6cHc="}]
        (mapv :headers sent)))
    (is
     (= []
        (support/platform-tokens-in credentials-fixtures/settings sent)))))

(deftest a-platform-token-is-no-gateway-key
  (let [token
        (platform-token)

        refused
        (fn [f]
          (try
            (f)
            (catch clojure.lang.ExceptionInfo e
              ((juxt :status :message) (ex-data e)))))]
    (testing "sandbox keys are derived keys; the real lookup knows no session for a platform token"
      (is
       (nil?
        (sessions/session-by-key {:settings settings} token #{"llm" "tools" "mcp"}))))
    (with-redefs [sessions/session-by-key (fn [_ _ _] nil)]
      (is
       (= [401 "Invalid gateway key"]
          (refused #(llm-gateway/chat-completions {:settings settings}
                                                  (sandbox-request :post "/llm/v1/chat/completions"
                                                                   "authorization" (str "Bearer " token) "{}")))))
      (is
       (= [401 "Invalid gateway key"]
          (refused #(mcp-gateway/forward {:settings settings}
                                         (sandbox-request :post "/mcp-gw/jira" "x-platform-key" token "{}"))))))))
