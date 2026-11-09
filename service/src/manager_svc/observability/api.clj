(ns manager-svc.observability.api
  "Facade for usage, audit and telemetry. Reads and writes go to edd as the
   platform (manager-svc.client); a failed call throws."
  (:require [manager-svc.client :as client]
            [manager-svc.observability.audit :as audit]
            [manager-svc.observability.telemetry :as telemetry]
            [manager-svc.observability.upstream :as upstream]
            [manager-svc.observability.usage :as usage]
            [manager-svc.time :as time]))

(defonce ^:private write-lock
  (Object.))

(defn- serialized-command
  "Usage and telemetry rollups are shared aggregates; writing them one
   request at a time avoids edd's concurrent-modification retries. Nobody
   reads these writes back right away, so the lock never waits for effects."
  [system commands]
  (locking write-lock
    (apply client/command-no-settle (:settings system) commands)))

(defn record-usage!
  "One LLM call: {:session-id :username :run-id :team-id :connection :model :status
   :prompt-tokens :completion-tokens :cached-tokens :latency-ms}."
  [system call]
  (serialized-command system (usage/commands (merge {:ts (time/now)}
                                                    (update call :session-id str)))))

(defn record-telemetry!
  "One monitor plugin report (telemetry/parse-report attrs) of a session."
  [system session report]
  (serialized-command system (telemetry/commands (assoc report
                                                        :session-id (str (:id session))
                                                        :team-id (:team-id session)
                                                        :ts (time/now)))))

(defn- summary
  [system query]
  (client/query (:settings system) (assoc query :query-id :get-usage-summary)))

(defn usage-for-session
  "{:calls :prompt-tokens :completion-tokens :cached-tokens :errors :avg-latency-ms}"
  [system session-id]
  (summary system {:session-id (str session-id)}))

(defn usage-for-run
  [system run-id]
  (summary system {:run-id (str run-id)}))

(defn team-usage
  "What a team page shows, in one call: {:usage (usage-for-session shape)
   :used-today tokens :by-model [{:model :calls :prompt-tokens
   :completion-tokens}]} since an ISO timestamp (day granularity)."
  [system team-id since]
  (client/query (:settings system) {:query-id :get-team-usage :team-id team-id :since since}))

(defn usage-rows
  "[{:team-id :connection :model :calls :prompt-tokens :completion-tokens
   :cached-tokens :errors :cost}] for team ids (nil = all) since; :cost is
   nil without pricing."
  [system team-ids since]
  (client/query (:settings system)
                (cond-> {:query-id :get-usage-rows :since since}
                  team-ids (assoc :team-ids (vec team-ids)))))

(defn tokens-by-user
  "{username tokens} of LLM calls since an ISO timestamp (day granularity),
   attributed to the owner of the calling session."
  [system since]
  (into {}
        (map (juxt :username :tokens))
        (client/query (:settings system) {:query-id :get-usage-by-user :since since})))

(defn tokens-today
  "Tokens of a team's LLM calls today (UTC)."
  [system team-id]
  (:tokens (client/query (:settings system) {:query-id :get-team-tokens-today :team-id team-id})))

(defn test-llm-connection
  "A tiny chat completion through the connection, the way the gateway calls
   it: {:ok true :model :latency-ms} or {:ok false :message [:status :latency-ms]}."
  [connection secret]
  (upstream/test-connection connection secret))

(defn append-audit!
  "Appends one entry to a session's audit trail, e.g. a fork:
   (append-audit! system id \"fork\" (str \"fork:\" new-id) {:forked-to new-id :by user}).
   Throws when it was not recorded."
  [system session-id type key payload]
  (client/command-no-settle (:settings system)
                            {:cmd-id :append-audit
                             :id (audit/aggregate-id session-id)
                             :attrs (audit/entry session-id (time/now) type key payload)}))

(defn- audit-summary
  [system session-id]
  (client/query (:settings system) {:query-id :get-audit-summary :session-id (str session-id)}))

(defn audit-count
  [system session-id]
  (:count (audit-summary system session-id)))

(defn audit-conversations
  "Conversations seen in a session's audit trail [{:id :title :messages :started :last}],
   most recent first."
  [system session-id]
  (let [conversations
        (:conversations (audit-summary system session-id))

        with-messages
        (sort-by :last #(compare %2 %1) (filter #(pos? (:messages %)) conversations))

        without-messages
        (remove #(pos? (:messages %)) conversations)]
    (mapv #(update % :title (fn [title] (or title "Untitled conversation")))
          (concat with-messages without-messages))))
