(ns manager-svc.runs.triggers
  "Pull request and schedule triggers, replacing the Temporal schedules.

   Every workflow with a pull_request or schedule trigger has a schedule
   trigger-<team>-<workflow>; its state aggregate keeps when it last ran.
   A pull_request trigger remembers the pull requests it has seen in a
   trigger aggregate, so each one starts a run only once (the open pull
   requests at first sight form the baseline unless backfill is set). Run ids
   are derived from the trigger and the item, so a run is never started
   twice even when a poll is interrupted."
  (:require [clojure.tools.logging :as log]
            [edd.common :as common]
            [edd.core :as edd]
            [edd.el.ctx :as el-ctx]
            [lambda.uuid :as uuid]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.directory :as directory]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.definitions :as definitions]
            [manager-svc.client :as client]
            [manager-svc.runs.edd :as runs-edd]
            [manager-svc.runs.scm :as scm]
            [manager-svc.system :as system]
            [manager-svc.time :as time]
            [manager-svc.workers :as workers]))

(def baseline "__baseline__")

(defn trigger-key
  [team-id workflow-id]
  (str team-id "/" workflow-id))

(defn trigger-id
  [team-id workflow-id]
  (uuid/named (str "trigger:" (trigger-key team-id workflow-id))))

(defn schedule-key
  [team-id workflow-id]
  (str "trigger-" team-id "-" workflow-id))

(defn schedule-id
  [schedule-key]
  (uuid/named (str "schedule:" schedule-key)))

