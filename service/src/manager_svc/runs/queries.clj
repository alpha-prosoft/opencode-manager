(ns manager-svc.runs.queries
  (:require [clojure.tools.logging :as log]
            [edd.common :as common]
            [edd.core :as edd]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.directory :as directory]
            [manager-svc.errors :as errors]
            [manager-svc.observability.api :as observability]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.definitions :as definitions]
            [manager-svc.runs.model :as model]
            [manager-svc.runs.run :as run]
            [manager-svc.runs.scm :as scm]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.system :as system]))

(def max-limit 500)

(def empty-usage
  {:calls 0 :prompt-tokens 0 :completion-tokens 0 :cached-tokens 0 :errors 0 :avg-latency-ms 0})

(defn- team-rows
  [ctx team-ids]
  (directory/rows-of ctx (map directory/team team-ids) :runs))

(defn- all-rows
  "Rows of every team directory, also of teams that left the registry."
  [ctx]
  (directory/rows-of ctx (directory/identities ctx "team") :runs))

(defn- newest-first
  [runs]
  (sort-by :created-at #(compare %2 %1) runs))

(defn list-runs
  [ctx {:keys [team-id limit]}]
  (let [system
        (system/of ctx)

        teams
        (set (platform/teams-of system (auth/user-id ctx)))

        wanted
        (if team-id (filter #{team-id} teams) teams)

        runs
        (take (min max-limit (or limit 100))
              (newest-first (team-rows ctx wanted)))

        names
        (definitions/workflow-names system (set (map :team-id runs)))]
    (mapv (fn [r] (model/listed r (get names [(:team-id r) (:workflow-id r)])))
          runs)))

(defn get-run-state
  [ctx {:keys [run-id]}]
  (let [found
        (common/get-by-id ctx {:id (run/->uuid run-id)})]
    (when (:status found)
      found)))

(defn get-run-source
  [ctx query]
  {:run (get-run-state ctx query)})

(defn- guarded
  [what fallback f]
  (try
    (f)
    (catch Exception e
      (log/warn e "Reading" what "failed")
      fallback)))

(defn- viewable-session
  [system user session-id]
  (when session-id
    (guarded "session" nil
             #(let [session (sessions/get-session system session-id)]
                (when (and session (sessions/can-view? system session user))
                  (sessions/describe-session system session user))))))

(defn get-run
  [ctx {:keys [run-id]}]
  (let [system
        (system/of ctx)

        user
        (auth/user-id ctx)

        found
        (run/visible! system user (get-run-state ctx {:run-id run-id}))

        names
        (definitions/workflow-names system #{(:team-id found)})]
    (model/detail found
                  (get names [(:team-id found) (:workflow-id found)])
                  (viewable-session system user (:session-id found))
                  (guarded "usage" empty-usage
                           #(or (observability/usage-for-run system (:id found)) empty-usage)))))

(defn- since
  [rows from]
  (vec (for [row rows
             :when (>= (compare (str (:created-at row)) from) 0)]
         (select-keys row [:id :status :created-by :created-at]))))

(defn list-runs-since
  "Runs of every team created since an ISO timestamp."
  [ctx query]
  (since (all-rows ctx) (:since query)))

(defn list-people-runs-since
  "Runs people started since an ISO timestamp, from their own directories."
  [ctx query]
  (since (directory/rows-of ctx (directory/identities ctx "user") :runs) (:since query)))

(defn list-pending-runs
  "Rows of the runs that need work; the executor reads each run itself."
  [ctx _query]
  (filterv :needs-work (all-rows ctx)))

(defn count-running-sessions
  "{:count n} of a team's running sessions; the dependency of :start-run."
  [ctx {:keys [team-id]}]
  {:count (sessions/running-count (system/of ctx) team-id)})

(defn scm-options
  "Where a workflow's pull requests live: options.scm, or the delivery target
   with the repository credential."
  [workflow]
  (let [options
        (:options workflow)

        trigger
        (:trigger workflow)]
    (cond
      (:scm options) (:scm options)
      (:pull-request options) (assoc (:pull-request options)
                                     :credentials (get-in options [:repository :credentials]))
      (= "pull_request" (:type trigger)) (select-keys trigger [:provider :repository :credentials])
      :else nil)))

(defn list-open-pull-requests
  [ctx {:keys [team-id workflow-id]}]
  (let [system
        (system/of ctx)

        user
        (auth/user-id ctx)

        _
        (when-not (contains? (set (platform/teams-of system user)) team-id)
          (errors/not-found! "Team not found"))

        workflow
        (or (definitions/workflow system team-id workflow-id)
            (errors/not-found! "Workflow not found"))

        {:keys [provider repository credentials]}
        (scm-options workflow)

        secret
        (when credentials
          (get-in (credentials/resolve-credential system credentials user) [:value :secret]))]
    (if (and provider repository secret)
      (let [items
            (try
              ((:open-pull-requests (scm/provider system provider secret)) repository)
              (catch Exception e
                (log/warn e "Listing pull requests failed")
                (errors/fail! 502 "Could not list pull requests")))]
        (mapv (fn [pr] {:id (:id pr) :title (:title pr) :author (:author pr) :branch (:source-branch pr)})
              (sort-by #(- (:id %)) items)))
      [])))

(def ^:private user-only
  {:role [:user]})

(defn register
  [ctx]
  (-> ctx
      (edd/reg-query :list-runs list-runs
                     :consumes [:map [:team-id {:optional true} [:maybe :string]] [:limit {:optional true} [:maybe :int]]]
                     :auth user-only)
      (edd/reg-query :get-run get-run
                     :consumes [:map [:run-id any?]]
                     :auth user-only)
      (edd/reg-query :get-run-source get-run-source
                     :consumes [:map [:run-id any?]]
                     :auth {:role [:non-interactive]})
      (edd/reg-query :list-runs-since list-runs-since
                     :consumes [:map [:since string?]]
                     :auth {:role [:non-interactive]})
      (edd/reg-query :list-people-runs-since list-people-runs-since
                     :consumes [:map [:since string?]]
                     :auth {:role [:non-interactive]})
      (edd/reg-query :list-pending-runs list-pending-runs
                     :auth {:role [:non-interactive]})
      (edd/reg-query :count-running-sessions count-running-sessions
                     :consumes [:map [:team-id :string]]
                     :auth {:role [:non-interactive]})
      (edd/reg-query :list-open-pull-requests list-open-pull-requests
                     :consumes [:map [:team-id :string] [:workflow-id :string]]
                     :auth user-only)))
