(ns manager-svc.webhooks.model
  "The webhook aggregate: an inbound endpoint that starts a team workflow.

     {:id :tenant
      :scope {:kind :tenant|:team|:project :team-id :project-id}
      :name :description :enabled
      :target {:team-id :workflow-id}
      :auth {:type :none|:header|:basic|:bearer|:hmac :header-name :username
             :algorithm :timestamp-header :tolerance-seconds :hint}
      :sealed            the secret, sealed at the edge (never plaintext)
      :mapping [{:input :source :value}]
      :allowed-ips [cidr] :allow-get :rate-limit-per-minute
      :deliveries [delivery] (newest first, at most max-deliveries)
      :last-delivery delivery
      :created-at :created-by :updated-at :updated-by :deleted}

   A webhook is listed (without its secret and deliveries) in :webhooks of
   its scope's directory: the tenant's for :tenant, the team's otherwise.

   Who may do what (caller as manager-svc.roles reads it): tenant admins
   manage every webhook of their tenant; team admins those of their teams
   and their projects; members of the scope's or the target's team see
   them without secret hints."
  (:require [manager-svc.directory :as directory]
            [manager-svc.roles :as roles]))

(def max-deliveries 50)

(def auth-types
  #{:none :header :basic :bearer :hmac})

(def secret-types
  "Auth types that need a secret."
  #{:header :basic :bearer :hmac})

(defn kind
  [webhook]
  (keyword (get-in webhook [:scope :kind])))

(defn scope-team
  [webhook]
  (when-not (= :tenant (kind webhook))
    (get-in webhook [:scope :team-id])))

(defn may-manage-scope?
  [caller tenant {:keys [team-id] :as scope}]
  (case (keyword (:kind scope))
    :tenant (roles/tenant-admin? caller tenant)
    (:team :project) (roles/team-admin? caller tenant team-id)
    false))

(defn may-manage?
  [caller webhook]
  (may-manage-scope? caller (:tenant webhook) (:scope webhook)))

(defn may-see?
  [caller webhook]
  (let [tenant
        (:tenant webhook)]
    (boolean
     (or (may-manage? caller webhook)
         (when-let [team-id (scope-team webhook)]
           (roles/member? caller tenant team-id))
         (roles/member? caller tenant (get-in webhook [:target :team-id]))))))

(defn present?
  [webhook]
  (boolean (and (:tenant webhook) (not (:deleted webhook)))))

(defn in-tenant
  "webhook when it exists, is not deleted and belongs to tenant; else nil."
  [webhook tenant]
  (when (and (present? webhook) (some? tenant) (= tenant (:tenant webhook)))
    webhook))

(defn identity-of
  "The directory listing the webhook."
  [webhook]
  (if (= :tenant (kind webhook))
    (directory/tenant (:tenant webhook))
    (directory/team (:tenant webhook) (scope-team webhook))))

(def config-keys
  [:tenant :scope :name :description :enabled :target :auth :mapping :allowed-ips :allow-get
   :rate-limit-per-minute])

(def row-keys
  [:id :tenant :scope :name :description :enabled :target :created-at :created-by :updated-at :updated-by
   :last-delivery])

(defn- summary-delivery
  [delivery]
  (select-keys delivery [:id :at :status :run-id :error :test]))

(defn row
  [webhook]
  (assoc (select-keys webhook row-keys)
         :auth-type (get-in webhook [:auth :type])
         :last-delivery (some-> (:last-delivery webhook) summary-delivery)
         :version (:version webhook)))

(defn directory-rows
  [webhook]
  (when (:tenant webhook)
    [(if (present? webhook)
       (directory/upsert (identity-of webhook) :webhooks (row webhook))
       (directory/removal (identity-of webhook) :webhooks (:id webhook) (:version webhook)))]))

(defn endpoint-path
  [{:keys [tenant id]}]
  (str "/hooks/" tenant "/" id))

(defn- auth-view
  [auth manage?]
  (cond-> (dissoc auth :sealed)
    (not manage?) (dissoc :hint)))

(defn view
  "What a person sees of a webhook: never the sealed secret, the hint only
   when they manage it."
  [caller webhook]
  (let [manage?
        (may-manage? caller webhook)]
    (-> (select-keys webhook (into [:id :created-at :created-by :updated-at :updated-by :last-delivery :deliveries]
                                   config-keys))
        (update :auth auth-view manage?)
        (assoc :can-manage manage?
               :endpoint-path (endpoint-path webhook)))))

(defn listed
  "A directory row as a person sees it in a list."
  [caller row]
  (-> (dissoc row :version)
      (assoc :can-manage (may-manage? caller row)
             :endpoint-path (endpoint-path row))))

(defn apply-saved
  [webhook {:keys [id attrs]}]
  (merge (dissoc webhook :deleted)
         {:id id}
         (select-keys attrs (into [:sealed :created-at :created-by :updated-at :updated-by] config-keys))
         {:deliveries (or (:deliveries webhook) [])}))

(defn apply-secret-set
  [webhook {:keys [attrs]}]
  (-> webhook
      (assoc :sealed (:sealed attrs)
             :updated-at (:updated-at attrs)
             :updated-by (:updated-by attrs))
      (assoc-in [:auth :hint] (:hint attrs))))

(defn apply-deleted
  [webhook {:keys [attrs]}]
  (-> webhook
      (dissoc :sealed :deliveries :last-delivery)
      (assoc :deleted true
             :updated-at (:updated-at attrs)
             :updated-by (:updated-by attrs))))

(defn apply-delivery
  [webhook {:keys [attrs]}]
  (let [delivery
        (:delivery attrs)]
    (assoc webhook
           :last-delivery delivery
           :deliveries (vec (take max-deliveries (cons delivery (:deliveries webhook)))))))
