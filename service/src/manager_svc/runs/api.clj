(ns manager-svc.runs.api
  "Facade other domains use for runs."
  (:require [manager-svc.client :as client]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.definitions :as definitions]
            [manager-svc.runs.sandbox :as sandbox]))

(defn workflow-sandbox
  "The sandbox (what sessions.api/create-session! takes as :sandbox) of an
   interactive session continuing a team workflow, as a fork needs it:
   {:session-id :tenant :team-id :workflow-id}. nil when the workflow, its
   agent or its harness is missing or invalid."
  [system {:keys [session-id tenant team-id workflow-id]}]
  (let [workflow
        (definitions/workflow system tenant team-id workflow-id)

        agent
        (when workflow (definitions/agent-spec system tenant team-id (:agent workflow)))

        harness
        (when workflow (definitions/harness-spec system tenant team-id (:harness workflow)))]
    (when (and agent harness)
      (sandbox/session-sandbox system
                               {:session-id session-id
                                :tenant tenant
                                :team-id team-id
                                :workflow workflow
                                :agent agent
                                :harness harness
                                :team (definitions/team system tenant team-id)
                                :prompt (or (:prompt agent)
                                            (platform/read-team-file system tenant team-id (:prompt-file agent)))
                                :interactive true}))))

(defn fork-sandbox
  "The sandbox of a fork of source: the source's workflow, with the keys and
   password of the fork. nil when the workflow can't be built."
  [system source fork]
  (when source
    (workflow-sandbox system {:session-id (:id fork)
                              :tenant (:tenant source)
                              :team-id (:team-id source)
                              :workflow-id (or (get-in fork [:labels :workflow])
                                               (get-in source [:labels :workflow]))})))

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
