(ns manager-svc.runs.executor
  "Carries runs forward, replacing the Temporal RunWorkflow. Each pass reads a
   run, performs the side effect its status calls for and records the outcome
   with a lifecycle command:

     :queued / :preparing  prepare the workspace, create the session      -> :running
     :running              wait for the session, prompt the agent         -> :awaiting-result
                           (interactive runs complete here)
     :awaiting-result      wait for the submission; nudge the agent, fall
                           back to the next model; finalize the result    -> :finalizing
     :finalizing           stop the session, follow CI or complete        -> :following-ci | :completed
     :following-ci         poll the build, start the failure follow-up    -> :completed

   Finished runs still get their session released and the team notified.
   Everything a pass needs is on the run aggregate, so passes are idempotent
   and the executor simply picks up where it was after a restart: a
   conversation is labelled on the session before the agent is prompted, so
   a repeated pass reuses it instead of prompting a second agent. A failing
   step is retried on the next pass, up to three times in a row, unless the
   error is permanent (bad inputs, missing definitions). Runs advance on the
   bounded :runs/executor pool."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [lambda.uuid :as uuid]
            [manager-svc.auth :as auth]
            [manager-svc.client :as client]
            [manager-svc.config :as config]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.http :as http]
            [manager-svc.opencode :as opencode]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.ci :as ci]
            [manager-svc.runs.definitions :as definitions]
            [manager-svc.runs.edd :as edd]
            [manager-svc.runs.handlers :as handlers]
            [manager-svc.runs.model :as model]
            [manager-svc.runs.run :as run]
            [manager-svc.runs.sandbox :as sandbox]
            [manager-svc.runs.submissions :as submissions]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.time :as time])
  (:import [java.util.concurrent ExecutorService RejectedExecutionException]))

(def ready-timeout-seconds (* 30 60))

(def result-timeout-seconds (* 3 60 60))

(def max-nudges 2)

(def idle-grace-seconds 20)

(def submission-grace-seconds 120)

(def opencode-retry-limit 3)

(def step-attempts 3)

(def max-passes 25)

(def conversation-label "conversation")

(def attempt-label
  "Session label <model-index>:<conversation> of the conversation the run
   prompts with its current model."
  "run-conversation")

(def nudge-text
  "You have not submitted your result yet. Call the `%s` tool now with your complete result.")

(defn session-id-for
  "The session of a run; derived from the run so preparing twice finds it."
  [run-id]
  (uuid/named (str "run-session:" run-id)))

(defn permanent
  [message]
  (ex-info message {:permanent true}))

(defn- seconds-since
  [iso now]
  (if iso
    (time/seconds-between iso now)
    Long/MAX_VALUE))

(defn- record!
  [system run cmd-id attrs]
  (client/command (:settings system) (cond-> {:cmd-id cmd-id :id (:id run)}
                                       attrs (assoc :attrs attrs))))

(defn- workflow!
  [system run]
  (or (definitions/workflow system (:team-id run) (:workflow-id run))
      (throw (permanent (str "Workflow " (:team-id run) "/" (:workflow-id run) " does not exist or is invalid")))))

(defn owner
  [settings run]
  (let [sub
        (or (model/person run) auth/platform-user)]
    {:sub sub
     :email (str sub "@" (:email-domain settings))
     :name sub}))

(defn author
  [settings run]
  (if-let [person (model/person run)]
    [(str person " (via Agent Platform)") (str person "@" (:email-domain settings))]
    ["Agent Platform" (str "agent-platform@" (:email-domain settings))]))

(defn options
  [workflow run]
  (let [scm
        (get-in run [:trigger :scm])]
    (cond-> (:options workflow)
      scm (assoc :scm scm))))

(defn run-context
  [system run workflow]
  (let [session-id
        (or (:session-id run) (session-id-for (:id run)))

        session
        (when (:session-id run)
          (sessions/get-session system session-id))]
    {:system system
     :run run
     :team (definitions/team system (:team-id run))
     :workflow workflow
     :options (options workflow run)
     :inputs (:inputs run)
     :workspace (str (sessions/workspace-dir system session-id))
     :session-url (if session (str (sessions/entry-url system session)) "")
     :author (author (:settings system) run)
     :record! (fn [cmd-id attrs] (record! system run cmd-id attrs))}))

