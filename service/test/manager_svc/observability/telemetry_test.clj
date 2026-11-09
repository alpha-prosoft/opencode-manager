(ns manager-svc.observability.telemetry-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.security :as security]
            [edd.test.fixture.dal :as mock]
            [manager-svc.observability.api :as api]
            [manager-svc.observability.core :as core]
            [manager-svc.observability.edge :as edge]
            [manager-svc.observability.telemetry :as telemetry]
            [manager-svc.platform.api :as platform]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.test-support :as support]))

(def session-id
  #uuid "6f1c5a43-3333-4a52-9c1b-6a3f0b6a0001")

(defn- ctx
  []
  (security/register (core/register (support/ctx))))

(defn- report!
  [ctx ts body]
  (mock/apply-cmd (support/as-platform ctx)
                  {:commands (telemetry/commands (assoc (telemetry/parse-report body)
                                                        :session-id (str session-id)
                                                        :team-id "alpha"
                                                        :ts ts))}))

(defmacro with-session
  [visibility & body]
  `(with-redefs [sessions/get-session (fn [_# id#]
                                        (when (= session-id id#)
                                          {:id session-id :sub "alice" :visibility ~visibility :team-id "alpha"}))
                 platform/role (fn [_# user# _#] (when (= "bob" user#) :member))
                 platform/platform-admin? (constantly false)]
     ~@body))

(deftest activity-summarises-reports
  (with-session :team
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            _
            (do
              (report! ctx "t1" {"kind" "tool.start" "tool" "bash" "call_id" "c1" "args" "{}"})
              (report! ctx "t2" {"kind" "tool.end" "tool" "bash" "call_id" "c1" "duration_ms" 100})
              (report! ctx "t3" {"kind" "tool.start" "tool" "read" "call_id" "c2"})
              (report! ctx "t4" {"kind" "tool.end" "tool" "read" "call_id" "c2" "duration_ms" 300})
              (report! ctx "t5" {"kind" "tool.end" "tool" "bash" "call_id" "c3" "duration_ms" 300})
              (report! ctx "t6" {"kind" "tool.blocked" "tool" "bash" "call_id" "c4" "reason" "command matches rm"})
              (report! ctx "t7" {"kind" "event" "event" "file.edited" "details" "{\"file\":\"src/a.clj\"}"})
              (report! ctx "t8" {"kind" "tool.start" "tool" "edit" "call_id" "c5"}))

            activity
            (mock/query (support/as-user ctx "bob") {:query-id :get-session-activity :session-id session-id})]
        (is
         (= :busy
            (:state activity)))
        (is
         (= {:id 8 :ts "t8" :kind :tool.start :tool "edit" :call-id "c5" :duration-ms nil :detail {}}
            (:current activity)))
        (is
         (= [{:tool "bash" :calls 2 :avg-ms 200.0 :max-ms 300}
             {:tool "read" :calls 1 :avg-ms 300.0 :max-ms 300}]
            (:tools activity)))
        (is
         (= 1
            (:blocked activity)))
        (is
         (= ["src/a.clj"]
            (:files-edited activity)))
        (is
         (= "t8"
            (:last-seen activity)))
        (is
         (= [8 7 6 5 4 3 2 1]
            (mapv :id (:events activity))))
        (is
         (= #{{:team-id "alpha" :tool "bash" :kind :tool.end :n 2 :total-ms 400}
              {:team-id "alpha" :tool "read" :kind :tool.end :n 1 :total-ms 300}
              {:team-id "alpha" :tool "bash" :kind :tool.blocked :n 1 :total-ms 0}}
            (set (:tools (mock/query (support/as-platform ctx) {:query-id :get-observability-metrics})))))))))

(deftest idle-after-session-idle-event
  (with-session :private
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            _
            (do
              (report! ctx "t1" {"kind" "tool.start" "tool" "bash" "call_id" "c1"})
              (report! ctx "t2" {"kind" "tool.end" "tool" "bash" "call_id" "c1" "duration_ms" 5})
              (report! ctx "t3" {"kind" "event" "event" "session.idle" "details" "{}"}))]
        (is
         (= :idle
            (:state (mock/query (support/as-user ctx "alice") {:query-id :get-session-activity :session-id session-id}))))
        (testing "a team member does not see a private session"
          (is
           (= 404
              (try
                (mock/query (support/as-user ctx "bob") {:query-id :get-session-activity :session-id session-id})
                nil
                (catch Exception e
                  (:status (ex-data e)))))))))))

(deftest only-the-last-hundred-reports-are-listed
  (with-session :public
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            _
            (doseq [n (range 120)]
              (report! ctx (str "t" n) {"kind" "tool.end" "tool" "bash" "call_id" (str "c" n) "duration_ms" 1}))

            activity
            (mock/query (support/as-user ctx "carol") {:query-id :get-session-activity :session-id session-id})]
        (is
         (= 100
            (count (:events activity))))
        (is
         (= [120 21]
            [(:id (first (:events activity))) (:id (last (:events activity)))]))
        (is
         (= 120
            (:calls (first (:tools activity)))))))))

(deftest reports-are-validated
  (is
   (= 422
      (try
        (telemetry/parse-report {"kind" "tool.exploded"})
        nil
        (catch Exception e
          (:status (ex-data e))))))
  (is
   (= 422
      (try
        (telemetry/parse-report {"kind" "tool.end" "tool" (apply str (repeat 101 "x"))})
        nil
        (catch Exception e
          (:status (ex-data e))))))
  (is
   (= {:kind :tool.end :tool "bash" :call-id "c1" :conversation "s1" :duration-ms 12
       :detail {:title "ls" :output-bytes 40}}
      (telemetry/parse-report {"kind" "tool.end" "at" 1 "tool" "bash" "call_id" "c1" "conversation" "s1"
                               "duration_ms" 12 "title" "ls" "output_bytes" 40}))))

(deftest plugin-reports-are-authenticated-and-recorded
  (let [recorded
        (atom [])

        touched
        (atom [])

        post
        (fn [key body]
          (edge/ingest-telemetry {:settings support/settings}
                                 {:headers {"x-platform-key" key}
                                  :body (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8"))}))]
    (with-redefs [sessions/session-by-key (fn [_ k purposes] (when (and (= "session-key" k) (= #{"telemetry"} purposes)) {:id session-id :team-id "alpha"}))
                  api/record-telemetry! (fn [_ session report] (swap! recorded conj [(:id session) (:kind report)]))
                  sessions/touch! (fn [_ id] (swap! touched conj id))]
      (is
       (= 204
          (:status (post "session-key" "{\"kind\":\"tool.start\",\"tool\":\"bash\",\"call_id\":\"c1\"}"))))
      (is
       (= 204
          (:status (post "session-key" "{\"kind\":\"event\",\"event\":\"session.idle\"}"))))
      (is
       (= [[session-id :tool.start] [session-id :event]]
          @recorded))
      (is
       (= [session-id]
          @touched))
      (is
       (= 401
          (try
            (post "wrong" "{\"kind\":\"tool.start\"}")
            nil
            (catch Exception e
              (:status (ex-data e)))))))))

(deftest telemetry-is-authenticated-before-its-body-is-read
  (let [body
        (proxy [java.io.InputStream] []
          (read
            ([] (throw (AssertionError. "body read before authentication")))
            ([_] (throw (AssertionError. "body read before authentication")))
            ([_ _ _] (throw (AssertionError. "body read before authentication")))))]
    (with-redefs [sessions/session-by-key (constantly nil)]
      (is
       (= 401
          (try
            (edge/ingest-telemetry {:settings support/settings} {:headers {"x-platform-key" "nope"} :body body})
            nil
            (catch clojure.lang.ExceptionInfo e
              (:status (ex-data e)))))))))

(deftest sandbox-endpoints-are-only-on-the-sandbox-listener
  (let [paths
        (fn [routes] (set (keep :path routes)))]
    (is
     (= #{"/llm/v1/models" "/llm/v1/chat/completions" "/api/telemetry"}
        (paths core/sandbox-routes)))
    (is
     (= #{"/metrics"}
        (paths core/routes)))))
