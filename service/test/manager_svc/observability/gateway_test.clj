(ns manager-svc.observability.gateway-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.observability.api :as api]
            [manager-svc.observability.gateway :as gateway]
            [manager-svc.observability.json :as json]
            [manager-svc.observability.upstream :as upstream]
            [manager-svc.platform.api :as platform]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.test-support :as support])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io BufferedReader ByteArrayInputStream InputStreamReader PipedInputStream PipedOutputStream]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def session
  {:id #uuid "6f1c5a43-4444-4a52-9c1b-6a3f0b6a0001"
   :sub "alice"
   :team-id "alpha"
   :run-id nil
   :sandbox {:llm-connection "up" :llm-models ["gpt-4o"]}})

(def first-chunk
  "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n")

(def last-chunks
  (str "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
       "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,"
       "\"prompt_tokens_details\":{\"cached_tokens\":2}}}\n\n"
       "data: [DONE]\n\n"))

(defn- write!
  [^HttpExchange exchange ^String text]
  (let [out (.getResponseBody exchange)]
    (.write out (.getBytes text StandardCharsets/UTF_8))
    (.flush out)))

(defn- upstream-handler
  [received release]
  (reify HttpHandler
    (handle [_ exchange]
      (let [body
            (json/read-str (slurp (.getRequestBody exchange) :encoding "UTF-8"))

            path
            (.getPath (.getRequestURI exchange))]
        (swap! received conj {:path path
                              :authorization (.getFirst (.getRequestHeaders exchange) "Authorization")
                              :body body})
        (cond

          (get body "stream")
          (do
            (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
            (.sendResponseHeaders exchange 200 0)
            (write! exchange first-chunk)
            (deref release 5000 nil)
            (write! exchange last-chunks))

          (= "bad" (get body "model"))
          (do
            (.sendResponseHeaders exchange 400 0)
            (write! exchange "not json"))

          :else
          (do
            (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
            (.sendResponseHeaders exchange 200 0)
            (write! exchange (json/write-str {"choices" [] "usage" {"prompt_tokens" 11 "completion_tokens" 4}}))))
        (.close exchange)))))

(defmacro with-upstream
  [[port received release] & body]
  `(let [~received (atom [])
         ~release (promise)
         server# (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
     (.createContext server# "/" (upstream-handler ~received ~release))
     (.setExecutor server# (java.util.concurrent.Executors/newCachedThreadPool))
     (.start server#)
     (try
       (let [~port (.getPort (.getAddress server#))]
         ~@body)
       (finally
         (deliver ~release true)
         (.stop server# 0)))))

(defmacro with-platform
  [{:keys [port used recorded connection]} & body]
  `(with-redefs [sessions/session-by-key (fn [_# k# purposes#] (when (and (= "session-key" k#) (= #{"llm"} purposes#)) session))
                 platform/platform-spec (constantly {:llm-connections
                                                     [(merge {:id "up"
                                                              :type "openai-compatible"
                                                              :base-url (str "http://127.0.0.1:" ~port "/v1/")
                                                              :secret "up-secret"}
                                                             ~connection)]})
                 platform/team (constantly {:id "alpha" :budget {:daily-tokens 1000}})
                 credentials/secret (fn [_# name#] (when (= "up-secret" name#) "sk-test"))
                 api/tokens-today (fn [& _#] @~used)
                 api/record-usage! (fn [_# call#] (swap! ~recorded conj call#))]
     ~@body))

(defn- request
  [body]
  {:method :post
   :path "/llm/v1/chat/completions"
   :headers {"authorization" "Bearer session-key" "accept" "text/event-stream"}
   :body (ByteArrayInputStream. (.getBytes (json/write-str body) StandardCharsets/UTF_8))})

(defn- status-of
  [f]
  (try
    (f)
    nil
    (catch Exception e
      (:status (ex-data e)))))

(def system
  {:settings support/settings})

(deftest streaming-is-relayed-chunk-by-chunk
  (with-upstream [port received release]
    (let [used (atom 0)
          recorded (atom [])]
      (with-platform {:port port :used used :recorded recorded}
        (let [response
              (gateway/chat-completions system (request {"model" "gpt-4o" "stream" true "messages" []}))

              in
              (PipedInputStream. 65536)

              out
              (PipedOutputStream. in)

              relay
              (future
                (try
                  ((:body response) out)
                  (finally
                    (.close out))))

              reader
              (BufferedReader. (InputStreamReader. in StandardCharsets/UTF_8))]
          (is
           (= "text/event-stream"
              (get-in response [:headers "Content-Type"])))
          (testing "the first chunk arrives while upstream is still holding the rest"
            (is
             (= (string/trim first-chunk)
                (.readLine reader)))
            (is
             (empty?
              @recorded)))
          (deliver release true)
          (let [rest-of-stream
                (slurp reader)]
            (deref relay 5000 nil)
            (is
             (string/includes? rest-of-stream "data: [DONE]")))
          (is
           (= [{:prompt-tokens 7 :completion-tokens 3 :cached-tokens 2 :status 200 :model "gpt-4o"
                :connection "up" :team-id "alpha" :username (:sub session)}]
              (mapv #(select-keys % [:prompt-tokens :completion-tokens :cached-tokens :status :model
                                     :connection :team-id :username])
                    @recorded)))
          (is
           (= {:path "/v1/chat/completions"
               :authorization "Bearer sk-test"
               :include-usage true}
              (let [{:keys [path authorization body]} (first @received)]
                {:path path
                 :authorization authorization
                 :include-usage (get-in body ["stream_options" "include_usage"])}))))))))

(deftest non-streaming-meters-the-usage-block
  (with-upstream [port _received _release]
    (let [used (atom 0)
          recorded (atom [])]
      (with-platform {:port port :used used :recorded recorded}
        (let [response
              (gateway/chat-completions system (request {"model" "gpt-4o" "messages" []}))]
          (is
           (= 200
              (:status response)))
          (is
           (= [11 4]
              ((juxt :prompt-tokens :completion-tokens) (first @recorded)))))))))

(deftest upstream-errors-are-passed-through-and-counted
  (with-upstream [port _received _release]
    (let [used (atom 0)
          recorded (atom [])]
      (with-redefs [gateway/sandbox (constantly {:llm-connection "up" :llm-models ["bad"] :team-id "alpha"})]
        (with-platform {:port port :used used :recorded recorded}
          (let [response
                (gateway/chat-completions system (request {"model" "bad" "messages" []}))]
            (is
             (= 400
                (:status response)))
            (is
             (= {"error" {"message" "not json"}}
                (json/read-str (:body response))))
            (is
             (= [400 0]
                ((juxt :status :prompt-tokens) (first @recorded))))))))))

(deftest budget-and-allow-list-are-enforced
  (with-upstream [port received _release]
    (let [used (atom 999)
          recorded (atom [])]
      (with-platform {:port port :used used :recorded recorded}
        (testing "under budget"
          (is
           (= 200
              (:status (gateway/chat-completions system (request {"model" "gpt-4o" "messages" []}))))))
        (testing "budget used up"
          (reset! used 1000)
          (is
           (= 429
              (status-of #(gateway/chat-completions system (request {"model" "gpt-4o" "messages" []}))))))
        (testing "model outside the harness allow-list"
          (reset! used 0)
          (is
           (= 403
              (status-of #(gateway/chat-completions system (request {"model" "gpt-5" "messages" []}))))))
        (testing "unknown key"
          (is
           (= 401
              (status-of #(gateway/chat-completions system (assoc-in (request {"model" "gpt-4o"})
                                                                     [:headers "authorization"] "Bearer nope"))))))
        (is
         (= 1
            (count @received)))))))

(deftest models-lists-the-allow-list
  (with-platform {:port 1 :used (atom 0) :recorded (atom [])}
    (is
     (= {"object" "list" "data" [{"id" "gpt-4o" "object" "model" "owned_by" "platform"}]}
        (json/read-str (:body (gateway/models system {:headers {"authorization" "Bearer session-key"}})))))))

(deftest copilot-sends-the-secret-as-bearer
  (with-upstream [port received _release]
    (with-platform {:port port :used (atom 0) :recorded (atom [])
                    :connection {:type "github-copilot" :base-url (str "http://127.0.0.1:" port "/v1") :secret "up-secret"}}
      (gateway/chat-completions system (request {"model" "gpt-4o" "messages" []}))
      (is
       (= [["/v1/chat/completions" "Bearer sk-test"]]
          (mapv (juxt :path :authorization) @received))))))

(deftest copilot-defaults-to-the-copilot-api
  (is
   (= "https://api.githubcopilot.com/chat/completions"
      (:url (upstream/upstream {:type :github-copilot} "gh-token")))))

(deftest usage-is-read-from-split-sse-lines
  (let [stream
        (ByteArrayInputStream. (.getBytes (str first-chunk last-chunks) StandardCharsets/UTF_8))]
    (is
     (= [7 3 2]
        (gateway/relay! stream (java.io.ByteArrayOutputStream.))))))

(deftest stopped-and-locked-sessions-lose-their-keys
  (with-platform {:port 1 :used (atom 0) :recorded (atom [])}
    (testing "stopped"
      (with-redefs [sessions/session-by-key (fn [& _] (assoc session :stopped-at "2026-11-08T10:00:00Z"))]
        (is
         (= 401
            (status-of #(gateway/chat-completions system (request {"model" "gpt-4o" "messages" []})))))))
    (testing "locked"
      (with-redefs [sessions/session-by-key (fn [& _] (assoc session :locked-at "2026-11-08T10:00:00Z"))]
        (is
         (= 423
            (status-of #(gateway/chat-completions system (request {"model" "gpt-4o" "messages" []})))))))))

(deftest the-budget-fails-closed
  (with-upstream [port received _release]
    (with-platform {:port port :used (atom 0) :recorded (atom [])}
      (with-redefs [api/tokens-today (fn [& _] (throw (ex-info "edd down" {:status 500})))]
        (is
         (= 503
            (status-of #(gateway/chat-completions system (request {"model" "gpt-4o" "messages" []})))))
        (is
         (empty?
          @received))))))

(deftest an-unreachable-connection-hides-internal-details
  (with-platform {:port 1 :used (atom 0) :recorded (atom [])}
    (let [failure
          (try
            (gateway/chat-completions system (request {"model" "gpt-4o" "messages" []}))
            nil
            (catch Exception e
              (ex-data e)))]
      (is
       (= {:status 502 :message "Could not reach LLM connection up"}
          (select-keys failure [:status :message]))))))

(deftest the-llm-route-allows-large-bodies
  (is
   (= gateway/max-body-bytes
      (:max-body-bytes (some #(when (= "/llm/v1/chat/completions" (:path %)) %) gateway/routes)))))

(deftest the-connection-test-calls-the-way-the-gateway-does
  (with-upstream [port received _release]
    (is
     (= {:ok true :model "gpt-4o"}
        (dissoc (upstream/test-connection {:id "cp" :type :github-copilot :base-url (str "http://127.0.0.1:" port "/v1") :models ["gpt-4o"]} "gh-token")
                :latency-ms)))
    (is
     (= [["/v1/chat/completions" "Bearer gh-token"]]
        (mapv (juxt :path :authorization) @received)))))
