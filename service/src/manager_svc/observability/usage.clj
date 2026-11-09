(ns manager-svc.observability.usage
  "LLM usage metering.

   Every gateway call is one request with three commands:
   - :record-usage on the session's usage aggregate: the event is the detail
     row, the state holds the session's running totals (and its run);
   - :roll-up-usage on the UTC day's rollup and on the all-time rollup, whose
     state holds totals per team, user, connection and model, and for a
     call of a run on the run's rollup.
   The user is the session's owner: the person who started the run, or who
   forked the session.
   Admin and team usage sum day rollups (day granularity), metrics read the
   all-time rollup, and a rollup also keeps tokens per team, which the
   budget check reads, so no view scans every session."
  (:require [edd.common :as common]
            [edd.core :as edd]
            [lambda.uuid :as uuid]
            [manager-svc.platform.api :as platform]
            [manager-svc.rows :as rows]
            [manager-svc.system :as system]
            [manager-svc.time :as time])
  (:import [java.time LocalDate ZoneOffset]))

(def counters
  [:calls :prompt-tokens :completion-tokens :cached-tokens :errors :latency-ms])

(def empty-totals
  (zipmap counters (repeat 0)))

(def row-keys
  [:team-id :connection :model])

(def rollup-keys
  [:team-id :username :connection :model])

(defn aggregate-id
  [session-id]
  (uuid/named (str "usage:" session-id)))

(defn day-id
  [day]
  (uuid/named (str "usage-day:" day)))

(def total-id
  (uuid/named "usage-total"))

(defn run-rollup-id
  [run-id]
  (uuid/named (str "usage-run:" run-id)))

(defn day-of
  [timestamp]
  (subs (str timestamp) 0 10))