(defn desired-schedules
  "{schedule-key {:kind :team :workflow :minutes}} for workflows given as
   {team-id [workflow-spec]}."
  [workflows-by-team]
  (into {}
        (for [[team-id workflows] workflows-by-team
              {:keys [id trigger]} workflows
              :let [kind (:type trigger)]
              :when (contains? #{"pull_request" "schedule"} kind)]
          [(schedule-key team-id id)
           {:kind kind
            :team team-id
            :workflow id
            :minutes (if (= "pull_request" kind)
                       (:poll-minutes trigger)
                       (:every-minutes trigger))}])))

(defn next-run
  "When a schedule is due next: a pull request poll runs as soon as it is
   registered, a scheduled run one interval after; then one interval after
   the last run."
  [{:keys [registered-at last-run]} {:keys [kind minutes]}]
  (let [interval
        (* 60 (long minutes))]
    (cond
      last-run (time/plus-seconds last-run interval)
      (nil? registered-at) nil
      (= "pull_request" kind) registered-at
      :else (time/plus-seconds registered-at interval))))

(defn due?
  [state schedule now]
  (let [at
        (next-run state schedule)]
    (boolean (and at (not (time/after? at now))))))

(defn- mark-seen
  [ctx {:keys [attrs]}]
  {:event-id :trigger-items-seen
   :id (trigger-id (:team-id attrs) (:workflow-id attrs))
   :attrs {:key (trigger-key (:team-id attrs) (:workflow-id attrs))
           :items (mapv #(select-keys % [:item :run-id]) (:items attrs))
           :at (time/now)}})

(def seen-retention-seconds
  "How long a seen pull request is remembered. A pull request open longer is
   seen again; its derived run id keeps it from starting a second run."
  (* 90 24 3600))

(defn- remembered?
  [now {:keys [item seen-at]}]
  (or (= baseline item)
      (nil? seen-at)
      (< (time/seconds-between seen-at now) seen-retention-seconds)))

(defn- apply-seen
  [state {:keys [attrs]}]
  (let [known
        (into {}
              (comp (filter #(remembered? (:at attrs) %))
                    (map (juxt :item identity)))
              (:seen state))

        merged
        (reduce (fn [acc {:keys [item run-id]}]
                  (update acc item (fn [old]
                                     {:item item
                                      :run-id (or run-id (:run-id old))
                                      :seen-at (or (:seen-at old) (:at attrs))})))
                known
                (:items attrs))]
    (assoc state
           :key (:key attrs)
           :seen (vec (sort-by :item (vals merged))))))

(defn- get-trigger-items
  [ctx {:keys [team-id workflow-id]}]
  (let [state
        (common/get-by-id ctx {:id (trigger-id team-id workflow-id)})]
    (mapv :item (:seen state))))

(defn- register-schedule
  "Registers a schedule, or records the team of one registered without it."
  [ctx {:keys [attrs]}]
  (let [state
        (el-ctx/get-aggregate ctx)]
    (when-not (and (:registered-at state) (:team-id state))
      {:event-id :schedule-registered
       :id (schedule-id (:schedule-key attrs))
       :attrs {:schedule-key (:schedule-key attrs)
               :team-id (:team-id attrs)
               :at (or (:registered-at state) (time/now))}})))

(defn- record-schedule-run
  [_ctx {:keys [attrs]}]
  {:event-id :schedule-ran
   :id (schedule-id (:schedule-key attrs))
   :attrs (assoc (select-keys attrs [:schedule-key :team-id :run-ids :error]) :at (time/now))})

(defn- apply-registered
  [state {:keys [attrs]}]
  (assoc state
         :schedule-key (:schedule-key attrs)
         :team-id (:team-id attrs)
         :registered-at (:at attrs)))

(defn- apply-ran
  [state {:keys [attrs]}]
  (assoc state
         :team-id (or (:team-id attrs) (:team-id state))
         :last-run (:at attrs)
         :last-run-ids (vec (:run-ids attrs))
         :last-error (:error attrs)))

(def schedule-row-keys
  [:schedule-key :team-id :registered-at :last-run :last-run-ids :last-error])

(defn directory-rows
  "A schedule is listed in its team's directory, keyed by its aggregate id."
  [state]
  (when (:team-id state)
    [(directory/upsert (directory/team (:team-id state))
                       :schedules
                       (assoc (select-keys state schedule-row-keys)
                              :id (:id state)
                              :version (:version state)))]))

(defn workflows-by-team
  [system]
  (into {}
        (for [team (platform/teams system)
              :let [team-id (:id team)]]
          [team-id (vec (definitions/workflows system team-id))])))

(defn- schedule-states
  [ctx]
  (into {}
        (map (juxt :schedule-key #(dissoc % :id :version)))
        (directory/rows-of ctx (directory/identities ctx "team") :schedules)))

(defn list-schedules
  [ctx _query]
  (platform/require-admin! ctx)
  (let [states
        (schedule-states ctx)]
    (vec (sort-by :id
                  (for [[k schedule] (desired-schedules (workflows-by-team (system/of ctx)))
                        :let [state (get states k)]]
                    {:id k
                     :next-run (next-run state schedule)
                     :last-run (:last-run state)
                     :paused false})))))

(defn get-schedule-states
  [ctx _query]
  (vec (vals (schedule-states ctx))))

(defn register
  [ctx]
  (let [by-trigger
        (fn [_ctx cmd] (trigger-id (get-in cmd [:attrs :team-id]) (get-in cmd [:attrs :workflow-id])))

        by-schedule
        (fn [_ctx cmd] (schedule-id (get-in cmd [:attrs :schedule-key])))

        platform-only
        {:role [:non-interactive]}]
    (-> ctx
        (edd/reg-cmd :mark-trigger-items-seen mark-seen
                     :id-fn by-trigger
                     :auth platform-only)
        (edd/reg-event :trigger-items-seen apply-seen)
        (edd/reg-query :get-trigger-items get-trigger-items
                       :consumes [:map [:team-id :string] [:workflow-id :string]]
                       :auth platform-only)
        (edd/reg-cmd :register-schedule register-schedule
                     :id-fn by-schedule
                     :auth platform-only)
        (edd/reg-cmd :record-schedule-run record-schedule-run
                     :id-fn by-schedule
                     :auth platform-only)
        (edd/reg-event :schedule-registered apply-registered)
        (edd/reg-event :schedule-ran apply-ran)
        (edd/reg-query :get-schedule-states get-schedule-states
                       :auth platform-only)
        (edd/reg-query :list-schedules list-schedules
                       :auth {:role [:user]})
        (directory/reg-rows #{:schedule-registered :schedule-ran} directory-rows))))

(defn- start-run!
  "Starts a run with a derived id unless it exists; returns the id."
  [system run-id attrs]
  (when-not (runs-edd/run-state system run-id)
    (client/command (:settings system) {:cmd-id :start-run :id run-id :attrs attrs}))
  run-id)

(defn- platform-secret!
  [system secret-name]
  (or (credentials/secret system secret-name)
      (throw (ex-info (str "Secret " secret-name " is not set") {:permanent true}))))

(defn new-pull-requests
  "Starts a run for every open pull request the trigger has not seen; returns their ids."
  [system team-id workflow-id]
  (let [{:keys [trigger]}
        (definitions/workflow system team-id workflow-id)]
    (if-not (= "pull_request" (:type trigger))
      []
      (let [provider
            (scm/provider system (:provider trigger) (platform-secret! system (:credentials trigger)))

            targets
            (set (:target-branches trigger))

            open-prs
            (filter #(or (empty? targets) (contains? targets (:destination-branch %)))
                    ((:open-pull-requests provider) (:repository trigger)))

            seen
            (set (client/query (:settings system) {:query-id :get-trigger-items :team-id team-id :workflow-id workflow-id}))

            mark!
            (fn [items]
              (client/command (:settings system) {:cmd-id :mark-trigger-items-seen
                                                  :attrs {:team-id team-id :workflow-id workflow-id :items items}}))

            key
            (trigger-key team-id workflow-id)]
        (if (and (not (contains? seen baseline)) (not (:backfill trigger)))
          (do
            (mark! (into [{:item baseline}] (map (fn [pr] {:item (str (:id pr))})) open-prs))
            (log/info "Trigger" key ":" (count open-prs) "open pull requests recorded as baseline")
            [])
          (let [started
                (vec
                 (for [pr open-prs
                       :when (not (contains? seen (str (:id pr))))]
                   {:item (str (:id pr))
                    :run-id (start-run! system
                                        (uuid/named (str "trigger-run:" key ":" (:id pr)))
                                        {:team-id team-id
                                         :workflow-id workflow-id
                                         :trigger {:type "pull_request"
                                                   :scm (select-keys trigger [:provider :repository :credentials])
                                                   :pull-request pr}
                                         :inputs {:pull-request (:id pr)}
                                         :created-by (str auth/platform-user ":pull_request")})}))

                items
                (cond->> started
                  (not (contains? seen baseline)) (cons {:item baseline}))]
            (when (seq items)
              (mark! (vec items)))
            (mapv :run-id started)))))))

(defn scheduled-run
  [system team-id workflow-id due-at]
  (let [{:keys [trigger]}
        (definitions/workflow system team-id workflow-id)]
    (start-run! system
                (uuid/named (str "schedule-run:" (schedule-key team-id workflow-id) ":" due-at))
                {:team-id team-id
                 :workflow-id workflow-id
                 :trigger {:type "schedule"}
                 :inputs (or (:inputs trigger) {})
                 :created-by (str auth/platform-user ":schedule")})))

(defn- fire!
  [system {:keys [kind team workflow]} due-at]
  (if (= "pull_request" kind)
    (new-pull-requests system team workflow)
    [(scheduled-run system team workflow due-at)]))

(declare fire-and-record!)

(defn run-due!
  "Registers new schedules and fires the due ones of the given kinds; returns
   {schedule-key run-ids}."
  [system kinds]
  (let [states
        (into {}
              (map (juxt :schedule-key identity))
              (client/query (:settings system) {:query-id :get-schedule-states}))

        now
        (time/now)]
    (into {}
          (for [[k schedule] (desired-schedules (workflows-by-team system))
                :when (contains? kinds (:kind schedule))
                :let [state (get states k)]]
            (if-not (:registered-at state)
              (do
                (client/command (:settings system) {:cmd-id :register-schedule :attrs {:schedule-key k :team-id (:team schedule)}})
                [k (if (= "pull_request" (:kind schedule))
                     (fire-and-record! system k schedule now)
                     [])])
              [k (if (due? state schedule now)
                   (fire-and-record! system k schedule (next-run state schedule))
                   [])])))))

(defn fire-and-record!
  [system k schedule due-at]
  (let [[run-ids error]
        (try
          [(fire! system schedule due-at) nil]
          (catch Exception e
            (log/warn e "Trigger" k "failed")
            [[] (ex-message e)]))]
    (client/command (:settings system) {:cmd-id :record-schedule-run
                                        :attrs {:schedule-key k :team-id (:team schedule) :run-ids run-ids :error error}})
    (when (seq run-ids)
      (workers/kick!))
    run-ids))
