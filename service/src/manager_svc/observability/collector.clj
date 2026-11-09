(ns manager-svc.observability.collector
  "Pulls OpenCode events from running sessions into their audit trails. A
   reconcile job starts one follower thread per running session and stops
   followers of sessions that stopped. A follower subscribes to /event,
   snapshots existing conversations, and appends every classified event
   whose key its trail does not hold yet; the keys already recorded are read
   from the trail when it starts, and after any failure."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.client :as client]
            [manager-svc.observability.audit :as audit]
            [manager-svc.observability.json :as json]
            [manager-svc.opencode :as opencode]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.time :as time])
  (:import [java.io BufferedReader Closeable InputStream InputStreamReader]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

(def reconnect-ms 3000)
(def batch-size 100)

(defn- get-json
  [base-url path password]
  (json/read-str (opencode/text base-url password path)))

(defn recorded-keys
  "Keys already in a session's audit trail."
  [settings session-id]
  (set (client/query settings {:query-id :list-audit-keys :session-id (str session-id)})))

(defn append!
  "Appends entries in batches; seen (an atom of keys) takes each batch's keys
   once that batch is recorded, so a failure never makes a recorded entry
   look new."
  [settings session-id seen entries]
  (doseq [batch (partition-all batch-size entries)]
    (apply client/command-no-settle settings
           (map (fn [attrs]
                  {:cmd-id :append-audit :id (audit/aggregate-id session-id) :attrs attrs})
                batch))
    (swap! seen into (map :key batch))))

(defn snapshot-events
  "Existing conversations of a session as OpenCode events."
  [base-url password]
  (mapcat (fn [conversation]
            (let [id
                  (get conversation "id")

                  messages
                  (get-json base-url (str "/session/" id "/message") password)]
              (concat
               [{"type" "conversation" "properties" conversation}]
               (mapcat (fn [message]
                         (cons {"type" "message.updated" "properties" {"info" (get message "info")}}
                               (map (fn [part] {"type" "message.part.updated" "properties" {"part" part}})
                                    (get message "parts"))))
                       messages))))
          (get-json base-url "/session" password)))

(defn- classify
  "Snapshot conversations are recorded under type conversation, as in Python."
  [event]
  (if (= "conversation" (get event "type"))
    (let [conversation (get event "properties")]
      ["conversation" (str "conversation:" (get conversation "id")) conversation])
    (audit/classify event)))

(defn new-entries
  "Audit entry attrs for the events whose keys are not in seen, each key once."
  [session-id seen events]
  (first
   (reduce (fn [[entries seen] event]
             (let [[entry-type entry-key payload] (classify event)]
               (if (and entry-type (not (contains? seen entry-key)))
                 [(conj entries (audit/entry session-id (time/now) entry-type entry-key payload)) (conj seen entry-key)]
                 [entries seen])))
           [[] seen]
           events)))

(defn record!
  [settings session-id seen events]
  (let [entries
        (new-entries session-id @seen events)]
    (when (seq entries)
      (append! settings session-id seen entries))))

(defn- follow-once!
  [{:keys [settings] :as system} session-id seen follower]
  (let [session
        (sessions/get-session system session-id)

        password
        (:secret-key session)

        base-url
        (sessions/session-base-url system session-id)

        {:keys [status body]}
        (opencode/events base-url password)]
    (with-open [in ^InputStream body
                reader (BufferedReader. (InputStreamReader. in StandardCharsets/UTF_8))]
      (when (>= status 400)
        (throw (ex-info "OpenCode event stream refused" {:status status})))
      (swap! follower assoc :stream in)
      (record! settings session-id seen (snapshot-events base-url password))
      (loop []
        (when-let [line (.readLine reader)]
          (when (string/starts-with? line "data: ")
            (when-let [event (json/read-str (subs line 6))]
              (record! settings session-id seen [event])))
          (recur))))))

(defn- pause!
  [follower]
  (try
    (Thread/sleep (long reconnect-ms))
    (catch InterruptedException _
      (swap! follower assoc :stopped true))))

(defn- follow!
  [system session-id follower]
  (while (not (:stopped @follower))
    (try
      (let [seen
            (atom (recorded-keys (:settings system) session-id))]
        (follow-once! system session-id seen follower))
      (catch InterruptedException _
        (swap! follower assoc :stopped true))
      (catch Exception e
        (when-not (:stopped @follower)
          (log/debug "Audit stream interrupted" session-id (ex-message e)))))
    (when-not (:stopped @follower)
      (pause! follower))))

(defn- stop-follower!
  [follower]
  (swap! follower assoc :stopped true)
  (when-let [stream (:stream @follower)]
    (try
      (.close ^Closeable stream)
      (catch Exception _ nil)))
  (some-> (:future @follower) future-cancel))

(defn reconcile!
  "Starts followers for running sessions and stops the others."
  [system]
  (let [followers
        (:observability/followers system)

        running
        (set (map str (sessions/running-session-ids system)))]
    (doseq [session-id running
            :when (not (contains? @followers session-id))]
      (let [follower
            (atom {})]
        (swap! follower assoc :future (future (follow! system session-id follower)))
        (swap! followers assoc session-id follower)))
    (doseq [[session-id follower] @followers
            :when (not (contains? running session-id))]
      (stop-follower! follower)
      (swap! followers dissoc session-id))))

(defn job
  [_system]
  {:id :audit-collector
   :interval-seconds 5
   :initial-delay-seconds 5
   :run reconcile!})