(defn session-title
  [workflow run]
  (let [pull-request
        (get-in run [:inputs :pull-request])

        title
        (get-in run [:inputs :title])]
    (cond
      pull-request (str (:name workflow) " · PR #" pull-request)
      (not (string/blank? title)) title
      :else (:name workflow))))

(defn visibility
  [workflow activity run]
  (let [team?
        (= "team" (:visibility workflow))]
    (cond
      (and (:interactive activity) (not (model/platform-created? run))) (if team? :private :public)
      team? :team
      :else :public)))

(defn agent-prompt
  [system team-id agent options]
  (let [base
        (or (:prompt agent)
            (platform/read-team-file system team-id (:prompt-file agent)))]
    (if-let [instructions (:instructions-file options)]
      (str base "\n\n" (platform/read-team-file system team-id instructions))
      base)))

(defn check-session-limit!
  [system team-id]
  (when-let [message
             (run/session-limit-error system team-id (sessions/running-count system team-id))]
    (throw (ex-info message {:status 422 :message message}))))

(declare release-session!)

(defn- release-if-finished!
  "A run cancelled while its session was being prepared releases the
   session right away; nothing else would, as the run never recorded it."
  [system run-id session-id]
  (let [current
        (edd/run-state system run-id)]
    (when (and current (model/finished? current) (nil? (:session-id current)))
      (release-session! system current session-id))))

(defn- prepare!
  [system run]
  (when (= :queued (:status run))
    (record! system run :mark-run-preparing nil))
  (let [workflow
        (workflow! system run)

        team-id
        (:team-id run)

        agent
        (definitions/agent-spec system team-id (:agent workflow))

        harness
        (definitions/harness-spec system team-id (:harness workflow))

        _
        (when-not (and agent harness)
          (throw (permanent "The workflow's agent or harness is missing or invalid")))

        activity
        (platform/activity system (:activity run))

        session-id
        (session-id-for (:id run))

        existing
        (sessions/get-session system session-id)]
    (when-not existing
      (check-session-limit! system team-id)
      (let [ctx
            (run-context system run workflow)

            settings
            (:settings system)]
        (handlers/prepare! ctx)
        (sessions/create-session!
         system
         {:id session-id
          :identity (owner settings run)
          :name (session-title workflow run)
          :description (:description workflow)
          :visibility (visibility workflow activity run)
          :team-id team-id
          :run-id (:id run)
          :activity (:activity run)
          :labels {:workflow (:workflow-id run) :activity (:activity run)}
          :sandbox (sandbox/sandbox settings
                                    {:session-id session-id
                                     :agent-id (:agent workflow)
                                     :agent agent
                                     :prompt (agent-prompt system team-id agent (:options ctx))
                                     :harness harness
                                     :interactive (:interactive activity)
                                     :result-tool (:result-tool activity)
                                     :password (sessions/secret-key system session-id)
                                     :team-id team-id
                                     :run-id (:id run)
                                     :skills (when (seq (:skills harness))
                                               (platform/read-skills system team-id (:skills harness)))})})
        (sessions/hand-over! system session-id)))
    (record! system run :record-run-session {:session-id session-id
                                             :interactive (boolean (:interactive activity))
                                             :models (vec (get-in harness [:llm :models]))})
    (release-if-finished! system (:id run) session-id)
    :progress))

(defn- opencode-access
  [system run]
  (when-not (sessions/get-session system (:session-id run))
    (throw (permanent (str "Session " (:session-id run) " not found"))))
  [(sessions/session-base-url system (:session-id run))
   (sessions/secret-key system (:session-id run))])

(defn- waiting-for-session
  "Why a run can't talk to its session yet; requests a start when the
   session stopped meanwhile. Throws when it can't start."
  [system run now]
  (let [session
        (sessions/get-session system (:session-id run))]
    (cond
      (nil? session)
      (throw (permanent (str "Session " (:session-id run) " not found")))

      (:error session)
      (throw (permanent (str "The session could not start: " (:error session))))

      (> (seconds-since (:status-at run) now) ready-timeout-seconds)
      (throw (permanent "The session did not become ready within 30 minutes"))

      (and (nil? (:pending session)) (:stopped-at session))
      (do
        (sessions/start-session! system (:session-id run))
        :wait)

      :else
      :wait)))

