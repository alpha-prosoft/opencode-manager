(ns manager-svc.runs.queries
  (:require [clojure.tools.logging :as log]
            [edd.common :as common]
            [edd.core :as edd]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.directory :as directory]
            [manager-svc.errors :as errors]
            [manager-svc.observability.api :as observability]
            [manager-svc.platform.api :as platform]
            [manager-svc.roles :as roles]
            [manager-svc.runs.definitions :as definitions]
            [manager-svc.runs.model :as model]
            [manager-svc.runs.run :as run]
            [manager-svc.runs.scm :as scm]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.system :as system]))

(def max-limit 500)

(defn- team-rows
  [ctx tenant team-ids]
  (directory/tenant-rows ctx tenant (map #(directory/team tenant %) team-ids) :runs))

(defn- tenant-rows
  "Rows of every team of tenant, also of teams that left the registry."
  [ctx tenant]
  (directory/tenant-rows ctx tenant (directory/identities ctx tenant "team") :runs))

(defn- all-rows
  "Rows of every team of every tenant."
  [ctx]
  (directory/rows-of ctx (directory/all-identities ctx "team") :runs))

(defn- newest-first
  [runs]
  (sort-by :created-at #(compare %2 %1) runs))

(defn- of-project
  "rows of a project of team-id: project ids are only unique within a team,
   so :project-id counts only beside :team-id."
  [team-id project-id rows]
  (if (and team-id project-id)
    (filter #(= project-id (:project %)) rows)
    rows))

(defn list-runs
  "Runs of the caller's teams, newest first; of one team with :team-id, of
   one of its projects with :project-id as well."
  [ctx {:keys [team-id project-id limit]}]
  (let [system
        (system/of ctx)

        caller
        (roles/caller ctx)

        tenant
        (:tenant caller)

        teams
        (set (platform/teams-of system caller))

        wanted
        (if team-id (filter #{team-id} teams) teams)

        runs
        (take (min max-limit (or limit 100))
              (newest-first (of-project team-id project-id (team-rows ctx tenant wanted))))

        names
        (definitions/workflow-names system tenant (set (map :team-id runs)))]
    (mapv (fn [r] (model/listed r (get names [(:team-id r) (:workflow-id r)])))
          runs)))

(defn get-run-state
  [ctx {:keys [run-id]}]
  (let [found
        (common/get-by-id ctx {:id run-id})]
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
  [system caller session-id]
  (when session-id
    (guarded "session" nil
             #(let [session (sessions/get-session system session-id)]
                (when (and session (sessions/can-view? system session caller))
                  (sessions/describe-session system session caller))))))

(defn get-run
  [ctx {:keys [run-id]}]
  (let [system
        (system/of ctx)

        caller
        (roles/caller ctx)

        found
        (run/visible! caller (get-run-state ctx {:run-id run-id}))

        workflow
        (definitions/workflow system (:tenant found) (:team-id found) (:workflow-id found))]
    (assoc (model/detail found
                         (:name workflow)
                         (viewable-session system caller (:session-id found))
                         (guarded "usage" observability/empty-usage
                                  #(or (observability/usage-for-run system (:id found)) observability/empty-usage)))
           :project (or (:project found) (:project workflow)))))

(defn- since
  [rows from]
  (vec (for [row rows
             :when (>= (compare (str (:created-at row)) from) 0)]
         (select-keys row [:id :status :created-by :created-at]))))

(defn list-runs-since
  "Runs of every team of a tenant created since an ISO timestamp."
  [ctx {:keys [tenant] :as query}]
  (since (tenant-rows ctx tenant) (:since query)))

(defn list-people-runs-since
  "Runs people of a tenant started since an ISO timestamp, from their own
   directories."
  [ctx {:keys [tenant] :as query}]
  (since (directory/tenant-rows ctx tenant (directory/identities ctx tenant "user") :runs) (:since query)))

(defn list-pending-runs
  "Rows of the runs that need work; the executor reads each run itself."
  [ctx _query]
  (filterv :needs-work (all-rows ctx)))

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
      (= "pull_request" (:type trigger)) (select-keys trigger [:provider :repository :url :web-url :credentials])
      :else nil)))

(defn list-open-pull-requests
  [ctx {:keys [team-id workflow-id]}]
  (let [system
        (system/of ctx)

        caller
        (roles/caller ctx)

        tenant
        (:tenant caller)

        _
        (run/member! system caller team-id)

        workflow
        (or (definitions/workflow system tenant team-id workflow-id)
            (errors/not-found! "Workflow not found"))

        {:keys [provider repository credentials] :as source}
        (scm-options workflow)

        credential
        (when credentials
          (:value (credentials/resolve-credential system {:tenant tenant :team-id team-id :user (:id caller)} credentials)))]
    (if (and provider repository (:secret credential))
      (let [items
            (try
              ((:open-pull-requests (scm/provider system source credential)) repository)
              (catch Exception e
                (log/warn e "Listing pull requests failed")
                (errors/fail! 502 "Could not list pull requests")))]
        (mapv (fn [pr] {:id (:id pr) :title (:title pr) :author (:author pr) :branch (:source-branch pr)})
              (sort-by #(- (:id %)) items)))
      [])))

(defn register
  [ctx]
  (-> ctx
      (edd/reg-query :list-runs list-runs
                     :consumes [:map
                                [:team-id {:optional true} [:maybe :string]]
                                [:project-id {:optional true} [:maybe :string]]
                                [:limit {:optional true} [:maybe :int]]]
                     :auth roles/people)
      (edd/reg-query :get-run get-run
                     :consumes [:map [:run-id uuid?]]
                     :auth roles/people)
      (edd/reg-query :get-run-source get-run-source
                     :consumes [:map [:run-id uuid?]]
                     :auth roles/platform-only)
      (edd/reg-query :list-runs-since list-runs-since
                     :consumes [:map [:tenant string?] [:since string?]]
                     :auth roles/platform-only)
      (edd/reg-query :list-people-runs-since list-people-runs-since
                     :consumes [:map [:tenant string?] [:since string?]]
                     :auth roles/platform-only)
      (edd/reg-query :list-pending-runs list-pending-runs
                     :auth roles/platform-only)
      (edd/reg-query :list-open-pull-requests list-open-pull-requests
                     :consumes [:map [:team-id :string] [:workflow-id :string]]
                     :auth roles/people)))
