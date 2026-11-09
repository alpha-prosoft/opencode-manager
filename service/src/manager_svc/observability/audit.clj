(ns manager-svc.observability.audit
  "Append-only, hash-chained audit trail per session.

   Each entry is an :audit-appended event on the session's audit aggregate;
   its event-seq is the entry's seq, so pages of the trail are event-store
   range reads. The aggregate keeps only the count, the chain head and the
   conversations seen. An entry's hash is the sha256 of the JSON array
   [prev-hash, ts, type, key, body] (:hash-version 2), with body the
   canonical JSON payload, stored verbatim so verification and the export
   never depend on re-serialisation. Entries recorded before versions
   (no :hash-version) are sha256(prev-hash|type|key|body) and verify as
   such."
  (:require [edd.common :as common]
            [edd.core :as edd]
            [edd.dal :as dal]
            [edd.el.ctx :as el-ctx]
            [lambda.uuid :as uuid]
            [manager-svc.hash :as hash]
            [manager-svc.observability.access :as access]
            [manager-svc.observability.json :as json]
            [manager-svc.rows :as rows]))

(def genesis
  (apply str (repeat 64 "0")))

(def ignored
  #{"message.part.delta" "server.heartbeat" "server.connected" "plugin.added"
    "catalog.updated" "reference.updated" "integration.updated"})

(def page-size
  1000)

(defn aggregate-id
  [session-id]
  (uuid/named (str "audit:" session-id)))

(def hash-version
  2)

(defn entry-hash
  "The hash of an entry ({:ts :type :key :body}) after previous, as version
   computes it."
  [version previous {:keys [ts type key body]}]
  (if (= 2 version)
    (hash/sha256-hex (json/canonical [previous ts type key body]))
    (hash/sha256-hex (str previous "|" type "|" key "|" body))))

(defn- conversation-facts
  "The conversation an entry belongs to, read from its payload once when it
   is recorded, so applying events never parses bodies."
  [entry-type payload]
  (case entry-type
    "conversation" {:conversation-id (get payload "id") :title (get payload "title")}
    "message" {:conversation-id (get payload "sessionID")}
    {}))

(defn entry
  "Command attrs for one entry: {:session-id :ts :type :key :body} and, for
   conversations and messages, :conversation-id (and :title)."
  [session-id ts entry-type entry-key payload]
  (into {:session-id (str session-id)
         :ts ts
         :type entry-type
         :key entry-key
         :body (json/canonical payload)}
        (remove (comp nil? val))
        (conversation-facts entry-type payload)))

