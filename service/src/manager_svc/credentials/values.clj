(ns manager-svc.credentials.values
  "Stored credential values and which prompts a person has seen.

   A value belongs to a scope and an owner:

     :tenant  \"tenant:<tenant>\"            set by tenant admins
     :team    \"team:<tenant>/<team>\"       set by the team's admins
     :user    \"user:<tenant>/<username>\"   a person's own value

   The vault binds a sealed value to its credential and owner, so a value
   sealed for one owner, team or tenant can never be opened for another.

   A value aggregate exists per credential id and owner and holds only the
   sealed value and metadata: {:credential-id :owner :scope :tenant
   :team-id :username :sealed :hint :updated-at :updated-by :verified-at}.
   A deleted value keeps {:deleted true} without the sealed value. Stored
   values are listed, without the sealed value, in :credentials of their
   owner's aggregate: the tenant's, the team's or the person's.

   Prompts aggregate per person: {:username :seen [ids]}."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [edd.common :as common]
            [lambda.uuid :as uuid]
            [manager-svc.credentials.spec :as spec]
            [manager-svc.credentials.vault :as vault]
            [manager-svc.directory :as directory]))

(defn owner
  "The owner string of a scope's value: {:tenant} for :tenant, {:tenant
   :team-id} for :team, {:tenant :user} for :user."
  [scope {:keys [tenant team-id user]}]
  (case scope
    :tenant (str "tenant:" tenant)
    :team (str "team:" tenant "/" team-id)
    :user (str "user:" tenant "/" user)))

(defn value-id
  [credential-id owner]
  (uuid/named (str "credential-value:" credential-id ":" owner)))

(defn prompts-id
  [tenant username]
  (uuid/named (str "credential-prompts:" tenant "/" username)))

(defn present?
  [aggregate]
  (boolean (and aggregate (:sealed aggregate) (not (:deleted aggregate)))))

(def ^:private value-keys
  [:credential-id :owner :scope :tenant :team-id :username])

(defn apply-value-set
  [aggregate {:keys [id attrs]}]
  (merge (dissoc aggregate :deleted)
         {:id id}
         (select-keys attrs (into value-keys [:sealed :hint :updated-at :updated-by :verified-at]))))

(defn apply-value-deleted
  [aggregate {:keys [id attrs]}]
  (merge (select-keys aggregate value-keys)
         {:id id
          :deleted true
          :updated-at (:updated-at attrs)
          :updated-by (:updated-by attrs)}))

(defn apply-seen
  [aggregate {:keys [id attrs]}]
  (assoc aggregate
         :id id
         :tenant (:tenant attrs)
         :username (:username attrs)
         :seen (vec (distinct (concat (:seen aggregate) (:credentials attrs))))))

(defn stored
  [ctx credential-id owner]
  (let [aggregate
        (common/get-by-id ctx {:id (value-id credential-id owner)})]
    (when (present? aggregate)
      aggregate)))

(defn directory-of
  [{:keys [scope tenant team-id username]}]
  (case (keyword scope)
    :tenant (directory/tenant tenant)
    :team (directory/team tenant team-id)
    :user (directory/user username)))

(def row-keys
  (into value-keys [:id :hint :updated-at :updated-by :verified-at]))

(defn directory-rows
  [aggregate]
  (when (:scope aggregate)
    (let [identity
          (directory-of aggregate)]
      [(if (present? aggregate)
         (directory/upsert identity :credentials (assoc (select-keys aggregate row-keys) :version (:version aggregate)))
         (directory/removal identity :credentials (:id aggregate) (:version aggregate)))])))

(defn of-user
  "Rows of the values a person stored in tenant."
  [ctx tenant username]
  (filterv #(= :user (keyword (:scope %)))
           (directory/tenant-rows ctx tenant [(directory/user username)] :credentials)))

(defn of-team
  [ctx tenant team-id]
  (directory/tenant-rows ctx tenant [(directory/team tenant team-id)] :credentials))

(defn of-tenant
  [ctx tenant]
  (directory/tenant-rows ctx tenant [(directory/tenant tenant)] :credentials))

