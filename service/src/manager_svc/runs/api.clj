(ns manager-svc.runs.api
  "Facade other domains use for runs."
  (:require [manager-svc.client :as client]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.definitions :as definitions]
            [manager-svc.runs.model :as model]
            [manager-svc.runs.sandbox :as sandbox]
            [manager-svc.sessions.api :as sessions]))

(defn workflow-sandbox
  "The sandbox (what sessions.api/create-session! takes as :sandbox) of an
   interactive session continuing a team workflow, as a fork needs it:
   {:session-id :team-id :workflow-id}. nil when the workflow, its agent or
   its harness is missing or invalid."
  [system {:keys [session-id team-id workflow-id]}]
  (let [workflow
        (definitions/workflow system team-id workflow-id)

        agent
        (when workflow (definitions/agent-spec system team-id (:agent workflow)))

        harness
        (when workflow (definitions/harness-spec system team-id (:harness workflow)))]
    (when (and agent harness)
      (sandbox/sandbox (:settings system)
                       {:session-id session-id
                        :agent-id (:agent workflow)
                        :agent agent
                        :prompt (or (:prompt agent)
                                    (platform/read-team-file system team-id (:prompt-file agent)))
                        :harness harness
                        :interactive true
                        :result-tool nil
                        :password (sessions/secret-key system session-id)
                        :team-id team-id
                        :run-id nil
                        :skills (when (seq (:skills harness))
                                  (platform/read-skills system team-id (:skills harness)))}))))

(defn fork-sandbox
  "The sandbox of a fork of source: the source's workflow, with the keys and
   password of the fork. nil when the workflow can't be built."
  [system source fork]
  (when source
    (workflow-sandbox system {:session-id (:id fork)
                              :team-id (:team-id source)
                              :workflow-id (or (get-in fork [:labels :workflow])
                                               (get-in source [:labels :workflow]))})))

(defn- runs-since
  [system since]
  (client/query (:settings system) {:query-id :list-runs-since :since since}))

(defn run-counts-by-status
  "{status count} of runs created since an ISO timestamp."
  [system since]
  (frequencies (map (comp keyword :status) (runs-since system since))))

(defn run-counts-by-user
  "{username count} of runs people started since an ISO timestamp."
  [system since]
  (frequencies (keep #(when-not (model/platform-created? %) (:created-by %))
                     (client/query (:settings system) {:query-id :list-people-runs-since :since since}))))
