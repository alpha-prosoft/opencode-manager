(ns manager-svc.runs.triggers
  "Pull request and schedule triggers, replacing the Temporal schedules.

   Every workflow with a pull_request or schedule trigger has a schedule
   trigger-<tenant>-<team>-<workflow>; its state aggregate keeps when it
   last ran. Runs a trigger starts are platform-started runs of the team.
   A pull_request trigger remembers the pull requests it has seen in a
   trigger aggregate, so each one starts a run only once (the open pull
   requests at first sight form the baseline unless backfill is set). Run ids
   are derived from the trigger and the item, so a run is never started
   twice even when a poll is interrupted."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
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
            [manager-svc.runs.model :as model]
            [manager-svc.runs.scm :as scm]
            [manager-svc.roles :as roles]
            [manager-svc.system :as system]
            [manager-svc.time :as time]
            [manager-svc.workers :as workers]))

(def baseline "__baseline__")

(defn trigger-key
  [tenant team-id workflow-id]
  (str tenant "/" team-id "/" workflow-id))

(defn trigger-id
  [tenant team-id workflow-id]
  (uuid/named (str "trigger:" (trigger-key tenant team-id workflow-id))))

(defn schedule-key
  [tenant team-id workflow-id]
  (str "trigger-" tenant "-" team-id "-" workflow-id))

(defn schedule-id
  [schedule-key]
  (uuid/named (str "schedule:" schedule-key)))

