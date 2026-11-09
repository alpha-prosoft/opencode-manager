(ns manager-svc.credentials.values
  "Stored credential values and which prompts a person has seen.

   A credential value aggregate exists per credential id and owner (\"\" for
   the platform value) and holds only the sealed value and metadata:
   {:credential-id :owner :sealed :hint :updated-at :updated-by :verified-at}.
   A deleted value keeps {:deleted true} without the sealed value. Stored
   values are listed, without the sealed value, in the :credentials of their
   owner's directory, platform values in the platform directory.

   Prompts aggregate per person: {:username :seen [ids]}."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [edd.common :as common]
            [lambda.uuid :as uuid]
            [manager-svc.credentials.spec :as spec]
            [manager-svc.credentials.vault :as vault]
            [manager-svc.directory :as directory]))

(defn value-id
  [credential-id owner]
  (uuid/named (str "credential-value:" credential-id ":" owner)))

(defn prompts-id
  [username]
  (uuid/named (str "credential-prompts:" username)))

(defn present?
  [aggregate]
  (boolean (and aggregate (:sealed aggregate) (not (:deleted aggregate)))))

(defn apply-value-set
  [aggregate {:keys [id attrs]}]
  (merge (dissoc aggregate :deleted)
         {:id id}
         (select-keys attrs [:credential-id :owner :sealed :hint :updated-at :updated-by :verified-at])))

(defn apply-value-deleted
  [aggregate {:keys [id attrs]}]
  (merge (select-keys aggregate [:credential-id :owner])
         {:id id
          :deleted true
          :updated-at (:updated-at attrs)
          :updated-by (:updated-by attrs)}))

(defn apply-seen
  [aggregate {:keys [id attrs]}]
  (assoc aggregate
         :id id
         :username (:username attrs)
         :seen (vec (distinct (concat (:seen aggregate) (:credentials attrs))))))

(defn stored
  [ctx credential-id owner]
  (let [aggregate
        (common/get-by-id ctx {:id (value-id credential-id owner)})]
    (when (present? aggregate)
      aggregate)))

(defn directory-of
  [owner]
  (if (= vault/platform-owner owner)
    directory/platform
    (directory/user owner)))

(def row-keys
  [:id :credential-id :owner :hint :updated-at :updated-by :verified-at])

(defn directory-rows
  [aggregate]
  (let [identity
        (directory-of (:owner aggregate))]
    [(if (present? aggregate)
       (directory/upsert identity :credentials (assoc (select-keys aggregate row-keys) :version (:version aggregate)))
       (directory/removal identity :credentials (:id aggregate) (:version aggregate)))]))

(defn of-owner
  "Rows of the values owner stored."
  [ctx owner]
  (directory/rows ctx (directory-of owner) :credentials))

(defn all-stored
  "Rows of the platform values and of every person's values."
  [ctx]
  (directory/rows-of ctx
                     (cons directory/platform (directory/identities ctx "user"))
                     :credentials))

(defn seen
  [ctx username]
  (set (:seen (common/get-by-id ctx {:id (prompts-id username)}))))

(defn file-value
  "A secret file in the secrets dir, or PLATFORM_SECRET_<name>."
  [{:keys [secrets-dir]} secret-name]
  (let [file
        (io/file secrets-dir (name secret-name))]
    (if (.isFile file)
      (string/trim (slurp file))
      (System/getenv (str "PLATFORM_SECRET_" (name secret-name))))))

(defn opener
  "A lookup (fn [credential-id owner] value-or-nil) over a sealed-record lookup."
  [settings sealed-record]
  (fn [credential-id owner]
    (when-let [{:keys [sealed]}
               (sealed-record credential-id owner)]
      (vault/unseal settings credential-id owner sealed))))

(defn ctx-lookup
  [ctx settings]
  (opener settings (partial stored ctx)))

(defn platform-value
  [settings lookup credential-id]
  (or (lookup credential-id vault/platform-owner)
      (when-let [legacy
                 (file-value settings credential-id)]
        (when-not (string/blank? legacy)
          {:secret legacy}))))

(defn resolve-value
  "The person's own value when the credential is personal and they set one,
   otherwise the platform value unless only people provide it."
  [platform-spec settings lookup credential-id user]
  (let [credential
        (spec/credential platform-spec credential-id)

        own
        (when (and user credential (spec/personal? credential))
          (lookup credential-id user))]
    (cond
      own {:value own :source :user}
      (= :user (:provided-by credential)) nil
      :else (when-let [value
                       (platform-value settings lookup credential-id)]
              {:value value :source :platform}))))