(defn classify
  "[type dedupe-key payload] for an OpenCode event (string keys), or nil when
   it is not worth recording."
  [event]
  (let [kind
        (get event "type")

        props
        (or (get event "properties") {})]
    (cond
      (or (nil? kind) (contains? ignored kind))
      nil

      (= "message.updated" kind)
      (let [info
            (or (get props "info") {})]
        (when-not (and (= "assistant" (get info "role"))
                       (not (get-in info ["time" "completed"])))
          ["message" (str "message:" (get info "id")) info]))

      (= "message.part.updated" kind)
      (let [part
            (or (get props "part") {})

            part-type
            (get part "type")

            part-key
            (str "part:" (get part "id"))]
        (cond
          (= "tool" part-type)
          (when (#{"completed" "error"} (get-in part ["state" "status"]))
            ["tool" part-key part])

          (#{"text" "reasoning"} part-type)
          (let [timing
                (get part "time")]
            (when-not (and timing (not (get timing "end")))
              [part-type part-key part]))

          (#{"step-start" "step-finish" "snapshot" "patch"} part-type)
          nil

          :else
          [(or part-type "part") part-key part]))

      :else
      [kind (str kind ":" (hash/sha1-hex (json/canonical props))) props])))

(defn- upsert-conversation
  [conversations id f]
  (rows/upsert conversations #(= id (:id %)) {:id id :title nil :messages 0 :started nil :last nil} f))

(defn- min-ts
  [a b]
  (if (and a (neg? (compare a b))) a b))

(defn- max-ts
  [a b]
  (if (and a (pos? (compare a b))) a b))

(defn- with-conversation-facts
  "Entries recorded before attrs carried their conversation are read from
   the body."
  [{:keys [type body] :as attrs}]
  (if (and (contains? #{"conversation" "message"} type)
           (not (contains? attrs :conversation-id)))
    (merge attrs (conversation-facts type (json/read-str body)))
    attrs))

(defn- track-conversation
  [conversations attrs]
  (let [{:keys [ts conversation-id title] :as attrs}
        (with-conversation-facts attrs)]
    (case (:type attrs)
      "conversation"
      (upsert-conversation conversations conversation-id #(assoc % :title title))

      "message"
      (if conversation-id
        (upsert-conversation conversations conversation-id
                             #(-> %
                                  (update :messages inc)
                                  (update :started min-ts ts)
                                  (update :last max-ts ts)))
        conversations)

      conversations)))

(defn apply-appended
  [aggregate {:keys [attrs]}]
  (assoc aggregate
         :session-id (:session-id attrs)
         :count (inc (or (:count aggregate) 0))
         :head (:hash attrs)
         :last-key (:key attrs)
         :conversations (track-conversation (:conversations aggregate) attrs)))

(defn append-cmd
  [ctx {:keys [id attrs]}]
  (let [aggregate
        (el-ctx/get-aggregate ctx)

        previous
        (or (:head aggregate) genesis)]
    (if (= (:key attrs) (:last-key aggregate))
      []
      {:event-id :audit-appended
       :id id
       :attrs (assoc attrs
                     :prev-hash previous
                     :hash-version hash-version
                     :hash (entry-hash hash-version previous attrs))})))

(defn- events
  "Audit entries with seq in [from, to]."
  [ctx session-id from to]
  (if (> from to)
    []
    (dal/get-events (assoc ctx :id (aggregate-id session-id) :version [from to]))))

(defn ->entry
  "An entry as the UI reads it, with the payload parsed; raw entries carry
   the stored :body instead, exactly as hashed. :hash-version says how the
   hash was taken (nil for entries recorded before versions)."
  [raw? {:keys [event-seq attrs]}]
  (let [entry
        {:seq event-seq
         :ts (:ts attrs)
         :type (:type attrs)
         :key (:key attrs)
         :hash (:hash attrs)
         :hash-version (:hash-version attrs)}]
    (if raw?
      (assoc entry :body (:body attrs))
      (assoc entry :payload (json/read-str (:body attrs))))))

(defn- last-seq
  [ctx session-id]
  (or (:version (common/get-by-id ctx {:id (aggregate-id session-id)})) 0))

(defn- page
  [ctx session-id after limit]
  (events ctx session-id (inc after) (min (last-seq ctx session-id) (+ after limit))))

(defn verify-events
  "Recomputes the chain over entries in seq order and compares its end with
   the aggregate's head and count, so a missing tail is found too."
  [aggregate entries]
  (loop [previous genesis
         checked 0
         [{:keys [event-seq attrs]} & more :as remaining] entries]
    (cond
      (empty? remaining)
      (if (and (= checked (or (:count aggregate) 0))
               (= previous (or (:head aggregate) genesis)))
        {:valid true :checked checked :head previous}
        {:valid false :checked checked :broken-at (inc checked)})

      (or (not= event-seq (inc checked))
          (not= previous (:prev-hash attrs))
          (not= (:hash attrs) (entry-hash (:hash-version attrs) previous attrs)))
      {:valid false :checked checked :broken-at (inc checked)}

      :else
      (recur (:hash attrs) (inc checked) more))))

(defn- all-events
  [ctx session-id]
  (let [end
        (last-seq ctx session-id)]
    (mapcat #(events ctx session-id % (min end (+ % page-size -1)))
            (range 1 (inc end) page-size))))

(defn list-query
  [ctx {:keys [session-id after limit raw]}]
  (access/visible-session! ctx session-id)
  (mapv (partial ->entry (true? raw)) (page ctx session-id (or after 0) (min 1000 (or limit 200)))))

(defn verify-query
  [ctx {:keys [session-id]}]
  (access/visible-session! ctx session-id)
  (verify-events (common/get-by-id ctx {:id (aggregate-id session-id)})
                 (all-events ctx session-id)))

(defn keys-query
  "Every key in a session's audit trail, for the collector's dedupe."
  [ctx {:keys [session-id]}]
  (mapv #(get-in % [:attrs :key]) (all-events ctx session-id)))

(defn summary-query
  [ctx {:keys [session-id]}]
  (let [aggregate
        (common/get-by-id ctx {:id (aggregate-id session-id)})]
    {:count (or (:count aggregate) 0)
     :conversations (vec (:conversations aggregate))}))

(def SessionQuery
  [:map [:session-id [:or :uuid :string]]])

(def AppendAudit
  [:map
   [:attrs [:map
            [:session-id [:or :uuid :string]]
            [:ts string?]
            [:type string?]
            [:key string?]
            [:body string?]]]])

(defn register
  [ctx]
  (-> ctx
      (edd/reg-cmd :append-audit append-cmd
                   :consumes AppendAudit
                   :id-fn (fn [_ cmd] (aggregate-id (get-in cmd [:attrs :session-id])))
                   :auth {:role [:non-interactive]})
      (edd/reg-event :audit-appended apply-appended)
      (edd/reg-query :list-audit-events list-query
                     :consumes [:map
                                [:session-id [:or :uuid :string]]
                                [:after {:optional true} [:maybe [:int {:min 0}]]]
                                [:limit {:optional true} [:maybe [:int {:min 1 :max 1000}]]]
                                [:raw {:optional true} [:maybe boolean?]]]
                     :auth {:role [:user :non-interactive]})
      (edd/reg-query :list-audit-keys keys-query
                     :consumes SessionQuery
                     :auth {:role [:non-interactive]})
      (edd/reg-query :verify-audit verify-query
                     :consumes SessionQuery
                     :auth {:role [:user :non-interactive]})
      (edd/reg-query :get-audit-summary summary-query
                     :consumes SessionQuery
                     :auth {:role [:non-interactive]})))