(defn desired-schedules
  "{schedule-key {:kind :tenant :team :workflow :minutes}} for workflows
   given as {[tenant team-id] [workflow-spec]}."
  [workflows-by-team]
  (into {}
        (for [[[tenant team-id] workflows] workflows-by-team
              {:keys [id trigger]} workflows
              :let [kind (:type trigger)]
              :when (contains? #{"pull_request" "schedule"} kind)]
          [(schedule-key tenant team-id id)
           {:kind kind
            :tenant tenant
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
   :id (trigger-id (:tenant attrs) (:team-id attrs) (:workflow-id attrs))
   :attrs {:key (trigger-key (:tenant attrs) (:team-id attrs) (:workflow-id attrs))
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
  [ctx {:keys [tenant team-id workflow-id]}]
  (let [state
        (common/get-by-id ctx {:id (trigger-id tenant team-id workflow-id)})]
    (mapv :item (:seen state))))

(defn- register-schedule
  "Registers a schedule, or records the team of one registered without it."
  [ctx {:keys [attrs]}]
  (let [state
        (el-ctx/get-aggregate ctx)]
    (when-not (and (:registered-at state) (:team-id state) (:tenant state))
      {:event-id :schedule-registered
       :id (schedule-id (:schedule-key attrs))
       :attrs {:schedule-key (:schedule-key attrs)
               :tenant (:tenant attrs)
               :team-id (:team-id attrs)
               :at (or (:registered-at state) (time/now))}})))

(defn- record-schedule-run
  [_ctx {:keys [attrs]}]
  {:event-id :schedule-ran
   :id (schedule-id (:schedule-key attrs))
   :attrs (assoc (select-keys attrs [:schedule-key :tenant :team-id :run-ids :error]) :at (time/now))})

(defn- apply-registered
  [state {:keys [attrs]}]
  (assoc state
         :schedule-key (:schedule-key attrs)
         :tenant (:tenant attrs)
         :team-id (:team-id attrs)
         :registered-at (:at attrs)))

(defn- apply-ran
  [state {:keys [attrs]}]
  (assoc state
         :tenant (or (:tenant attrs) (:tenant state))
         :team-id (or (:team-id attrs) (:team-id state))
         :last-run (:at attrs)
         :last-run-ids (vec (:run-ids attrs))
         :last-error (:error attrs)))

(def schedule-row-keys
  [:schedule-key :tenant :team-id :registered-at :last-run :last-run-ids :last-error])

(defn directory-rows
  "A schedule is listed in its team's directory, keyed by its aggregate id."
  [state]
  (when (and (:tenant state) (:team-id state))
    [(directory/upsert (directory/team (:tenant state) (:team-id state))
                       :schedules
                       (assoc (select-keys state schedule-row-keys)
                              :id (:id state)
                              :version (:version state)))]))

(defn workflows-by-team
  "{[tenant team-id] [workflow-spec]} of every team of the given tenants."
  [system tenants]
  (into {}
        (for [tenant tenants
              team (platform/teams system tenant)
              :let [team-id (:id team)]]
          [[tenant team-id] (vec (definitions/workflows system tenant team-id))])))

(defn- all-tenants
  [system]
  (map :name (platform/tenants system)))

(defn- schedule-states
  [ctx identities]
  (into {}
        (map (juxt :schedule-key #(dissoc % :id :version)))
        (directory/rows-of ctx identities :schedules)))

(defn list-schedules
  "The schedules of the caller's tenant, for its admins."
  [ctx _query]
  (roles/require-tenant-admin! ctx)
  (let [tenant
        (roles/tenant-of ctx)

        states
        (schedule-states ctx (directory/identities ctx tenant "team"))]
    (vec (sort-by :id
                  (for [[k schedule] (desired-schedules (workflows-by-team (system/of ctx) [tenant]))
                        :let [state (get states k)]]
                    {:id k
                     :kind (keyword (string/replace (:kind schedule) "_" "-"))
                     :team (:team schedule)
                     :workflow (:workflow schedule)
                     :next-run (next-run state schedule)
                     :last-run (:last-run state)
                     :paused false})))))

(defn get-schedule-states
  [ctx _query]
  (vec (vals (schedule-states ctx (directory/all-identities ctx "team")))))

(defn register
  [ctx]
  (let [by-trigger
        (fn [_ctx cmd]
          (trigger-id (get-in cmd [:attrs :tenant]) (get-in cmd [:attrs :team-id]) (get-in cmd [:attrs :workflow-id])))

        by-schedule
        (fn [_ctx cmd] (schedule-id (get-in cmd [:attrs :schedule-key])))]
    (-> ctx
        (edd/reg-cmd :mark-trigger-items-seen mark-seen
                     :id-fn by-trigger
                     :auth roles/platform-only)
        (edd/reg-event :trigger-items-seen apply-seen)
        (edd/reg-query :get-trigger-items get-trigger-items
                       :consumes [:map [:tenant :string] [:team-id :string] [:workflow-id :string]]
                       :auth roles/platform-only)
        (edd/reg-cmd :register-schedule register-schedule
                     :id-fn by-schedule
                     :auth roles/platform-only)
        (edd/reg-cmd :record-schedule-run record-schedule-run
                     :id-fn by-schedule
                     :auth roles/platform-only)
        (edd/reg-event :schedule-registered apply-registered)
        (edd/reg-event :schedule-ran apply-ran)
        (edd/reg-query :get-schedule-states get-schedule-states
                       :auth roles/platform-only)
        (edd/reg-query :list-schedules list-schedules
                       :auth roles/people)
        (directory/reg-rows #{:schedule-registered :schedule-ran} directory-rows))))

(defn- start-run!
  "Starts a run with a derived id unless it exists; returns the id."
  [system run-id attrs]
  (when-not (runs-edd/run-state system run-id)
    (client/command (:settings system) {:cmd-id :start-run :id run-id :attrs attrs}))
  run-id)

(defn- team-credential!
  "{:secret :username} of a credential as the team's platform-started runs
   see it: the team's value, else the tenant's."
  [system tenant team-id secret-name]
  (let [value
        (:value (credentials/resolve-credential system {:tenant tenant :team-id team-id :user nil} secret-name))]
    (if (:secret value)
      value
      (throw (ex-info (str "Secret " secret-name " is not set") {:permanent true})))))

(def scm-keys
  "The SCM source a pull_request trigger polls, as runs record it."
  [:provider :repository :url :web-url :credentials])

(defn new-pull-requests
  "Starts a run for every open pull request the trigger has not seen; returns their ids."
  [system tenant team-id workflow-id]
  (let [{:keys [trigger]}
        (definitions/workflow system tenant team-id workflow-id)]
    (if-not (= "pull_request" (:type trigger))
      []
      (let [provider
            (scm/provider system trigger (team-credential! system tenant team-id (:credentials trigger)))

            targets
            (set (:target-branches trigger))

            open-prs
            (filter #(or (empty? targets) (contains? targets (:destination-branch %)))
                    ((:open-pull-requests provider) (:repository trigger)))

            seen
            (set (client/query (:settings system) {:query-id :get-trigger-items
                                                   :tenant tenant
                                                   :team-id team-id
                                                   :workflow-id workflow-id}))

            mark!
            (fn [items]
              (client/command (:settings system) {:cmd-id :mark-trigger-items-seen
                                                  :attrs {:tenant tenant :team-id team-id :workflow-id workflow-id :items items}}))

            key
            (trigger-key tenant team-id workflow-id)]
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
                                        {:tenant tenant
                                         :team-id team-id
                                         :workflow-id workflow-id
                                         :trigger {:type "pull_request"
                                                   :scm (select-keys trigger scm-keys)
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
  [system tenant team-id workflow-id due-at]
  (let [{:keys [trigger]}
        (definitions/workflow system tenant team-id workflow-id)]
    (start-run! system
                (uuid/named (str "schedule-run:" (schedule-key tenant team-id workflow-id) ":" due-at))
                {:tenant tenant
                 :team-id team-id
                 :workflow-id workflow-id
                 :trigger {:type "schedule"}
                 :inputs (or (:inputs trigger) {})
                 :created-by (str auth/platform-user ":schedule")})))

(defn- fire!
  [system {:keys [kind tenant team workflow]} due-at]
  (if (= "pull_request" kind)
    (new-pull-requests system tenant team workflow)
    [(scheduled-run system tenant team workflow due-at)]))

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
          (for [[k schedule] (desired-schedules (workflows-by-team system (all-tenants system)))
                :when (contains? kinds (:kind schedule))
                :let [state (get states k)]]
            (if-not (:registered-at state)
              (do
                (client/command (:settings system) {:cmd-id :register-schedule
                                                    :attrs {:schedule-key k :tenant (:tenant schedule) :team-id (:team schedule)}})
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
                                        :attrs {:schedule-key k
                                                :tenant (:tenant schedule)
                                                :team-id (:team schedule)
                                                :run-ids run-ids
                                                :error error}})
    (when (seq run-ids)
      (workers/kick!))
    run-ids))