(defn days-since
  "UTC days from the day of since up to today, as yyyy-mm-dd strings."
  [since]
  (let [today
        (LocalDate/now ZoneOffset/UTC)

        from
        (LocalDate/parse (day-of since))]
    (map str (take-while #(not (.isAfter ^LocalDate % today))
                         (iterate #(.plusDays ^LocalDate % 1) from)))))

(defn call-totals
  [{:keys [status prompt-tokens completion-tokens cached-tokens latency-ms]}]
  {:calls 1
   :prompt-tokens (or prompt-tokens 0)
   :completion-tokens (or completion-tokens 0)
   :cached-tokens (or cached-tokens 0)
   :errors (if (and status (>= status 400)) 1 0)
   :latency-ms (or latency-ms 0)})

(defn add-totals
  [a b]
  (merge-with + (merge empty-totals (select-keys a counters)) (select-keys b counters)))

(defn- row-of
  [call]
  {:team-id (or (:team-id call) "")
   :username (:username call)
   :connection (:connection call)
   :model (:model call)})

(defn add-to-rows
  [existing call]
  (let [target
        (row-of call)

        totals
        (call-totals call)]
    (rows/upsert existing
                 #(= target (row-of %))
                 (merge target empty-totals)
                 #(merge % (add-totals % totals)))))

(defn tokens
  [{:keys [prompt-tokens completion-tokens]}]
  (+ (or prompt-tokens 0) (or completion-tokens 0)))

(defn apply-recorded
  [aggregate {:keys [attrs]}]
  (merge aggregate
         (add-totals aggregate (call-totals attrs))
         {:session-id (str (:session-id attrs))
          :run-id (some-> (:run-id attrs) str)
          :team-id (:team-id attrs)}))

(defn apply-rolled-up
  [aggregate {:keys [attrs]}]
  (-> aggregate
      (assoc :rows (add-to-rows (:rows aggregate) attrs))
      (update-in [:team-tokens (or (:team-id attrs) "")] (fnil + 0) (tokens attrs))))

(defn summary
  "{:calls :prompt-tokens :completion-tokens :cached-tokens :errors :avg-latency-ms}"
  [totals]
  (let [{:keys [calls latency-ms] :as all}
        (merge empty-totals (select-keys totals counters))]
    (assoc (dissoc all :latency-ms)
           :avg-latency-ms (if (pos? calls) (quot latency-ms calls) 0))))

(defn group-rows
  "Rows summed by the given keys, sorted by them, without latency."
  [rows group-keys]
  (sort-by (apply juxt group-keys)
           (for [[k items] (group-by #(select-keys % group-keys) rows)]
             (merge k (dissoc (reduce add-totals empty-totals items) :latency-ms)))))

(defn price
  "Estimated cost of a usage row from its connection's pricing, or nil."
  [llm-connection {:keys [model prompt-tokens completion-tokens cached-tokens]}]
  (let [model-price
        (when model
          (get (:pricing llm-connection) (keyword model)))]
    (when model-price
      (let [{:keys [input output cached-input]}
            model-price

            cached
            (or cached-tokens 0)

            uncached
            (- (or prompt-tokens 0) cached)

            dollars
            (/ (+ (* uncached input)
                  (* cached (or cached-input input))
                  (* (or completion-tokens 0) output))
               1000000.0)]
        (/ (Math/round (* dollars 10000.0)) 10000.0)))))

(defn rows-since
  "Rollup rows of the days since an ISO timestamp, or all-time rows for nil."
  [ctx since]
  (if since
    (mapcat #(:rows (common/get-by-id ctx {:id (day-id %)})) (days-since since))
    (:rows (common/get-by-id ctx {:id total-id}))))

(defn record-cmd
  [_ctx {:keys [id attrs]}]
  {:event-id :usage-recorded
   :id id
   :attrs attrs})

(defn roll-up-cmd
  [_ctx {:keys [id attrs]}]
  {:event-id :usage-rolled-up
   :id id
   :attrs (dissoc attrs :scope)})

(defn rollup-id
  [{:keys [scope ts run-id]}]
  (case (keyword scope)
    :day (day-id (day-of ts))
    :run (run-rollup-id run-id)
    total-id))

(defn- roll-up
  [call scope]
  (let [attrs
        (assoc call :scope scope)]
    {:cmd-id :roll-up-usage :id (rollup-id attrs) :attrs attrs}))

(defn commands
  "The commands metering one call."
  [call]
  (cond-> [{:cmd-id :record-usage :id (aggregate-id (:session-id call)) :attrs call}
           (roll-up call :day)
           (roll-up call :total)]
    (:run-id call) (conj (roll-up call :run))))

(defn- summary-query
  [ctx {:keys [session-id run-id team-id since]}]
  (cond
    session-id
    (summary (common/get-by-id ctx {:id (aggregate-id session-id)}))

    run-id
    (summary (reduce add-totals empty-totals
                     (:rows (common/get-by-id ctx {:id (run-rollup-id run-id)}))))

    :else
    (summary (reduce add-totals empty-totals
                     (filter #(= (or team-id "") (:team-id %)) (rows-since ctx since))))))

(defn- priced
  [ctx rows]
  (let [system
        (system/of ctx)]
    (mapv #(assoc % :cost (price (platform/llm-connection system (:connection %)) %)) rows)))

(defn rows-query
  "Rows per team, connection and model since, each with its :cost (nil
   without pricing)."
  [ctx {:keys [team-ids since]}]
  (let [teams
        (when team-ids (set team-ids))]
    (priced ctx (group-rows (filter #(or (nil? teams) (contains? teams (:team-id %))) (rows-since ctx since))
                            row-keys))))

(defn team-tokens-today
  "Tokens of a team's calls today, from today's rollup."
  [ctx team-id]
  (let [day
        (common/get-by-id ctx {:id (day-id (day-of (time/now)))})]
    (or (get-in day [:team-tokens (or team-id "")])
        (reduce + 0 (map tokens (filter #(= (or team-id "") (:team-id %)) (:rows day)))))))

(defn- team-tokens-query
  [ctx {:keys [team-id]}]
  {:tokens (team-tokens-today ctx team-id)})

(defn team-usage-query
  "What a team page shows: {:usage summary :used-today tokens :by-model rows}."
  [ctx {:keys [team-id since]}]
  (let [team-rows
        (filter #(= team-id (:team-id %)) (rows-since ctx since))]
    {:usage (summary (reduce add-totals empty-totals team-rows))
     :used-today (team-tokens-today ctx team-id)
     :by-model (mapv #(select-keys % [:model :calls :prompt-tokens :completion-tokens])
                     (group-rows team-rows [:team-id :model]))}))

(defn tokens-by-user
  "[{:username :tokens}] summed over rows, most tokens first; rows recorded
   before usage carried a user are left out."
  [rows]
  (let [by-user
        (group-by :username (filter :username rows))]
    (vec (sort-by :tokens >
                  (for [[username items] by-user]
                    {:username username
                     :tokens (reduce + 0 (map tokens items))})))))

(defn- users-query
  [ctx {:keys [since]}]
  (tokens-by-user (rows-since ctx since)))

(defn since-days
  "Start of the window of `days` UTC days that ends today."
  [days]
  (time/start-of-day (dec (max 1 days))))

(defn admin-usage-query
  [ctx {:keys [days]}]
  (platform/require-admin! ctx)
  (priced ctx (group-rows (rows-since ctx (since-days (or days 7))) row-keys)))

(def RecordUsage
  [:map
   [:attrs [:map
            [:session-id [:or :uuid :string]]
            [:model {:optional true} [:maybe string?]]
            [:ts string?]]]])

(def RollUpUsage
  [:map
   [:attrs [:map
            [:scope [:enum :day :total :run]]
            [:ts string?]]]])

(defn register
  [ctx]
  (-> ctx
      (edd/reg-cmd :record-usage record-cmd
                   :consumes RecordUsage
                   :id-fn (fn [_ cmd] (aggregate-id (get-in cmd [:attrs :session-id])))
                   :auth {:role [:non-interactive]})
      (edd/reg-cmd :roll-up-usage roll-up-cmd
                   :consumes RollUpUsage
                   :id-fn (fn [_ cmd] (rollup-id (:attrs cmd)))
                   :auth {:role [:non-interactive]})
      (edd/reg-event :usage-recorded apply-recorded)
      (edd/reg-event :usage-rolled-up apply-rolled-up)
      (edd/reg-query :get-usage-summary summary-query
                     :auth {:role [:non-interactive]})
      (edd/reg-query :get-usage-rows rows-query
                     :auth {:role [:non-interactive]})
      (edd/reg-query :get-team-tokens-today team-tokens-query
                     :consumes [:map [:team-id [:maybe string?]]]
                     :auth {:role [:non-interactive]})
      (edd/reg-query :get-team-usage team-usage-query
                     :consumes [:map [:team-id string?] [:since string?]]
                     :auth {:role [:non-interactive]})
      (edd/reg-query :get-usage-by-user users-query
                     :consumes [:map [:since string?]]
                     :auth {:role [:non-interactive]})
      (edd/reg-query :get-admin-usage admin-usage-query
                     :consumes [:map [:days {:optional true} [:int {:min 1 :max 90}]]]
                     :auth {:role [:user :non-interactive]})))
