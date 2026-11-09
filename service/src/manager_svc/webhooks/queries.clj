(ns manager-svc.webhooks.queries
  "Reading webhooks: lists from the directory rows of the caller's tenant and
   teams, one webhook with its deliveries, the teams and workflows a person
   may target, a dry run of a mapping, and, for the edge only, a webhook
   with its sealed secret."
  (:require [clojure.string :as string]
            [edd.common :as common]
            [edd.core :as edd]
            [manager-svc.directory :as directory]
            [manager-svc.errors :as errors]
            [manager-svc.platform.api :as platform]
            [manager-svc.roles :as roles]
            [manager-svc.system :as system]
            [manager-svc.webhooks.model :as model]
            [manager-svc.webhooks.workflows :as workflows]))

(defn- identities
  [ctx tenant caller]
  (into [(directory/tenant tenant)]
        (if (roles/tenant-admin? caller tenant)
          (directory/identities ctx tenant "team")
          (map #(directory/team tenant %) (platform/teams-of (system/of ctx) caller)))))

(defn- in-filter?
  [{:keys [team-id project-id]} row]
  (and (or (nil? team-id)
           (= team-id (model/scope-team row))
           (= team-id (get-in row [:target :team-id])))
       (or (nil? project-id)
           (= project-id (get-in row [:scope :project-id])))))

(defn list-webhooks
  [ctx query]
  (let [caller
        (roles/caller ctx)

        tenant
        (roles/tenant-of ctx)]
    (->> (directory/tenant-rows ctx tenant (identities ctx tenant caller) :webhooks)
         (filter #(model/may-see? caller %))
         (filter #(in-filter? query %))
         (sort-by (juxt #(string/lower-case (str (:name %))) (comp str :id)))
         (mapv #(model/listed caller %)))))

(defn- uuid-of
  [value]
  (if (uuid? value) value (parse-uuid (str value))))

(defn- stored
  "The webhook of tenant by id, or nil."
  [ctx tenant webhook-id]
  (when-let [id
             (uuid-of webhook-id)]
    (model/in-tenant (common/get-by-id ctx {:id id}) tenant)))

(defn- visible!
  [ctx webhook-id]
  (let [webhook
        (or (stored ctx (roles/tenant-of ctx) webhook-id)
            (errors/not-found! "Webhook not found"))]
    (when-not (model/may-see? (roles/caller ctx) webhook)
      (errors/forbidden! (str "Only members of " (get-in webhook [:target :team-id]) " see this webhook")))
    webhook))

(defn get-webhook
  [ctx {:keys [webhook-id]}]
  (model/view (roles/caller ctx) (visible! ctx webhook-id)))

(defn- team-targets
  [system tenant caller team]
  {:id (:id team)
   :name (or (:name team) (:id team))
   :manage (roles/team-admin? caller tenant (:id team))
   :projects (mapv #(select-keys % [:id :name]) (:projects team))
   :workflows (mapv #(select-keys % [:id :name :project :inputs-schema])
                    (workflows/team-workflows system tenant (:id team)))})

(defn list-webhook-targets
  "Teams of the caller with their projects and workflows (and whether they
   manage webhooks there), and whether they may add tenant webhooks."
  [ctx _query]
  (let [system
        (system/of ctx)

        caller
        (roles/caller ctx)

        tenant
        (roles/tenant-of ctx)

        mine
        (platform/teams-of system caller)]
    {:tenant tenant
     :tenant-scope (roles/tenant-admin? caller tenant)
     :teams (vec (for [team (platform/teams system tenant)
                       :when (contains? mine (:id team))]
                   (team-targets system tenant caller team)))}))

(defn preview-webhook
  "A dry run: what a sample request maps to for a workflow, validated like
   a delivery; nothing is started."
  [ctx {:keys [target mapping request]}]
  (let [tenant
        (roles/tenant-of ctx)

        team-id
        (:team-id target)]
    (when-not (platform/team (system/of ctx) tenant team-id)
      (errors/not-found! "Team not found"))
    (when-not (roles/member? (roles/caller ctx) tenant team-id)
      (errors/forbidden! (str "Only members of team " team-id " try its webhooks")))
    (workflows/preview (system/of ctx) tenant target mapping
                       {:body-text (:body request)
                        :headers (or (:headers request) {})
                        :query (or (:query request) {})})))

(defn get-webhook-for-delivery
  "The whole webhook, sealed secret included, wrapped as {:webhook}."
  [ctx {:keys [tenant webhook-id]}]
  {:webhook (stored ctx tenant webhook-id)})

(defn register
  [ctx]
  (-> ctx
      (edd/reg-query :list-webhooks list-webhooks
                     :consumes [:map
                                [:team-id {:optional true} [:maybe :string]]
                                [:project-id {:optional true} [:maybe :string]]]
                     :auth {:role [:user]})
      (edd/reg-query :get-webhook get-webhook
                     :consumes [:map [:webhook-id any?]]
                     :auth {:role [:user]})
      (edd/reg-query :list-webhook-targets list-webhook-targets
                     :auth {:role [:user]})
      (edd/reg-query :preview-webhook preview-webhook
                     :consumes [:map
                                [:target [:map [:team-id :string] [:workflow-id :string]]]
                                [:mapping [:vector map?]]
                                [:request [:map
                                           [:body {:optional true} [:maybe :string]]
                                           [:headers {:optional true} [:maybe map?]]
                                           [:query {:optional true} [:maybe map?]]]]]
                     :auth {:role [:user]})
      (edd/reg-query :get-webhook-for-delivery get-webhook-for-delivery
                     :consumes [:map [:tenant :string] [:webhook-id any?]]
                     :auth {:role [:non-interactive]})))
