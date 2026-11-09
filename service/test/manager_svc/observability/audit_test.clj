(ns manager-svc.observability.audit-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.security :as security]
            [edd.test.fixture.dal :as mock]
            [lambda.test.fixture.state :as state]
            [manager-svc.observability.audit :as audit]
            [manager-svc.observability.collector :as collector]
            [manager-svc.observability.core :as core]
            [manager-svc.client :as client]
            [manager-svc.observability.edge :as edge]
            [manager-svc.observability.json :as json]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.test-support :as support]))

(def session-id
  #uuid "6f1c5a43-1111-4a52-9c1b-6a3f0b6a0001")

(def sessions
  {session-id {:id session-id :sub "alice" :visibility :private :team-id "alpha"}})

(defn- ctx
  []
  (security/register (core/register (support/ctx))))

(defmacro with-domain
  [& body]
  `(with-redefs [sessions/get-session (fn [_# id#] (get sessions id#))
                 sessions/can-view? (fn [_# session# username#] (= username# (:sub session#)))]
     ~@body))

(defn- append!
  [ctx entries]
  (mock/handle-cmd (support/as-platform ctx)
                   {:commands (mapv (fn [[type key payload]]
                                      {:cmd-id :append-audit
                                       :id (audit/aggregate-id session-id)
                                       :attrs (audit/entry session-id "2026-11-08T10:00:00Z" type key payload)})
                                    entries)}))

(defn- tamper!
  [seq-no f]
  (swap! state/*dal-state*
         update-in [:realms :test :event-store]
         (fn [events]
           (mapv #(if (and (= (audit/aggregate-id session-id) (:id %)) (= seq-no (:event-seq %))) (f %) %)
                 events))))

(def entries
  [["conversation" "conversation:c1" {"id" "c1" "title" "Fix the build"}]
   ["message" "message:m1" {"id" "m1" "sessionID" "c1" "role" "user"}]
   ["tool" "part:p1" {"id" "p1" "type" "tool" "state" {"status" "completed"}}]])

(deftest entries-are-chained-from-genesis
  (with-domain
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            _
            (append! ctx entries)

            listed
            (mock/query (support/as-user ctx "alice") {:query-id :list-audit-events :session-id session-id})

            first-entry
            (first listed)]
        (is
         (= [1 2 3]
            (mapv :seq listed)))
        (is
         (= (audit/entry-hash audit/genesis "conversation" "conversation:c1" "{\"id\":\"c1\",\"title\":\"Fix the build\"}")
            (:hash first-entry)))
        (is
         (= (audit/entry-hash (:hash first-entry) "message" "message:m1"
                              "{\"id\":\"m1\",\"role\":\"user\",\"sessionID\":\"c1\"}")
            (:hash (second listed))))
        (is
         (= {"id" "c1" "title" "Fix the build"}
            (:payload first-entry)))))))

(deftest pages-follow-after-and-limit
  (with-domain
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            _
            (append! ctx entries)

            page
            (mock/query (support/as-user ctx "alice")
                        {:query-id :list-audit-events :session-id session-id :after 1 :limit 1})]
        (is
         (= ["message:m1"]
            (mapv :key page)))))))

(deftest verify-detects-tampering
  (with-domain
    (mock/with-mock-dal
      (let [ctx
            (support/as-user (ctx) "alice")

            _
            (append! ctx entries)

            verify
            #(mock/query ctx {:query-id :verify-audit :session-id session-id})

            intact
            (verify)]
        (is
         (= {:valid true :checked 3}
            (select-keys intact [:valid :checked])))
        (testing "a changed payload breaks the chain at that entry"
          (tamper! 2 #(assoc-in % [:attrs :body] "{\"id\":\"m1\",\"role\":\"admin\"}"))
          (is
           (= {:valid false :checked 1 :broken-at 2}
              (verify))))
        (testing "a recomputed hash still breaks the link of the next entry"
          (tamper! 2 #(assoc-in % [:attrs :hash]
                                (audit/entry-hash (get-in % [:attrs :prev-hash]) "message" "message:m1"
                                                  (get-in % [:attrs :body]))))
          (is
           (= {:valid false :checked 2 :broken-at 3}
              (verify))))))))

(deftest verify-detects-a-missing-tail
  (let [first-hash
        (audit/entry-hash audit/genesis "t" "k1" "{}")

        first-entry
        {:event-seq 1 :attrs {:type "t" :key "k1" :body "{}" :prev-hash audit/genesis :hash first-hash}}]
    (is
     (= {:valid true :checked 1 :head first-hash}
        (audit/verify-events {:count 1 :head first-hash} [first-entry])))
    (is
     (= {:valid false :checked 1 :broken-at 2}
        (audit/verify-events {:count 2 :head "later"} [first-entry])))
    (is
     (= {:valid false :checked 0 :broken-at 1}
        (audit/verify-events {:count 1 :head first-hash} [(assoc first-entry :event-seq 2)])))))

(deftest repeated-key-is-not-appended-twice
  (with-domain
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            _
            (append! ctx [(first entries) (first entries)])]
        (is
         (= 1
            (:count (mock/query (support/as-platform ctx)
                                {:query-id :get-audit-summary :session-id session-id}))))))))

(deftest summary-tracks-conversations
  (with-domain
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            _
            (append! ctx entries)

            summary
            (mock/query (support/as-platform ctx) {:query-id :get-audit-summary :session-id session-id})]
        (is
         (= 3
            (:count summary)))
        (is
         (= [{:id "c1" :title "Fix the build" :messages 1
              :started "2026-11-08T10:00:00Z" :last "2026-11-08T10:00:00Z"}]
            (:conversations summary)))))))

(deftest others-cannot-read-a-private-trail
  (with-domain
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            _
            (append! ctx entries)]
        (is
         (= 404
            (try
              (mock/query (support/as-user ctx "mallory") {:query-id :verify-audit :session-id session-id})
              nil
              (catch Exception e
                (:status (ex-data e))))))))))

(deftest classify-follows-opencode-events
  (is
   (nil?
    (audit/classify {"type" "message.part.delta" "properties" {}})))
  (is
   (nil?
    (audit/classify {"type" "message.updated"
                     "properties" {"info" {"id" "m1" "role" "assistant" "time" {}}}})))
  (is
   (= ["message" "message:m1" {"id" "m1" "role" "assistant" "time" {"completed" 5}}]
      (audit/classify {"type" "message.updated"
                       "properties" {"info" {"id" "m1" "role" "assistant" "time" {"completed" 5}}}})))
  (is
   (nil?
    (audit/classify {"type" "message.part.updated"
                     "properties" {"part" {"id" "p1" "type" "tool" "state" {"status" "running"}}}})))
  (is
   (= "session.idle"
      (first (audit/classify {"type" "session.idle" "properties" {"sessionID" "c1"}})))))

(deftest collector-skips-recorded-keys
  (let [entries
        (collector/new-entries session-id
                               #{"message:m1"}
                               [{"type" "message.updated" "properties" {"info" {"id" "m1" "role" "user"}}}
                                {"type" "message.updated" "properties" {"info" {"id" "m2" "role" "user"}}}
                                {"type" "conversation" "properties" {"id" "c1" "title" "T"}}
                                {"type" "conversation" "properties" {"id" "c1" "title" "T"}}])]
    (is
     (= ["message:m2" "conversation:c1"]
        (mapv :key entries)))))

(deftest a-failed-batch-keeps-the-keys-of-recorded-batches
  (let [sent
        (atom [])

        seen
        (atom #{})

        events
        (for [n (range 150)]
          {"type" "message.updated" "properties" {"info" {"id" (str "m" n) "role" "user"}}})]
    (with-redefs [collector/batch-size 100
                  client/command-no-settle (fn [_ & commands]
                                             (when (seq @sent)
                                               (throw (ex-info "edd down" {:status 500})))
                                             (swap! sent conj (count commands)))]
      (is
       (thrown? clojure.lang.ExceptionInfo
                (collector/record! {} session-id seen events)))
      (is
       (= [100]
          @sent))
      (is
       (= 100
          (count @seen)))
      (is
       (contains? @seen "message:m0"))
      (is
       (not (contains? @seen "message:m100"))))))

(deftest conversations-are-tracked-from-entry-attrs
  (is
   (= {:session-id (str session-id) :ts "t" :type "conversation" :key "conversation:c1"
       :body "{\"id\":\"c1\",\"title\":\"T\"}" :conversation-id "c1" :title "T"}
      (audit/entry session-id "t" "conversation" "conversation:c1" {"id" "c1" "title" "T"})))
  (is
   (= {:conversation-id "c1"}
      (select-keys (audit/entry session-id "t" "message" "message:m1" {"id" "m1" "sessionID" "c1"})
                   [:conversation-id :title]))))

(deftest raw-entries-export-the-hashed-body-verbatim
  (with-domain
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            payload
            {"#odd" {"z" 1 "a" ":keywordish"}}

            _
            (append! ctx [["tool" "part:p9" payload]])

            [entry]
            (mock/query (support/as-user ctx "alice") {:query-id :list-audit-events :session-id session-id :raw true})

            line
            (json/read-str (edge/export-line entry))]
        (is
         (= (json/canonical payload)
            (:body entry)))
        (is
         (= payload
            (get line "payload")))
        (is
         (= (:hash entry)
            (audit/entry-hash audit/genesis "tool" "part:p9" (:body entry))))))))
