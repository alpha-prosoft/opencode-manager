(ns manager-svc.sessions.queries
  (:require [edd.common :as common]
            [edd.core :as edd]
            [manager-svc.directory :as directory]
            [manager-svc.observability.api :as observability]
            [manager-svc.roles :as roles]
            [manager-svc.sessions.access :as access]
            [manager-svc.sessions.aggregate :as aggregate]
            [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.links :as links]
            [manager-svc.sessions.live :as live]
            [manager-svc.sessions.views :as views]
            [manager-svc.system :as system]))

(defn session-record
  [ctx session-id]
  (when session-id
    (let [found
          (common/get-by-id ctx {:id session-id})]
      (when (aggregate/session? found)
        found))))

(defn- newest-first
  [sessions]
  (vec (sort-by :created-at #(compare %2 %1) sessions)))

(defn- team-directories
  "Every team directory of tenant (also of teams that left the registry),
   or of every tenant."
  [ctx tenant]
  (if tenant
    (directory/identities ctx tenant "team")
    (directory/all-identities ctx "team")))

(defn listed-sessions
  "Directory rows of the sessions the caller may see in their tenant,
   newest first: their own, their teams' and those shared with the tenant;
   every session of the tenant for tenant admins."
  [ctx caller]
  (let [viewer
        (access/viewer caller)

        tenant
        (:tenant viewer)

        rows
        (when tenant
          (directory/tenant-rows ctx tenant
                                 (cons (directory/user (:username viewer)) (team-directories ctx tenant))
                                 :sessions))]
    (newest-first (access/visible-to (system/of ctx) viewer rows))))

(defn- visible!
  [ctx session-id]
  (access/visible! (system/of ctx) (session-record ctx session-id) (roles/caller ctx)))

(defn- matching
  "rows of a team, and of one of its projects: project ids are only unique
   within a team, so :project-id counts only beside :team-id."
  [{:keys [team-id project-id]} rows]
  (cond->> rows
    team-id (filter #(= team-id (:team-id %)))
    (and team-id project-id) (filter #(= project-id (:project %)))))

(defn list-sessions
  "The sessions the caller may see; of one team with :team-id, of one of its
   projects with :project-id as well."
  [ctx query]
  (let [system
        (system/of ctx)

        viewer
        (access/viewer (roles/caller ctx))

        statuses
        (backend/statuses (live/backend-of system))]
    (mapv #(views/session-view system statuses % viewer)
          (matching query (listed-sessions ctx viewer)))))

(defn get-session
  [ctx {:keys [session-id]}]
  (let [system
        (system/of ctx)

        session
        (visible! ctx session-id)

        status
        (backend/status (live/backend-of system) session-id)]
    (assoc (views/session-view system {session-id status} session (roles/caller ctx))
           :usage (merge observability/empty-usage (access/guarded {} observability/usage-for-session system session-id))
           :audit-events (or (access/guarded 0 observability/audit-count system session-id) 0))))

(defn list-conversations
  [ctx {:keys [session-id]}]
  (visible! ctx session-id)
  (vec (for [conversation (access/guarded [] observability/audit-conversations (system/of ctx) session-id)]
         (assoc conversation :open-path (links/open-path {} (:id conversation))))))

(defn get-session-record
  "{:session record}: edd answers a query result holding :error (a failed
   start) as an error, so the record is wrapped."
  [ctx {:keys [session-id]}]
  {:session (session-record ctx session-id)})

(defn list-session-rows
  "Directory rows of every session, of a tenant's or of a team's."
  [ctx {:keys [tenant team-id]}]
  (let [rows
        (directory/rows-of ctx
                           (if (and tenant team-id)
                             [(directory/team tenant team-id)]
                             (team-directories ctx tenant))
                           :sessions)]
    (if tenant
      (filterv #(= tenant (:tenant %)) rows)
      rows)))

(defn count-running-sessions
  "{:count n}: running sessions of a team, for a command's :deps (it reads
   the team's directory in this request, without calling edd again). With
   :session-id instead of a team: those running beside that session in its
   team."
  [ctx {:keys [tenant team-id session-id]}]
  (let [session
        (session-record ctx session-id)

        tenant
        (or tenant (:tenant session))

        team-id
        (or team-id (:team-id session))

        running
        (when (and tenant team-id)
          (live/running-ids (system/of ctx) (list-session-rows ctx {:tenant tenant :team-id team-id})))]
    {:count (count (remove #{session-id} running))}))

(defn get-idle-stop-request
  "{:requests [{:tenant :requested-at}]}: each tenant's last request to stop
   its idle sessions."
  [ctx _query]
  {:requests (vec (for [tenant (directory/tenants ctx)
                        :let [requested-at (:requested-at (common/get-by-id ctx {:id (aggregate/reaper-id tenant)}))]
                        :when requested-at]
                    {:tenant tenant :requested-at requested-at}))})

(def ^:private SessionId
  [:map [:session-id uuid?]])

(defn register
  [ctx]
  (-> ctx
      (edd/reg-query :list-sessions list-sessions
                     :auth roles/people
                     :consumes [:map
                                [:team-id {:optional true} [:maybe string?]]
                                [:project-id {:optional true} [:maybe string?]]])
      (edd/reg-query :get-session get-session
                     :auth roles/people
                     :consumes SessionId)
      (edd/reg-query :list-conversations list-conversations
                     :auth roles/people
                     :consumes SessionId)
      (edd/reg-query :get-session-record get-session-record
                     :auth roles/platform-only
                     :consumes SessionId)
      (edd/reg-query :list-session-rows list-session-rows
                     :auth roles/platform-only
                     :consumes [:map
                                [:tenant {:optional true} string?]
                                [:team-id {:optional true} string?]])
      (edd/reg-query :count-running-sessions count-running-sessions
                     :auth roles/platform-only
                     :consumes [:map
                                [:tenant {:optional true} string?]
                                [:team-id {:optional true} string?]
                                [:session-id {:optional true} uuid?]])
      (edd/reg-query :get-idle-stop-request get-idle-stop-request
                     :auth roles/platform-only)))
