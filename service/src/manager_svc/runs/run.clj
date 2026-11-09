(ns manager-svc.runs.run
  "The run aggregate: a workflow run from :queued to :completed, :failed or
   :cancelled. People start, retry and cancel runs; the executor records each
   step it carries out with the :non-interactive lifecycle commands. Lifecycle
   commands on a finished run produce no event, so a step that races a cancel
   changes nothing."
  (:require [clojure.string :as string]
            [edd.core :as edd]
            [edd.el.ctx :as el-ctx]
            [manager-svc.auth :as auth]
            [manager-svc.directory :as directory]
            [manager-svc.errors :as errors]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.definitions :as definitions]
            [manager-svc.runs.model :as model]
            [manager-svc.system :as system]
            [manager-svc.time :as time]))

(defn ->uuid
  [value]
  (cond
    (uuid? value) value
    (string? value) (parse-uuid value)
    :else nil))

(defn session-limit-error
  "Why the team may not start another session, or nil."
  [system team-id running]
  (let [limit
        (get-in (definitions/team system team-id) [:budget :max-running-sessions])]
    (when (and limit (>= running limit))
      (str "Team " team-id " already has " running
           " running sessions (limit " limit "). Stop one and retry."))))

(defn running-sessions-dep
  "The team's running session count, read before :start-run runs, so the
   command itself stays pure."
  [_deps cmd]
  {:query-id :count-running-sessions
   :team-id (get-in cmd [:attrs :team-id])})

(defn- workflow!
  [system team-id workflow-id]
  (or (definitions/workflow system team-id workflow-id)
      (errors/invalid! (str "Workflow " team-id "/" workflow-id " does not exist or is invalid"))))

(defn- scm-of
  [{:keys [trigger]}]
  (when (= "pull_request" (:type trigger))
    (select-keys trigger [:provider :repository :credentials])))

(defn created-event
  "The :run-created event of a new run; validates the workflow and inputs."
  [system id {:keys [team-id workflow-id inputs trigger created-by platform-started]}]
  (let [workflow
        (workflow! system team-id workflow-id)

        {:keys [value errors]}
        (platform/validate-inputs system (:activity workflow) inputs (:options workflow))

        _
        (when (seq errors)
          (errors/invalid! (string/join "; " errors)))

        stamp
        (time/now)]
    {:event-id :run-created
     :id id
     :attrs {:team-id team-id
             :workflow-id workflow-id
             :activity (:activity workflow)
             :trigger trigger
             :inputs value
             :created-by created-by
             :platform-started (boolean platform-started)
             :at stamp}}))

(defn- ensure-new!
  [ctx]
  (when (:status (el-ctx/get-aggregate ctx))
    (errors/fail! 409 "Run already exists")))

(defn platform-start
  "What the platform starts: a run on its own (team credentials), or one
   continuing the run of :person, which then acts for that person."
  [{:keys [person created-by] :as attrs}]
  (let [person
        (when (auth/person-name? person) person)]
    (merge {:trigger {:type "manual"}}
           (dissoc attrs :person)
           {:created-by (or person created-by auth/platform-user)
            :platform-started (nil? person)})))

(defn start-run
  [ctx {:keys [id attrs]}]
  (ensure-new! ctx)
  (let [system
        (system/of ctx)

        {:keys [team-id workflow-id]}
        attrs]
    (if (auth/platform? ctx)
      (created-event system id (platform-start attrs))
      (let [user
            (auth/user-id ctx)

            _
            (when-not (platform/role system user team-id)
              (errors/not-found! "Team not found"))

            workflow
            (workflow! system team-id workflow-id)

            limit-error
            (session-limit-error system team-id (get-in ctx [:running-sessions :count] 0))

            _
            (when limit-error
              (errors/invalid! limit-error))

            scm
            (scm-of workflow)

            trigger
            (cond-> {:type "manual" :by user}
              scm (assoc :scm scm))]
        (created-event system id {:team-id team-id
                                  :workflow-id workflow-id
                                  :inputs (:inputs attrs)
                                  :trigger trigger
                                  :created-by user})))))

(defn visible!
  "The run when the person is a member of its team; 404 otherwise."
  [system user run]
  (if (and (:status run)
           (contains? (set (platform/teams-of system user)) (:team-id run)))
    run
    (errors/not-found! "Run not found")))

(defn retry-run
  [ctx {:keys [id]}]
  (ensure-new! ctx)
  (let [system
        (system/of ctx)

        user
        (auth/user-id ctx)

        source
        (visible! system user (get-in ctx [:source :run]))]
    (created-event system id {:team-id (:team-id source)
                              :workflow-id (:workflow-id source)
                              :inputs (:inputs source)
                              :trigger (assoc (:trigger source) :retry-of (:id source) :by user)
                              :created-by user})))