(defn reusable-conversation
  "The conversation a previous pass created for the run's current model, or nil."
  [session run]
  (let [[index conversation]
        (some-> (get-in session [:labels (keyword attempt-label)]) (string/split #":" 2))]
    (when (and conversation (= (str (or (:model-index run) 0)) index))
      conversation)))

(defn- conversation!
  "The run's conversation for its current model: the one a previous pass
   labelled, else a new one, labelled before anyone prompts it."
  [system run workflow base-url password]
  (or (reusable-conversation (sessions/get-session system (:session-id run)) run)
      (let [conversation
            (opencode/create-session! base-url password (:name workflow))]
        (sessions/label! system (:session-id run) conversation-label conversation)
        (sessions/label! system (:session-id run) attempt-label (str (or (:model-index run) 0) ":" conversation))
        conversation)))

(defn- start-conversation!
  [system run now]
  (if-not (sessions/ready? system (:session-id run))
    (waiting-for-session system run now)
    (let [workflow
          (workflow! system run)

          model
          (nth (:models run) (or (:model-index run) 0))

          text
          (handlers/prompt (run-context system run workflow))]
      (if (nil? text)
        (record! system run :complete-run nil)
        (let [[base-url password]
              (opencode-access system run)

              conversation
              (conversation! system run workflow base-url password)]
          (when (empty? (opencode/messages base-url password conversation))
            (opencode/prompt-async! base-url password conversation (str sandbox/provider-id "/" model) text))
          (record! system run :record-run-conversation {:conversation conversation :model model})
          (when (:interactive run)
            (record! system run :complete-run nil))))
      :progress)))

(defn- model-unavailable
  [message]
  (ex-info message {:model-unavailable true}))

(defn conversation-state
  "Whether the agent is still working (:busy) or has stopped (:idle); throws
   {:model-unavailable true} when the model can't serve the conversation."
  [system run]
  (let [[base-url password]
        (opencode-access system run)

        status
        (get (opencode/session-statuses base-url password) (:conversation run))]
    (when (= "retry" (:type status))
      (when (or (= "free_tier_limit" (get-in status [:action :reason]))
                (>= (or (:attempt status) 0) opencode-retry-limit))
        (throw (model-unavailable (or (:message status) "The model is not available")))))
    (if (and status (not= "idle" (:type status)))
      :busy
      (do
        (try
          (opencode/last-reply base-url password (:conversation run))
          (catch clojure.lang.ExceptionInfo e
            (if (:opencode-error (ex-data e))
              (throw (model-unavailable (ex-message e)))
              (throw e))))
        :idle))))

(defn- busy?
  [system run]
  (try
    (= :busy (conversation-state system run))
    (catch Exception _
      false)))

(defn- finalize!
  [system run]
  (let [workflow
        (workflow! system run)

        {:keys [tool payload]}
        (:submission run)

        submission
        (submissions/validate-submission tool payload)

        result
        (handlers/finalize! (run-context system run workflow) submission)]
    (record! system run :finalize-run {:result result})
    :progress))

(defn- no-result
  [reasons]
  (str "No result: " (if (seq reasons)
                       (string/join "; " reasons)
                       "the agent finished without submitting a result")))

(defn- abort-quietly!
  [system run]
  (try
    (let [[base-url password] (opencode-access system run)]
      (opencode/abort! base-url password (:conversation run)))
    (catch Exception e
      (log/warn "Aborting conversation failed" (:id run) (ex-message e)))))

(defn- next-model!
  [system run message]
  (log/warn "Model" (:model run) "unavailable for run" (:id run) message)
  (abort-quietly! system run)
  (if (< (inc (or (:model-index run) 0)) (count (:models run)))
    (record! system run :record-model-unavailable {:model (:model run) :message message})
    (record! system run :fail-run {:message (no-result (conj (vec (:unavailable run))
                                                             (str (:model run) ": " message)))}))
  :progress)

(defn- nudge!
  [system run]
  (let [[base-url password]
        (opencode-access system run)

        tool
        (:result-tool (platform/activity system (:activity run)))]
    (opencode/prompt-async! base-url password (:conversation run)
                            (str sandbox/provider-id "/" (:model run))
                            (format nudge-text tool))
    (record! system run :record-run-nudge nil)
    :progress))

(defn- await-result!
  [system run now]
  (cond
    (:submission run)
    (if (and (busy? system run)
             (< (seconds-since (:submitted-at run) now) submission-grace-seconds))
      :wait
      (finalize! system run))

    (< (seconds-since (or (:nudged-at run) (:status-at run)) now) idle-grace-seconds)
    :wait

    :else
    (let [state
          (try
            (conversation-state system run)
            (catch clojure.lang.ExceptionInfo e
              (if (:model-unavailable (ex-data e))
                (ex-message e)
                (throw e))))]
      (cond
        (string? state)
        (next-model! system run state)

        (= :busy state)
        (if (> (seconds-since (:status-at run) now) result-timeout-seconds)
          (throw (permanent "Timed out waiting for the agent's result"))
          :wait)

        (< (or (:nudges run) 0) max-nudges)
        (nudge! system run)

        :else
        (record! system run :fail-run {:message (no-result (:unavailable run))})))))

(defn- release-session!
  [system run session-id]
  (let [session
        (sessions/get-session system session-id)

        stop?
        (and session
             (case (:status run)
               :cancelled (not= "session" (some-> (:activity session) name))
               :completed (not (:interactive run))
               :failed (not (:interactive run))
               (not (:interactive run))))]
    (when stop?
      (sessions/stop-session! system session-id))
    (record! system run :record-run-session-released nil)))

(defn- start-follow-up!
  [system run ci-config]
  (let [follow-up-workflow
        (or (definitions/workflow system (:team-id run) (:on-failure ci-config))
            (throw (permanent (str "Workflow " (:team-id run) "/" (:on-failure ci-config)
                                   " does not exist or is invalid"))))

        build-number
        (get-in run [:result :ci :build :number])

        run-id
        (uuid/named (str "ci-follow-up:" (:id run) ":" build-number))]
    (when-not (edd/run-state system run-id)
      (client/command (:settings system) {:cmd-id :start-run
                                          :id run-id
                                          :attrs (ci/failure-run system run ci-config follow-up-workflow)}))
    run-id))

(defn- after-finalize!
  [system run]
  (when-not (:interactive run)
    (when-not (:session-released run)
      (release-session! system run (:session-id run))))
  (let [workflow
        (definitions/workflow system (:team-id run) (:workflow-id run))

        ci-config
        (when workflow (ci/follow-config workflow run))]
    (if ci-config
      (record! system run :follow-run-ci {:ci {:job (ci/job-name ci-config (get-in run [:result :pull-request]))
                                               :state "waiting"
                                               :build nil
                                               :timeout-minutes (:timeout-minutes ci-config)}})
      (record! system run :complete-run nil))
    :progress))

(defn ci-command
  "Polls that change what a run list shows (the state or the build) update
   the run's CI; the others only record the check."
  [current changes]
  (if (and (= (:state current) (:state changes))
           (= (get-in current [:build :number]) (get-in changes [:build :number])))
    :check-run-ci
    :update-run-ci))

(defn- follow-ci!
  [system run now]
  (let [workflow
        (definitions/workflow system (:team-id run) (:workflow-id run))

        ci-config
        (merge (when workflow (ci/follow-config workflow run))
               (select-keys (get-in run [:result :ci]) [:timeout-minutes]))]
    (cond
      (nil? (:mcp-server ci-config))
      (record! system run :complete-run nil)

      (ci/timed-out? run ci-config now)
      (do
        (record! system run :update-run-ci {:ci {:state "timeout" :detail "Stopped waiting for the build"}})
        (record! system run :complete-run nil))

      (not (ci/poll-due? run now))
      :wait

      :else
      (let [changes
            (ci/poll system run ci-config)

            polled
            (update-in run [:result :ci] merge changes)]
        (record! system run (ci-command (get-in run [:result :ci]) changes) {:ci changes})
        (case (:state changes)
          "passed" (record! system run :complete-run nil)
          "failed" (do
                     (when (:on-failure ci-config)
                       (record! system run :update-run-ci
                                {:ci {:follow-up-run (start-follow-up! system polled ci-config)}}))
                     (record! system run :complete-run nil))
          :wait)))))

(defn notify!
  [system run]
  (let [team
        (definitions/team system (:team-id run))

        webhook
        (get-in team [:notifications :webhook])

        wanted
        (set (map name (or (get-in team [:notifications :on]) ["completed" "failed"])))

        url
        (when (and webhook (contains? wanted (name (:status run))))
          (credentials/secret system webhook))]
    (when url
      (let [settings
            (:settings system)

            link
            (str (config/public-url settings (:domain settings)) "/runs/" (:id run))

            verdict
            (get-in run [:result :review :verdict])

            text
            (str (:team-id run) "/" (:workflow-id run) " " (name (:status run))
                 (when verdict (str " (" verdict ")")) ": " link)]
        (try
          (http/request! {:method :post
                          :url url
                          :json {:text text
                                 :run {:id (str (:id run))
                                       :team_id (:team-id run)
                                       :workflow_id (:workflow-id run)
                                       :activity (:activity run)
                                       :status (name (:status run))
                                       :error (:error run)}}
                          :timeout-seconds 10})
          (catch Exception e
            (log/warn "Webhook for run" (:id run) "failed:" (ex-message e))))))))

(defn- session-of
  "The run's session; a run cancelled while preparing never recorded it, so
   its derived session is looked up."
  [system run]
  (or (:session-id run)
      (let [derived
            (session-id-for (:id run))]
        (when (sessions/get-session system derived)
          derived))))

(defn- housekeeping!
  [system run]
  (try
    (when-let [session-id
               (when-not (:session-released run)
                 (session-of system run))]
      (release-session! system run session-id))
    (catch Exception e
      (log/warn e "Releasing the session of run" (:id run) "failed")
      (record! system run :record-run-session-released nil)))
  (when (and (contains? #{:completed :failed} (:status run)) (not (:notified run)))
    (notify! system run)
    (record! system run :record-run-notified nil))
  :done)

(defn step!
  "Performs the next step of a run: :progress (look again), :wait or :done."
  [system run]
  (let [now
        (time/now)]
    (case (:status run)
      (:queued :preparing) (prepare! system run)
      :running (start-conversation! system run now)
      :awaiting-result (await-result! system run now)
      :finalizing (after-finalize! system run)
      :following-ci (follow-ci! system run now)
      (housekeeping! system run))))

(defn- permanent?
  [e]
  (let [{:keys [permanent status]}
        (ex-data e)]
    (or permanent
        (and (number? status) (< status 500)))))

(defn advance-once!
  [system run]
  (try
    (let [outcome
          (step! system run)]
      (when (and (pos? (or (:attempts run) 0)) (not (model/finished? run)))
        (record! system run :record-run-step-recovered nil))
      (if (keyword? outcome) outcome :progress))
    (catch Exception e
      (let [message
            (or (ex-message e) (str (class e)))]
        (log/warn e "Run" (:id run) "step" (:status run) "failed")
        (if (or (permanent? e) (>= (inc (or (:attempts run) 0)) step-attempts))
          (record! system run :fail-run {:message message})
          (record! system run :record-run-step-error {:step (:status run) :message message}))
        :wait))))

(defn advance!
  "Moves a run forward until it has to wait."
  [system run-id]
  (loop [passes 0]
    (let [current
          (edd/run-state system run-id)]
      (when (and current
                 (model/needs-work? current)
                 (< passes max-passes)
                 (= :progress (advance-once! system current)))
        (recur (inc passes))))))

(defonce ^:private in-flight
  (atom #{}))

(defn- claim!
  [run-id]
  (let [[before _]
        (swap-vals! in-flight conj run-id)]
    (not (contains? before run-id))))

(defn- advance-task
  [system run-id]
  (fn []
    (try
      (advance! system run-id)
      (catch Throwable t
        (log/error t "Advancing run failed" run-id))
      (finally
        (swap! in-flight disj run-id)))))

(defn execute!
  "The executor job: advances every run that needs work on the :runs/executor pool."
  [system]
  (doseq [{:keys [id]} (client/query (:settings system) {:query-id :list-pending-runs})
          :when (claim! id)]
    (try
      (.execute ^ExecutorService (:runs/executor system) ^Runnable (advance-task system id))
      (catch RejectedExecutionException _
        (swap! in-flight disj id)))))
