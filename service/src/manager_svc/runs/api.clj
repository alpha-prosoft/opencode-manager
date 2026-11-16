(ns manager-svc.runs.api
  "Facade other domains use for runs."
  (:require [manager-svc.client :as client]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.definitions :as definitions]
            [manager-svc.runs.sandbox :as sandbox]
            [manager-svc.sessions.api :as sessions]))

(defn workflow-sandbox
  "The sandbox (what sessions.api/create-session! takes as :sandbox) of an
   interactive session of team-id continuing a workflow of workflow-team-id,
   as a fork needs it: {:session-id :tenant :team-id :workflow-team-id
   :workflow-id}. nil when the workflow, its agent or its harness is missing
   or invalid."
  [system {:keys [session-id tenant team-id workflow-team-id workflow-id]}]
  (let [workflow
        (definitions/workflow system tenant workflow-team-id workflow-id)

        agent
        (when workflow (definitions/agent-spec system tenant workflow-team-id (:agent workflow)))

        harness
        (when workflow (definitions/harness-spec system tenant workflow-team-id (:harness workflow)))]
    (when (and agent harness)
      (sandbox/session-sandbox system
                               {:session-id session-id
                                :tenant tenant
                                :team-id team-id
                                :definitions-team-id workflow-team-id
                                :workflow workflow
                                :agent agent
                                :harness harness
                                :team (definitions/team system tenant team-id)
                                :prompt (or (:prompt agent)
                                            (platform/read-team-file system tenant workflow-team-id (:prompt-file agent)))
                                :interactive true}))))

(defn fork-sandbox
  "The sandbox of a fork: the workflow its source ran, as a session of the
   fork's team with the fork's keys and password. nil when the workflow
   can't be built."
  [system fork]
  (workflow-sandbox system {:session-id (:id fork)
                            :tenant (:tenant fork)
                            :team-id (:team-id fork)
                            :workflow-team-id (sessions/workflow-team fork)
                            :workflow-id (get-in fork [:labels :workflow])}))

(defn run-counts-by-status
  "{status count} of the runs of tenant created since an ISO timestamp."
  [system tenant since]
  (frequencies (map (comp keyword :status)
                    (client/query (:settings system) {:query-id :list-runs-since :tenant tenant :since since}))))

(defn run-counts-by-user
  "{username count} of the runs people of tenant started since an ISO timestamp."
  [system tenant since]
  (frequencies (map :created-by
                    (client/query (:settings system) {:query-id :list-people-runs-since :tenant tenant :since since}))))
