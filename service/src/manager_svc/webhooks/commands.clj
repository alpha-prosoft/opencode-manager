(ns manager-svc.webhooks.commands
  "Saving, re-keying and deleting webhooks, and recording their deliveries.
   Secrets arrive sealed by the edge (manager-svc.webhooks.edge); a command
   opens one only to check it was sealed for this webhook and to keep its
   hint."
  (:require [clojure.string :as string]
            [edd.core :as edd]
            [edd.el.ctx :as el-ctx]
            [manager-svc.auth :as auth]
            [manager-svc.directory :as directory]
            [manager-svc.errors :as errors]
            [manager-svc.platform.api :as platform]
            [manager-svc.roles :as roles]
            [manager-svc.system :as system]
            [manager-svc.time :as time]
            [manager-svc.webhooks.mapping :as mapping]
            [manager-svc.webhooks.model :as model]
            [manager-svc.webhooks.secrets :as secrets]
            [manager-svc.webhooks.verify :as verify]
            [manager-svc.webhooks.workflows :as workflows]))

(def header-name-pattern
  #"^[A-Za-z0-9][A-Za-z0-9-]{0,63}$")

(def reserved-headers
  #{"authorization" "cookie" "host" "content-type" "content-length" "x-forwarded-for"})

(def min-secret-length 8)

(def max-mapping-rows 50)

(def max-allowed-ips 50)

(defn- text
  [value]
  (some-> value str string/trim not-empty))

(defn- kw
  [value]
  (when (or (string? value) (keyword? value))
    (keyword (name value))))

(defn scope-of
  [{:keys [kind team-id project-id]}]
  (let [kind
        (kw kind)]
    (cond-> {:kind kind}
      (#{:team :project} kind) (assoc :team-id (text team-id))
      (= :project kind) (assoc :project-id (text project-id)))))

(defn auth-of
  [{:keys [type header-name username algorithm timestamp-header tolerance-seconds]}]
  (let [kind
        (kw type)]
    (case kind
      :header {:type kind :header-name (text header-name)}
      :basic {:type kind :username (text username)}
      :hmac (cond-> {:type kind
                     :header-name (text header-name)
                     :algorithm (or (kw algorithm) :sha256)}
              (text timestamp-header) (assoc :timestamp-header (text timestamp-header)
                                             :tolerance-seconds (or tolerance-seconds verify/default-tolerance-seconds)))
      {:type kind})))

(defn config-of
  "The webhook configuration of save attrs, normalized."
  [tenant attrs]
  {:tenant tenant
   :scope (scope-of (:scope attrs))
   :name (text (:name attrs))
   :description (or (text (:description attrs)) "")
   :enabled (not (false? (:enabled attrs)))
   :target {:team-id (text (get-in attrs [:target :team-id]))
            :workflow-id (text (get-in attrs [:target :workflow-id]))}
   :auth (auth-of (:auth attrs))
   :mapping (mapv mapping/row (:mapping attrs))
   :allowed-ips (vec (keep text (:allowed-ips attrs)))
   :allow-get (true? (:allow-get attrs))
   :rate-limit-per-minute (or (:rate-limit-per-minute attrs) 60)})

(def scope-kinds
  #{:tenant :team :project})

(defn- scoped!
  "Fails unless the configuration names a kind of scope, which decides who
   may manage it."
  [{:keys [scope]}]
  (when-not (contains? scope-kinds (:kind scope))
    (errors/invalid! "Choose the tenant, a team or a project as the webhook's scope")))

(defn- scope-problems
  [system {:keys [tenant scope target]}]
  (let [{:keys [kind team-id project-id]}
        scope

        team
        (when team-id (platform/team system tenant team-id))]
    (cond
      (and (not= :tenant kind) (nil? team))
      [(str "Team " team-id " does not exist")]

      (and (= :project kind) (not (some #(= project-id (:id %)) (:projects team))))
      [(str "Project " project-id " does not exist in team " team-id)]

      (and (not= :tenant kind) (not= team-id (:team-id target)))
      ["A team's or project's webhook starts a workflow of that team"]

      :else
      [])))

(defn- header-problem
  [label value]
  (cond
    (nil? value) (str label " is required")
    (not (re-matches header-name-pattern value)) (str label " may hold letters, digits and dashes only")
    (contains? reserved-headers (string/lower-case value)) (str label " can't be " value)
    :else nil))

(defn- auth-problems
  [{:keys [type header-name username algorithm timestamp-header tolerance-seconds]}]
  (remove nil?
          [(when-not (contains? model/auth-types type) "Choose how callers authenticate")
           (when (#{:header :hmac} type) (header-problem "The header name" header-name))
           (when (and (= :basic type) (nil? username)) "Basic auth needs a username")
           (when (and (= :hmac type) (not (#{:sha256 :sha1} algorithm))) "The signature algorithm is sha256 or sha1")
           (when (and (= :hmac type) timestamp-header) (header-problem "The timestamp header" timestamp-header))
           (when (and timestamp-header (not (and (int? tolerance-seconds) (<= 30 tolerance-seconds 3600))))
             "The timestamp tolerance is 30 to 3600 seconds")]))

(defn- project-problem
  "A project's webhook starts the project's workflows or the team's own,
   never another project's."
  [{:keys [kind project-id]} workflow]
  (let [workflow-project
        (some-> (:project workflow) name)]
    (when (and (= :project kind) workflow-project (not= project-id workflow-project))
      (str "Workflow " (:id workflow) " belongs to project " workflow-project ", not " project-id))))

(defn- target-problems
  [system {:keys [tenant scope target mapping]}]
  (if-let [workflow
           (workflows/workflow system tenant (:team-id target) (:workflow-id target))]
    (concat (remove nil? [(project-problem scope workflow)])
            (mapping/mapping-problems (:inputs-schema workflow) mapping))
    [(str "Workflow " (:team-id target) "/" (:workflow-id target) " does not exist or is invalid")]))

(def unsigned-sources
  "Where a signature doesn't reach: an HMAC covers the body only."
  #{:query :header})

(defn- signature-problems
  "Why a signed webhook can't take what its signature doesn't cover."
  [{:keys [auth mapping allow-get]}]
  (when (= :hmac (:type auth))
    (remove nil?
            [(when (some unsigned-sources (map :source mapping))
               "A signed (HMAC) webhook maps inputs from the body only: the signature doesn't cover the query string or headers")
             (when allow-get
               "A signed (HMAC) webhook can't allow GET: a GET request has no body to sign")])))

(defn problems
  "Why a configuration can't be saved; empty when it can."
  [system {:keys [name description mapping allowed-ips rate-limit-per-minute auth] :as config}]
  (vec
   (concat
    (remove nil?
            [(when (nil? name) "A name is required")
             (when (and name (> (count name) 100)) "The name is at most 100 characters")
             (when (> (count description) 1000) "The description is at most 1000 characters")
             (when (> (count mapping) max-mapping-rows) (str "At most " max-mapping-rows " mapped inputs"))
             (when (> (count allowed-ips) max-allowed-ips) (str "At most " max-allowed-ips " allowed addresses"))
             (when-let [invalid (first (remove verify/valid-cidr? allowed-ips))]
               (str invalid " is not an IP address or CIDR range"))
             (when-not (and (int? rate-limit-per-minute) (<= 1 rate-limit-per-minute 600))
               "The rate limit is 1 to 600 requests a minute")])
    (scope-problems system config)
    (auth-problems auth)
    (signature-problems config)
    (target-problems system config))))

(defn- require-manage!
  [ctx tenant scope]
  (when-not (or (auth/platform? ctx) (model/may-manage-scope? (roles/caller ctx) tenant scope))
    (errors/forbidden! (if (= :tenant (kw (:kind scope)))
                         "Only tenant admins manage tenant webhooks"
                         (str "Only admins of team " (:team-id scope) " manage its webhooks")))))

(defn- existing!
  "The webhook of ctx in the caller's tenant; 404 when it is missing,
   deleted or another tenant's."
  [ctx]
  (or (model/in-tenant (el-ctx/get-aggregate ctx) (roles/tenant-of ctx))
      (errors/not-found! "Webhook not found")))

(defn- secret-of
  "The opened secret a save keeps: the newly sealed one, else the stored
   one."
  [ctx id tenant sealed]
  (when sealed
    (secrets/open (system/settings ctx) tenant id sealed)))

(defn- secret-problem
  [{:keys [type]} secret]
  (when (contains? model/secret-types type)
    (cond
      (string/blank? (:secret secret)) "A secret is required for this auth type"
      (< (count (:secret secret)) min-secret-length) (str "The secret needs at least " min-secret-length " characters")
      (> (count (:secret secret)) 4096) "The secret is at most 4096 characters"
      :else nil)))

(defn save-webhook
  [ctx {:keys [id attrs]}]
  (let [tenant
        (roles/tenant-of ctx)

        current
        (el-ctx/get-aggregate ctx)

        created?
        (nil? (:tenant current))

        _
        (when-not created?
          (existing! ctx)
          (require-manage! ctx tenant (:scope current)))

        config
        (config-of tenant attrs)

        _
        (scoped! config)

        _
        (require-manage! ctx tenant (:scope config))

        _
        (when (and (not created?) (not= (:scope current) (:scope config)))
          (errors/invalid! "A webhook's scope can't change; create a new webhook instead"))

        found
        (problems (system/of ctx) config)

        _
        (when (seq found)
          (errors/invalid! (string/join "; " found)))

        sealed
        (when (contains? model/secret-types (get-in config [:auth :type]))
          (or (:sealed attrs) (:sealed current)))

        secret
        (secret-of ctx id tenant sealed)

        _
        (when-let [problem (secret-problem (:auth config) secret)]
          (errors/invalid! problem))

        now
        (time/now)

        user
        (auth/user-id ctx)]
    {:event-id :webhook-saved
     :attrs (merge config
                   {:auth (cond-> (:auth config)
                            secret (assoc :hint (secrets/hint (:auth config) secret)))
                    :sealed sealed
                    :created-at (or (:created-at current) now)
                    :created-by (or (:created-by current) user)
                    :updated-at now
                    :updated-by user})}))

(defn set-webhook-secret
  [ctx {:keys [id attrs]}]
  (let [webhook
        (existing! ctx)

        _
        (require-manage! ctx (:tenant webhook) (:scope webhook))

        _
        (when-not (contains? model/secret-types (keyword (get-in webhook [:auth :type])))
          (errors/invalid! "This webhook takes requests without a secret"))

        secret
        (secrets/open (system/settings ctx) (:tenant webhook) id (:sealed attrs))]
    (when-let [problem (secret-problem {:type :header} secret)]
      (errors/invalid! problem))
    {:event-id :webhook-secret-set
     :attrs {:sealed (:sealed attrs)
             :hint (secrets/hint (:auth webhook) secret)
             :updated-at (time/now)
             :updated-by (auth/user-id ctx)}}))

(defn delete-webhook
  [ctx _cmd]
  (let [webhook
        (existing! ctx)]
    (require-manage! ctx (:tenant webhook) (:scope webhook))
    {:event-id :webhook-deleted
     :attrs {:updated-at (time/now)
             :updated-by (auth/user-id ctx)}}))

(defn record-delivery
  [ctx {:keys [attrs]}]
  (when (model/present? (el-ctx/get-aggregate ctx))
    {:event-id :webhook-delivery-recorded
     :attrs {:delivery (:delivery attrs)}}))

(def ^:private Name
  [:maybe [:or :string :keyword]])

(def ^:private Text
  [:maybe :string])

(defn- fields
  "A closed map of optional fields [key schema ...]."
  [& pairs]
  (into [:map {:closed true}]
        (for [[k schema] (partition 2 pairs)]
          [k {:optional true} schema])))

(def SaveAttrs
  (fields :name Text
          :description Text
          :enabled [:maybe :boolean]
          :scope [:maybe (fields :kind Name :team-id Text :project-id Text)]
          :target [:maybe (fields :team-id Text :workflow-id Text)]
          :auth [:maybe (fields :type Name
                                :header-name Text
                                :username Text
                                :algorithm Name
                                :timestamp-header Text
                                :tolerance-seconds [:maybe :int])]
          :mapping [:maybe [:vector (fields :input Text
                                            :source Name
                                            :value [:maybe [:or :string number? :boolean]])]]
          :allowed-ips [:maybe [:vector :string]]
          :allow-get [:maybe :boolean]
          :rate-limit-per-minute [:maybe :int]
          :sealed Text))

(def ^:private people
  {:role [:user]})

(def ^:private platform-only
  {:role [:non-interactive]})

(defn register
  [ctx]
  (-> ctx
      (edd/reg-cmd :save-webhook save-webhook
                   :consumes [:map [:id uuid?] [:attrs SaveAttrs]]
                   :auth people)
      (edd/reg-cmd :set-webhook-secret set-webhook-secret
                   :consumes [:map [:id uuid?] [:attrs [:map {:closed true} [:sealed :string]]]]
                   :auth people)
      (edd/reg-cmd :delete-webhook delete-webhook
                   :consumes [:map [:id uuid?]]
                   :auth people)
      (edd/reg-cmd :record-webhook-delivery record-delivery
                   :consumes [:map [:id uuid?] [:attrs [:map [:delivery map?]]]]
                   :auth platform-only)
      (edd/reg-event :webhook-saved model/apply-saved)
      (edd/reg-event :webhook-secret-set model/apply-secret-set)
      (edd/reg-event :webhook-deleted model/apply-deleted)
      (edd/reg-event :webhook-delivery-recorded model/apply-delivery)
      (directory/reg-rows #{:webhook-saved :webhook-secret-set :webhook-deleted :webhook-delivery-recorded}
                          model/directory-rows)))
