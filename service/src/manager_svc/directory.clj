(ns manager-svc.directory
  "Entity aggregates: one per tenant (identity \"/tenant/<tenant>\"), per
   team (\"/team/<tenant>/<team>\"), per person (\"/user/<username>\") and
   one for the platform (\"/platform\"). Each has a fresh UUID found through
   its edd identity and holds that entity's configuration (written by the
   platform domain's sync) and the rows its lists show, so a list reads a
   few aggregates instead of searching:

     {:identity \"/team/acme/alpha\"
      :tenants [\"acme\"]
      :collections {:runs [row ...] :sessions [row ...]}
      :removed {:sessions [{:id :version} ...]}}

   A collection keeps its rows oldest first and at most max-rows of them;
   the oldest is dropped when it is full. A row describes one aggregate and
   carries its :id, :version and :tenant: an older version never replaces a
   newer one, and a removed row stays removed for older versions, so rows
   are right however late or often an effect arrives.

   Domains keep rows in step with reg-rows: after every command producing
   one of their events, an effect sends :upsert-directory-row and
   :remove-directory-row commands as the platform. The first command for an
   identity creates the aggregate and registers the identity. When two
   requests create the same identity at once, the identity store's unique
   constraint fails the later one; its redelivery resolves the identity and
   writes to the aggregate the first one created.

   Indexes are never capped: the platform's :tenants lists every tenant
   aggregate, a tenant's :entities every team and person with rows of that
   tenant, so reads over all teams or people of a tenant miss none."
  (:require [clojure.string :as string]
            [edd.common :as common]
            [edd.core :as edd]
            [edd.el.ctx :as el-ctx]
            [manager-svc.client :as client]
            [manager-svc.roles :as roles]
            [manager-svc.rows :as rows]))

(def max-rows 10000)

(def platform "/platform")

(defn tenant
  [tenant-name]
  (str "/tenant/" tenant-name))

(defn team
  [tenant-name team-id]
  (str "/team/" tenant-name "/" team-id))

(defn user
  [username]
  (str "/user/" username))

(defn parse
  "{:kind \"team\" :tenant \"acme\" :name \"alpha\"} of an identity."
  [identity]
  (let [[_ kind & parts]
        (string/split (str identity) #"/")]
    (case kind
      "tenant" {:kind kind :tenant (first parts) :name (first parts)}
      "team" {:kind kind :tenant (first parts) :name (second parts)}
      "user" {:kind kind :name (string/join "/" parts)}
      {:kind kind})))

(defn upsert
  "The command putting row into collection of the aggregate of identity."
  [identity collection row]
  {:cmd-id :upsert-directory-row
   :attrs {:identity identity :collection collection :row row}})

(defn removal
  "The command removing the row of an aggregate at version."
  [identity collection row-id version]
  {:cmd-id :remove-directory-row
   :attrs {:identity identity :collection collection :row-id row-id :version version}})

(defn- version-of
  [events]
  (reduce max 0 (keep :event-seq events)))

(defn- from
  "The command store needs an aggregate id for every effect; :id-fn replaces
   it with the directory's, so an effect carries the id of its source."
  [command source-id]
  (assoc command :id source-id))

(defn reg-rows
  "Keeps directory rows of an aggregate in step: after a command producing
   one of event-ids, sends (commands-fn aggregate) as the platform. The
   aggregate carries the :version the command produced."
  [ctx event-ids commands-fn]
  (edd/reg-fx ctx
              (fn [fx-ctx events]
                (when (some #(contains? event-ids (:event-id %)) events)
                  (let [aggregate
                        (assoc (el-ctx/get-aggregate fx-ctx) :version (version-of events))

                        commands
                        (into [] (comp (remove nil?) (map #(from % (:id aggregate))))
                              (commands-fn aggregate))]
                    (when (seq commands)
                      {:commands commands
                       :meta client/platform-meta}))))))

(defn- removed-version
  [directory collection row-id]
  (:version (rows/by-id (get-in directory [:removed collection]) row-id)))

(defn- older?
  [version than]
  (and (some? than) (<= (or version 0) than)))

(defn- changes?
  [directory collection row]
  (let [existing
        (rows/by-id (get-in directory [:collections collection]) (:id row))]
    (and (not= existing row)
         (not (older? (:version row) (:version existing)))
         (not (older? (:version row) (removed-version directory collection (:id row)))))))

(defn created-if-new
  "The identity and creation event that must precede the first event of the
   aggregate of identity; empty when it exists."
  [aggregate identity]
  (if (:identity aggregate)
    []
    [{:identity identity}
     {:event-id :directory-created
      :attrs {:identity identity}}]))

(defn- joins
  "Joining the tenant a row belongs to, once per tenant. Tenants and the
   platform are not listed in a tenant."
  [directory identity row-tenant]
  (let [{:keys [kind] :as parsed}
        (parse identity)

        tenant-name
        (or (:tenant parsed) row-tenant)]
    (when (and (#{"team" "user"} kind)
               (string? tenant-name)
               (not (some #{tenant-name} (:tenants directory))))
      [{:event-id :directory-tenant-joined
        :attrs {:tenant tenant-name :identity identity}}])))

(defn with-creation
  "events of a command on the aggregate of identity, preceded by its
   creation when it does not exist yet."
  [aggregate identity events]
  (into (into (created-if-new aggregate identity) (joins aggregate identity nil))
        events))

(defn upsert-row
  [ctx {:keys [attrs]}]
  (let [{:keys [identity collection row]}
        attrs

        directory
        (el-ctx/get-aggregate ctx)]
    (when (changes? directory collection row)
      (-> (created-if-new directory identity)
          (into (joins directory identity (:tenant row)))
          (conj {:event-id :directory-row-upserted
                 :attrs {:collection collection :row row}})))))

(defn- newer-than?
  [version than]
  (and (some? than) (> (or version 0) than)))

(defn remove-row
  "Removes a row and keeps a tombstone at version. A removal that arrives
   before the directory exists creates it, so the tombstone still stops an
   older upsert that comes later."
  [ctx {:keys [attrs]}]
  (let [{:keys [identity collection row-id version]}
        attrs

        directory
        (el-ctx/get-aggregate ctx)

        existing
        (rows/by-id (get-in directory [:collections collection]) row-id)]
    (when (and (not (older? version (removed-version directory collection row-id)))
               (not (newer-than? (:version existing) version)))
      (conj (created-if-new directory identity)
            {:event-id :directory-row-removed
             :attrs {:collection collection :row-id row-id :version version}}))))

(defn- uncapped?
  [directory collection]
  (let [kind
        (:kind (parse (:identity directory)))]
    (or (and (= platform (:identity directory)) (= :tenants collection))
        (and (= "tenant" kind) (= :entities collection)))))

(defn- capped
  [rows]
  (let [rows
        (vec rows)

        excess
        (- (count rows) max-rows)]
    (if (pos? excess)
      (subvec rows excess)
      rows)))

(defn- without
  [rows row-id]
  (vec (remove #(= row-id (:id %)) rows)))

(defn- put-row
  [rows row limit]
  (let [rows
        (vec rows)

        index
        (first (keep-indexed (fn [i existing] (when (= (:id row) (:id existing)) i)) rows))]
    (if index
      (assoc rows index row)
      (limit (conj rows row)))))

(defn- apply-created
  [directory {:keys [id attrs]}]
  (assoc directory
         :id id
         :identity (:identity attrs)
         :collections (or (:collections directory) {})))

(defn- apply-tenant-joined
  [directory {:keys [attrs]}]
  (update directory :tenants (fnil conj []) (:tenant attrs)))

(defn- apply-upserted
  [directory {{:keys [collection row]} :attrs}]
  (let [limit
        (if (uncapped? directory collection) vec capped)]
    (-> directory
        (update-in [:collections collection] put-row row limit)
        (update-in [:removed collection] without (:id row)))))

(defn- tombstone
  [tombstones row-id version]
  (capped (conj (without tombstones row-id) {:id row-id :version version})))

(defn- apply-removed
  [directory {{:keys [collection row-id version]} :attrs}]
  (-> directory
      (update-in [:collections collection] without row-id)
      (update-in [:removed collection] tombstone row-id version)))

(defn- index-command
  [id identity]
  (let [{:keys [kind name]}
        (parse identity)]
    (when (= "tenant" kind)
      (from (upsert platform :tenants {:id name}) id))))

(defn- listed-in-platform
  [_ctx {:keys [id attrs]}]
  (when-let [command
             (index-command id (:identity attrs))]
    {:commands [command]
     :meta client/platform-meta}))

(defn- listed-in-tenant
  [_ctx {:keys [id attrs]}]
  (let [{:keys [identity]}
        attrs]
    {:commands [(from (upsert (tenant (:tenant attrs)) :entities
                              {:id identity :kind (:kind (parse identity)) :tenant (:tenant attrs)})
                      id)]
     :meta client/platform-meta}))

(defn- newest
  [a b]
  (if (and a (older? (:version b) (:version a)))
    a
    b))

(defn- ids-of
  [ctx identities]
  (let [wanted
        (vec (distinct identities))]
    (if (seq wanted)
      (common/get-aggregate-id-by-identity (assoc ctx :identity wanted))
      {})))

(defn entity
  "The aggregate of identity, or nil."
  [ctx identity]
  (when-let [id
             (get (ids-of ctx [identity]) identity)]
    (common/get-by-id ctx {:id id})))

(defn rows-of
  "Rows of collection in the aggregates of identities, one per :id (its
   newest version), in no particular order."
  [ctx identities collection]
  (let [rows
        (mapcat #(get-in (common/get-by-id ctx {:id %}) [:collections collection])
                (distinct (vals (ids-of ctx identities))))]
    (vec (vals (reduce (fn [acc row] (update acc (:id row) newest row)) {} rows)))))

(defn rows
  [ctx identity collection]
  (rows-of ctx [identity] collection))

(defn tenant-rows
  "rows-of limited to the rows of tenant-name."
  [ctx tenant-name identities collection]
  (filterv #(= tenant-name (:tenant %)) (rows-of ctx identities collection)))

(defn tenants
  "Names of every tenant with an aggregate."
  [ctx]
  (vec (sort (map :id (rows ctx platform :tenants)))))

(defn identities
  "Identities of every team or person (kind \"team\" or \"user\") listed in
   a tenant."
  [ctx tenant-name kind]
  (vec (for [{:keys [id] :as row} (rows ctx (tenant tenant-name) :entities)
             :when (= kind (:kind row))]
         id)))

(defn all-identities
  "identities of kind over every tenant."
  [ctx kind]
  (vec (distinct (mapcat #(identities ctx % kind) (tenants ctx)))))

(defn entity-id
  "The :id-fn of commands on entity aggregates (identity in :attrs). edd
   resolves the id before it checks the caller's role, so a command without
   an identity gets a fresh id and fails authorization or validation
   instead of the lookup."
  [ctx {:keys [attrs]}]
  (or (when (string? (:identity attrs))
        (common/get-aggregate-id-by-identity ctx (:identity attrs)))
      (common/create-identity)))

(def ^:private Identity
  [:string {:min 1}])

(defn register
  [ctx]
  (-> ctx
      (edd/reg-cmd :upsert-directory-row upsert-row
                   :consumes [:map [:attrs [:map
                                            [:identity Identity]
                                            [:collection keyword?]
                                            [:row [:map [:id any?]]]]]]
                   :id-fn entity-id
                   :auth roles/platform-only)
      (edd/reg-cmd :remove-directory-row remove-row
                   :consumes [:map [:attrs [:map
                                            [:identity Identity]
                                            [:collection keyword?]
                                            [:row-id any?]
                                            [:version [:maybe int?]]]]]
                   :id-fn entity-id
                   :auth roles/platform-only)
      (edd/reg-event :directory-created apply-created)
      (edd/reg-event :directory-tenant-joined apply-tenant-joined)
      (edd/reg-event :directory-row-upserted apply-upserted)
      (edd/reg-event :directory-row-removed apply-removed)
      (edd/reg-event-fx :directory-created listed-in-platform)
      (edd/reg-event-fx :directory-tenant-joined listed-in-tenant)))
