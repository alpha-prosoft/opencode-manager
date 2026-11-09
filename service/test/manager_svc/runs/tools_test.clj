(ns manager-svc.runs.tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.auth :as auth]
            [manager-svc.runs.executor :as executor]
            [manager-svc.runs.fixtures :as f]
            [manager-svc.http :as http]
            [manager-svc.runs.tools :as tools]
            [manager-svc.test-support :as support]
            [manager-svc.workers :as workers])
  (:import [java.io ByteArrayInputStream]))

(def run-id #uuid "11111111-1111-1111-1111-111111111111")

(def session-id (executor/session-id-for run-id))

(defn- mcp-key
  [id]
  (auth/derived-key support/settings "mcp" id))

(defn- request
  [body key]
  {:method :post
   :headers (cond-> {} key (assoc "x-platform-key" key))
   :body body})

(defn- post
  [system message key]
  (let [response
        (tools/handle system (request (ByteArrayInputStream. (.getBytes (http/write-json message) "UTF-8")) key))]
    (assoc response :json (http/read-json (:body response)))))

(defn- call
  [system tool arguments key]
  (get-in (post system {:jsonrpc "2.0" :id 7 :method "tools/call"
                        :params {:name tool :arguments arguments}}
                key)
          [:json :result]))

(deftest a-request-without-the-session-key-is-refused-before-its-body-is-read
  (f/with-fakes [store]
    (let [body
          (proxy [java.io.InputStream] []
            (read [& _] (throw (ex-info "body read" {}))))]
      (swap! store assoc session-id {:id session-id})
      (is
       (= 401
          (:status (tools/handle {:settings support/settings} (request body nil)))))
      (is
       (= 401
          (:status (tools/handle {:settings support/settings} (request body (mcp-key (random-uuid))))))))))

(deftest initialize-and-list-tools
  (f/with-fakes [store]
    (swap! store assoc session-id {:id session-id})
    (let [system
          {:settings support/settings}

          key
          (mcp-key session-id)

          send
          (fn [message] (post system message key))

          init
          (send {:jsonrpc "2.0" :id 1 :method "initialize"
                 :params {:protocolVersion "2025-03-26" :capabilities {}}})]
      (is
       (= {:jsonrpc "2.0"
           :id 1
           :result {:protocolVersion "2025-03-26"
                    :capabilities {:tools {:listChanged false}}
                    :serverInfo {:name "opencode-platform" :version "1.0.0"}
                    :instructions "Submit the result of the current platform activity."}}
          (:json init)))
      (is
       (= 202
          (:status (send {:jsonrpc "2.0" :method "notifications/initialized"}))))
      (is
       (= ["submit_review" "submit_implementation" "submit_document"]
          (map :name (get-in (send {:jsonrpc "2.0" :id 2 :method "tools/list"}) [:json :result :tools]))))
      (is
       (= -32601
          (get-in (send {:jsonrpc "2.0" :id 3 :method "resources/list"}) [:json :error :code]))))))

(deftest submissions
  (mock/with-mock-dal
    (f/with-fakes [store]
      (let [{:keys [ctx system]}
            (f/env)

            sid
            session-id

            kicks
            (atom 0)

            platform!
            (fn [cmd-id attrs]
              ((get-in system [:test/edd :command]) {:cmd-id cmd-id :id run-id :attrs attrs}))]
        (swap! store assoc session-id {:id session-id :run-id run-id :secret-key "pw"})
        (f/command! ctx "alice" {:cmd-id :start-run :id run-id
                                 :attrs {:team-id "alpha" :workflow-id "analyse" :inputs {:brief "Look at the architecture"}}})
        (with-redefs [workers/kick! #(swap! kicks inc)]
          (testing "nothing is accepted before the run waits for a result"
            (is
             (= "No run is waiting for a result from this session; nothing was submitted"
                (get-in (call system "submit_document" {:title "T" :markdown "M"} (mcp-key sid))
                        [:content 0 :text]))))
          (platform! :record-run-session {:session-id session-id :interactive false :models ["a"]})
          (platform! :record-run-conversation {:conversation "c1" :model "a"})
          (testing "the wrong tool is refused"
            (is
             (= "This run expects submit_document, not submit_review"
                (get-in (call system "submit_review" {:verdict "APPROVE" :summary "ok"} (mcp-key sid))
                        [:content 0 :text]))))
          (testing "invalid arguments are explained"
            (is
             (= {:content [{:type "text" :text "markdown: missing required key"}] :isError true}
                (call system "submit_document" {:title "T"} (mcp-key sid)))))
          (testing "a valid submission is recorded and the executor kicked"
            (is
             (= {:content [{:type "text" :text "Result accepted. The platform takes it from here; you are done."}]
                 :isError false}
                (call system "submit_document" {:title "T" :markdown "# Doc"} (mcp-key sid))))
            (is
             (= {:tool "submit_document" :payload {:title "T" :markdown "# Doc"}}
                (:submission (:run ((get-in system [:test/edd :query]) {:query-id :get-run-source :run-id run-id})))))
            (is
             (= 1
                @kicks)))
          (testing "a second submission is refused with the platform's reason"
            (is
             (= {:content [{:type "text" :text "A result was already submitted for this run"}] :isError true}
                (call system "submit_document" {:title "T2" :markdown "# Doc"} (mcp-key sid))))))))))