(defn cancel-run
  [ctx {:keys [id]}]
  (let [system
        (system/of ctx)

        user
        (auth/user-id ctx)

        run
        (visible! system user (el-ctx/get-aggregate ctx))]
    (when (and (not= user (:created-by run))
               (not= :admin (some-> (platform/role system user (:team-id run)) keyword)))
      (errors/forbidden! "Only the run's creator or a team admin can do that"))
    (when (model/finished? run)
      (errors/fail! 409 (str "Run is already " (name (:status run)))))
    {:event-id :run-cancelled
     :id id
     :attrs {:by user :at (time/now)}}))

(defn- lifecycle
  "A :non-interactive command producing event-id with the command's attrs,
   unless the run is finished (or missing)."
  [event-id & {:keys [when-finished]}]
  (fn [ctx {:keys [id attrs]}]
    (let [run
          (el-ctx/get-aggregate ctx)]
      (when (and (:status run)
                 (or when-finished (not (model/finished? run))))
        {:event-id event-id
         :id id
         :attrs (assoc (or attrs {}) :at (time/now))}))))

(defn submit-result
  [ctx {:keys [id attrs]}]
  (let [run
        (el-ctx/get-aggregate ctx)]
    (cond
      (not (contains? #{:running :awaiting-result} (:status run)))
      (errors/fail! 409 "No run is waiting for a result from this session; nothing was submitted")

      (:submission run)
      (errors/fail! 409 "A result was already submitted for this run")

      :else
      {:event-id :run-result-submitted
       :id id
       :attrs (assoc (select-keys attrs [:tool :payload]) :at (time/now))})))

(defn- touched
  [run {:keys [attrs]}]
  (assoc run :updated-at (:at attrs)))

(defn- transition
  [run event status]
  (assoc (touched run event)
         :status status
         :status-at (get-in event [:attrs :at])
         :attempts 0
         :last-step-error nil))

(defn- apply-created
  [_run {:keys [attrs] :as event}]
  (merge (transition {} event :queued)
         (dissoc attrs :at)
         {:session-id nil
          :error nil
          :result nil
          :created-at (:at attrs)
          :model-index 0
          :nudges 0
          :unavailable []
          :posted-comments []}))

(defn- apply-preparing
  [run event]
  (transition run event :preparing))

(defn- apply-session
  [run {:keys [attrs] :as event}]
  (merge (transition run event :running)
         (select-keys attrs [:session-id :interactive :models])))

(defn- apply-conversation
  [run {:keys [attrs] :as event}]
  (let [moved
        (if (:interactive run)
          (touched run event)
          (transition run event :awaiting-result))]
    (assoc moved
           :conversation (:conversation attrs)
           :model (:model attrs)
           :nudges 0
           :nudged-at nil)))

(defn- apply-nudge
  [run {:keys [attrs] :as event}]
  (assoc (touched run event)
         :nudges (inc (or (:nudges run) 0))
         :nudged-at (:at attrs)))

(defn- apply-model-unavailable
  [run {:keys [attrs] :as event}]
  (assoc (transition run event :running)
         :model-index (inc (or (:model-index run) 0))
         :conversation nil
         :unavailable (conj (vec (:unavailable run)) (str (:model attrs) ": " (:message attrs)))))

(defn- apply-submitted
  [run {:keys [attrs] :as event}]
  (assoc (touched run event)
         :submission (select-keys attrs [:tool :payload])
         :submitted-at (:at attrs)))

(defn- apply-comment-posted
  [run {:keys [attrs] :as event}]
  (update (touched run event)
          :posted-comments
          (fnil conj [])
          (select-keys attrs [:key :comment-id])))

(defn- apply-outcome
  [run {:keys [attrs] :as event}]
  (update (touched run event)
          :result
          merge
          (dissoc attrs :at)))

(defn- apply-finalized
  [run {:keys [attrs] :as event}]
  (assoc (transition run event :finalizing)
         :result (merge (:result run) (:result attrs))
         :error nil))

(defn- apply-session-released
  [run event]
  (assoc (touched run event) :session-released true))

(defn- apply-ci-followed
  [run {:keys [attrs] :as event}]
  (assoc-in (transition run event :following-ci)
            [:result :ci]
            (assoc (:ci attrs) :started-at (:at attrs) :checked-at (:at attrs))))

(defn- apply-step-recovered
  [run event]
  (assoc (touched run event) :attempts 0 :last-step-error nil))

(defn- apply-ci-updated
  [run {:keys [attrs] :as event}]
  (update-in (touched run event)
             [:result :ci]
             merge
             (:ci attrs)
             {:checked-at (:at attrs)}))

(defn- apply-completed
  [run event]
  (assoc (transition run event :completed)
         :result (or (:result run) {:session-id (:session-id run)})))

(defn- apply-failed
  [run {:keys [attrs] :as event}]
  (let [message
        (str (:message attrs))]
    (assoc (transition run event :failed)
           :error (subs message 0 (min 4000 (count message))))))

(defn- apply-cancelled
  [run {:keys [attrs] :as event}]
  (assoc (transition run event :cancelled)
         :error (str "Cancelled by " (:by attrs))
         :cancelled-by (:by attrs)))

(defn- apply-step-error
  [run {:keys [attrs] :as event}]
  (assoc (touched run event)
         :attempts (inc (or (:attempts run) 0))
         :last-step-error (select-keys attrs [:step :message :at])))

(defn- apply-notified
  [run event]
  (assoc (touched run event) :notified true))

(def ^:private platform-only
  {:role [:non-interactive]})

(def lifecycle-commands
  [[:mark-run-preparing :run-preparing apply-preparing]
   [:record-run-session :run-session-created apply-session]
   [:record-run-conversation :run-conversation-started apply-conversation]
   [:record-run-nudge :run-nudged apply-nudge]
   [:record-model-unavailable :run-model-unavailable apply-model-unavailable]
   [:record-review-comment :run-review-comment-posted apply-comment-posted]
   [:record-run-outcome :run-outcome-recorded apply-outcome]
   [:finalize-run :run-finalized apply-finalized]
   [:follow-run-ci :run-ci-followed apply-ci-followed]
   [:update-run-ci :run-ci-updated apply-ci-updated]
   [:check-run-ci :run-ci-checked apply-ci-updated]
   [:record-run-step-recovered :run-step-recovered apply-step-recovered]
   [:complete-run :run-completed apply-completed]
   [:fail-run :run-failed apply-failed]
   [:record-run-step-error :run-step-failed apply-step-error]])

(def after-finish-commands
  [[:record-run-session-released :run-session-released apply-session-released]
   [:record-run-notified :run-notified apply-notified]])

(def ^:private id-command
  [:map [:id uuid?]])

(defn source-dep
  "Wrapped as {:run}: edd treats a dependency answer with :error as failed."
  [_deps cmd]
  {:query-id :get-run-source
   :run-id (->uuid (get-in cmd [:attrs :source-id]))})

(def unlisted-events
  "Events that change nothing a run list shows."
  #{:run-nudged :run-step-failed :run-step-recovered :run-review-comment-posted :run-ci-checked})

(def event-ids
  (into #{:run-created :run-cancelled :run-result-submitted}
        (comp (map second) (remove unlisted-events))
        (concat lifecycle-commands after-finish-commands)))

(defn directory-rows
  "A run is listed in its team's directory and in that of the person it acts for."
  [run]
  (let [row
        (model/row run)

        person
        (model/person run)]
    (cond-> [(directory/upsert (directory/team (:team-id run)) :runs row)]
      person (conj (directory/upsert (directory/user person) :runs row)))))

(defn register
  [ctx]
  (let [with-user-commands
        (-> ctx
            (edd/reg-cmd :start-run start-run
                         :consumes [:map
                                    [:id uuid?]
                                    [:attrs [:map
                                             [:team-id :string]
                                             [:workflow-id :string]]]]
                         :deps [:running-sessions running-sessions-dep]
                         :auth {:role [:user :non-interactive]})
            (edd/reg-cmd :retry-run retry-run
                         :consumes [:map [:id uuid?] [:attrs [:map [:source-id any?]]]]
                         :deps [:source source-dep]
                         :auth {:role [:user]})
            (edd/reg-cmd :cancel-run cancel-run
                         :consumes id-command
                         :auth {:role [:user]})
            (edd/reg-cmd :submit-run-result submit-result
                         :consumes id-command
                         :auth platform-only)
            (edd/reg-event :run-created apply-created)
            (edd/reg-event :run-cancelled apply-cancelled)
            (edd/reg-event :run-result-submitted apply-submitted))

        with-lifecycle
        (reduce (fn [acc [cmd-id event-id apply-fn]]
                  (-> acc
                      (edd/reg-cmd cmd-id (lifecycle event-id)
                                   :consumes id-command
                                   :auth platform-only)
                      (edd/reg-event event-id apply-fn)))
                with-user-commands
                lifecycle-commands)]
    (directory/reg-rows
     (reduce (fn [acc [cmd-id event-id apply-fn]]
               (-> acc
                   (edd/reg-cmd cmd-id (lifecycle event-id :when-finished true)
                                :consumes id-command
                                :auth platform-only)
                   (edd/reg-event event-id apply-fn)))
             with-lifecycle
             after-finish-commands)
     event-ids
     directory-rows)))