(defn all-teams
  "Rows of every team value of tenant."
  [ctx tenant]
  (directory/tenant-rows ctx tenant (directory/identities ctx tenant "team") :credentials))

(defn all-people
  "Rows of every personal value of tenant."
  [ctx tenant]
  (filterv #(= :user (keyword (:scope %)))
           (directory/tenant-rows ctx tenant (directory/identities ctx tenant "user") :credentials)))

(defn seen
  [ctx tenant username]
  (set (:seen (common/get-by-id ctx {:id (prompts-id tenant username)}))))

(defn- secret-file
  [^java.io.File file]
  (when (.isFile file)
    (string/trim (slurp file))))

(def ^:private tenant-pattern
  #"^[a-z0-9][a-z0-9-]{0,62}$")

(defn- operator-value
  "The operator's <secrets-dir>/<tenant>/<file-name>, else
   <secrets-dir>/<file-name>, else the environment variable."
  [{:keys [secrets-dir]} tenant file-name variable]
  (or (when (and tenant (re-matches tenant-pattern tenant))
        (secret-file (io/file secrets-dir tenant file-name)))
      (secret-file (io/file secrets-dir file-name))
      (System/getenv variable)))

(defn file-value
  "A secret file of the operator: <secrets-dir>/<tenant>/<name>, else
   <secrets-dir>/<name>, else PLATFORM_SECRET_<name>."
  [settings tenant secret-name]
  (operator-value settings tenant (name secret-name) (str "PLATFORM_SECRET_" (name secret-name))))

(defn file-username
  "The username of a secret file's value, for basic credentials:
   <secrets-dir>/<tenant>/<name>.username, else <secrets-dir>/<name>.username,
   else PLATFORM_SECRET_<name>_USERNAME."
  [settings tenant secret-name]
  (operator-value settings
                  tenant
                  (str (name secret-name) ".username")
                  (str "PLATFORM_SECRET_" (name secret-name) "_USERNAME")))

(defn file-value?
  [settings tenant secret-name]
  (not (string/blank? (file-value settings tenant secret-name))))

(defn tenant-value?
  "True when the tenant stored a value or the operator has a secret file,
   without opening either."
  [ctx settings tenant credential-id]
  (or (some? (stored ctx credential-id (owner :tenant {:tenant tenant})))
      (file-value? settings tenant credential-id)))

(defn opener
  "A lookup (fn [credential-id owner] value-or-nil) over a sealed-record lookup."
  [settings sealed-record]
  (fn [credential-id owner]
    (when-let [{:keys [sealed]}
               (sealed-record credential-id owner)]
      (vault/unseal settings credential-id owner sealed))))

(defn tenant-value
  "The tenant's stored value, else the operator's secret file."
  [settings lookup tenant credential-id]
  (or (lookup credential-id (owner :tenant {:tenant tenant}))
      (when-let [from-file
                 (file-value settings tenant credential-id)]
        (when-not (string/blank? from-file)
          (let [username
                (file-username settings tenant credential-id)]
            (cond-> {:secret from-file}
              (not (string/blank? username)) (assoc :username username)))))))

(defn- scope-value
  [settings lookup credential-id scope {:keys [tenant team-id user] :as owner-of}]
  (case scope
    :user (when user (lookup credential-id (owner :user owner-of)))
    :team (when team-id (lookup credential-id (owner :team owner-of)))
    :tenant (when tenant (tenant-value settings lookup tenant credential-id))))

(defn resolve-value
  "{:value :source} of credential-id for {:tenant :team-id :user}: the
   person's own value, then the team's, then the tenant's, each only when
   the credential lets that scope provide it. An undeclared secret has a
   tenant value only."
  [tenant-spec settings lookup credential-id owner-of]
  (let [credential
        (or (spec/credential tenant-spec credential-id) (spec/undeclared credential-id))]
    (some (fn [scope]
            (when (spec/provides? credential scope)
              (when-let [value
                         (scope-value settings lookup credential-id scope owner-of)]
                {:value value :source scope})))
          [:user :team :tenant])))
