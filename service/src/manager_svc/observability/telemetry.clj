(ns manager-svc.observability.telemetry
  "Tool activity reported by the sandbox monitor plugin
   (runs/monitor_plugin.js).

   Each report is a :telemetry-recorded event on the session's telemetry
   aggregate, which keeps per-tool statistics and the blocked count. The
   activity view reads the latest reports back from the event store by
   event-seq, so no record buffer lives in memory or in the aggregate.
   Finished and blocked tool calls are also rolled up per team and tool into
   one all-time aggregate for /metrics."
  (:require [clojure.string :as string]
            [edd.common :as common]
            [edd.core :as edd]
            [edd.dal :as dal]
            [lambda.uuid :as uuid]
            [manager-svc.errors :as errors]
            [manager-svc.observability.access :as access]
            [manager-svc.observability.json :as json]
            [manager-svc.rows :as rows]))

(def kinds
  #{"tool.start" "tool.end" "tool.blocked" "event"})

(def recent-limit
  100)

(def max-detail-chars
  2000)

(def limits
  {"tool" 100 "call_id" 200 "conversation" 200 "event" 100})

(defn aggregate-id
  [session-id]
  (uuid/named (str "telemetry:" session-id)))

(def total-id
  (uuid/named "telemetry-total"))

(defn- truncate
  [value]
  (if (and (string? value) (> (count value) max-detail-chars))
    (subs value 0 max-detail-chars)
    value))

(defn parse-report
  "Validated report attrs from the plugin's JSON body (string keys)."
  [body]
  (let [kind
        (get body "kind")

        too-long
        (some (fn [[field limit]]
                (let [value (get body field)]
                  (when (and (string? value) (> (count value) limit)) field)))
              limits)

        duration
        (get body "duration_ms")]
    (cond
      (not (map? body)) (errors/invalid! "Invalid telemetry event")
      (not (contains? kinds kind)) (errors/invalid! "Unknown telemetry kind")
      too-long (errors/invalid! (str too-long " is too long"))
      (and duration (or (not (integer? duration)) (neg? duration))) (errors/invalid! "Invalid duration_ms")
      :else {:kind (keyword kind)
             :tool (get body "tool")
             :call-id (get body "call_id")
             :conversation (get body "conversation")
             :duration-ms duration
             :detail (into {}
                           (keep (fn [[field k]]
                                   (when-some [value (get body field)]
                                     [k (truncate value)])))
                           {"args" :args
                            "title" :title
                            "output_bytes" :output-bytes
                            "reason" :reason
                            "event" :event
                            "details" :details})})))

(defn- update-tool
  [tools tool duration-ms]
  (let [record
        (fn [stats]
          (cond-> (update stats :calls inc)
            duration-ms (-> (update :total-ms + duration-ms)
                            (update :timed inc)
                            (update :max-ms max duration-ms))))

        fresh
        {:tool tool :calls 0 :total-ms 0 :timed 0 :max-ms 0}]
    (rows/upsert tools #(= tool (:tool %)) fresh record)))

(defn apply-recorded
  [aggregate {:keys [attrs]}]
  (let [{:keys [kind tool duration-ms ts]}
        attrs

        base
        (assoc aggregate
               :session-id (:session-id attrs)
               :team-id (:team-id attrs)
               :last-seen ts
               :blocked (or (:blocked aggregate) 0)
               :tools (vec (:tools aggregate)))]
    (case (keyword kind)
      :tool.end (update base :tools update-tool (or tool "") duration-ms)
      :tool.blocked (update base :blocked inc)
      base)))

(defn apply-rolled-up
  [aggregate {:keys [attrs]}]
  (let [target
        {:team-id (or (:team-id attrs) "") :tool (or (:tool attrs) "") :kind (keyword (:kind attrs))}

        add
        #(-> %
             (update :n inc)
             (update :total-ms + (or (:duration-ms attrs) 0)))

        same-target?
        (fn [row]
          (and (= (:team-id target) (:team-id row))
               (= (:tool target) (:tool row))
               (= (:kind target) (:kind row))))]
    (assoc aggregate
           :rows (rows/upsert (:rows aggregate) same-target? (assoc target :n 0 :total-ms 0) add))))

(defn record-cmd
  [_ctx {:keys [id attrs]}]
  {:event-id :telemetry-recorded
   :id id
   :attrs attrs})

(defn roll-up-cmd
  [_ctx {:keys [id attrs]}]
  {:event-id :telemetry-rolled-up
   :id id
   :attrs (select-keys attrs [:team-id :tool :kind :duration-ms])})

(defn commands
  "The commands recording one report: {:session-id :team-id :ts ...parse-report}."
  [report]
  (cond-> [{:cmd-id :record-telemetry :id (aggregate-id (:session-id report)) :attrs report}]
    (#{:tool.end :tool.blocked} (:kind report))
    (conj {:cmd-id :roll-up-telemetry :id total-id :attrs report})))

(defn ->activity-event
  [{:keys [event-seq attrs]}]
  {:id event-seq
   :ts (:ts attrs)
   :kind (keyword (:kind attrs))
   :tool (:tool attrs)
   :call-id (:call-id attrs)
   :duration-ms (:duration-ms attrs)
   :detail (or (:detail attrs) {})})

(defn- status-event?
  [{:keys [kind detail]}]
  (and (= :event kind)
       (contains? #{"session.status" "session.idle"} (:event detail))))

(defn- edited-file
  [{:keys [detail]}]
  (when (= "file.edited" (:event detail))
    (let [details
          (json/read-str (or (:details detail) "{}"))]
      (when (map? details)
        (get details "file")))))

(defn- tool-stat
  [{:keys [tool calls total-ms timed max-ms]}]
  {:tool tool
   :calls calls
   :avg-ms (when (pos? timed) (double (/ total-ms timed)))
   :max-ms (when (pos? timed) max-ms)})

(defn tool-stats
  [tools]
  (vec (sort-by :calls > (map tool-stat tools))))

(defn activity
  "SessionActivity from the telemetry aggregate and its latest events, newest first."
  [aggregate recent]
  (let [finished
        (set (keep #(when (= :tool.end (:kind %)) (:call-id %)) recent))

        running
        (filter #(and (= :tool.start (:kind %)) (not (contains? finished (:call-id %)))) recent)

        status
        (first (filter status-event? recent))]
    {:current (first running)
     :state (cond
              (seq running) :busy
              (= "session.idle" (get-in status [:detail :event])) :idle
              :else :unknown)
     :events (vec recent)
     :tools (tool-stats (:tools aggregate))
     :blocked (or (:blocked aggregate) 0)
     :files-edited (vec (sort (set (keep edited-file recent))))
     :last-seen (:ts (first recent))}))

(defn recent-events
  [ctx session-id last-seq]
  (if (pos? last-seq)
    (let [events
          (dal/get-events (assoc ctx
                                 :id (aggregate-id session-id)
                                 :version [(max 1 (- last-seq (dec recent-limit))) last-seq]))]
      (vec (reverse (map ->activity-event events))))
    []))

(defn activity-query
  [ctx {:keys [session-id]}]
  (access/visible-session! ctx session-id)
  (let [aggregate
        (common/get-by-id ctx {:id (aggregate-id session-id)})]
    (activity aggregate (recent-events ctx session-id (or (:version aggregate) 0)))))

(defn metrics-rows
  "[{:team-id :tool :kind :n :total-ms}] over all sessions, as TelemetryStore.metrics."
  [ctx]
  (vec (:rows (common/get-by-id ctx {:id total-id}))))

(def RecordTelemetry
  [:map
   [:attrs [:map
            [:session-id [:or :uuid :string]]
            [:ts string?]
            [:kind keyword?]]]])

(defn tool-report?
  [{:keys [kind]}]
  (string/starts-with? (name kind) "tool."))

(defn register
  [ctx]
  (-> ctx
      (edd/reg-cmd :record-telemetry record-cmd
                   :consumes RecordTelemetry
                   :id-fn (fn [_ cmd] (aggregate-id (get-in cmd [:attrs :session-id])))
                   :auth {:role [:non-interactive]})
      (edd/reg-cmd :roll-up-telemetry roll-up-cmd
                   :consumes [:map [:attrs [:map [:kind keyword?]]]]
                   :id-fn (constantly total-id)
                   :auth {:role [:non-interactive]})
      (edd/reg-event :telemetry-recorded apply-recorded)
      (edd/reg-event :telemetry-rolled-up apply-rolled-up)
      (edd/reg-query :get-session-activity activity-query
                     :consumes [:map [:session-id [:or :uuid :string]]]
                     :auth {:role [:user :non-interactive]})))
