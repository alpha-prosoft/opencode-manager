(ns manager-svc.directory
  "Directories hold the rows lists show, so a list reads a few aggregates
   instead of searching. There is one directory per person (identity
   \"user:<username>\"), per team (\"team:<team-id>\") and one for the
   platform (\"platform\"). A directory is an aggregate with a fresh UUID,
   found through its edd identity:

     {:identity \"team:alpha\"
      :collections {:runs [row ...] :sessions [row ...]}
      :removed {:sessions [{:id :version} ...]}}

   A collection keeps its rows oldest first and at most max-rows of them;
   the oldest is dropped when it is full. The platform's :directories is
   never capped, so reads over all teams or people miss none. A row describes one aggregate and
   carries its :id and :version: an older version never replaces a newer
   one, and a removed row stays removed for older versions, so rows are
   right however late or often an effect arrives.

   Domains keep rows in step with reg-rows: after every command producing
   one of their events, an effect sends :upsert-directory-row and
   :remove-directory-row commands as the platform. The first command for an
   identity creates the directory and registers the identity. When two
   requests create the same identity at once, the identity store's unique
   constraint fails the later one; its redelivery resolves the identity and
   writes to the directory the first one created.

   The platform directory lists every directory in :directories, so reads
   over all teams or all people also find teams that left the registry."
  (:require [clojure.string :as string]
            [edd.common :as common]
            [edd.core :as edd]
            [edd.el.ctx :as el-ctx]
            [manager-svc.client :as client]))

(def max-rows 10000)

(def platform "platform")

(defn user
  [username]
  (str "user:" username))

(defn team
  [team-id]
  (str "team:" team-id))

(defn upsert
  "The command putting row into collection of the directory of identity."
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

(defn- row-with-id
  [rows row-id]
  (some #(when (= row-id (:id %)) %) rows))

(defn- removed-version
  [directory collection row-id]
  (:version (row-with-id (get-in directory [:removed collection]) row-id)))

(defn- older?
  [version than]
  (and (some? than) (<= (or version 0) than)))

(defn- changes?
  [directory collection row]
  (let [existing
        (row-with-id (get-in directory [:collections collection]) (:id row))]
    (and (not= existing row)
         (not (older? (:version row) (:version existing)))
         (not (older? (:version row) (removed-version directory collection (:id row)))))))

(defn- creation
  [directory identity]
  (when-not (:identity directory)
    [{:identity identity}
     {:event-id :directory-created
      :attrs {:identity identity}}]))

(defn upsert-row
  [ctx {:keys [attrs]}]
  (let [{:keys [identity collection row]}
        attrs

        directory
        (el-ctx/get-aggregate ctx)]
    (when (changes? directory collection row)
      (conj (vec (creation directory identity))
            {:event-id :directory-row-upserted
             :attrs {:collection collection :row row}}))))

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
        (row-with-id (get-in directory [:collections collection]) row-id)]
    (when (and (not (older? version (removed-version directory collection row-id)))
               (not (newer-than? (:version existing) version)))
      (conj (vec (creation directory identity))
            {:event-id :directory-row-removed
             :attrs {:collection collection :row-id row-id :version version}}))))

(defn- uncapped?
  [directory collection]
  (and (= platform (:identity directory)) (= :directories collection)))

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

(defn- kind-of
  [identity]
  (first (string/split identity #":" 2)))

(defn- listed-in-platform
  [_ctx {:keys [id attrs]}]
  (let [identity
        (:identity attrs)]
    (when-not (= platform identity)
      {:commands [(from (upsert platform :directories {:id identity :kind (kind-of identity)}) id)]
       :meta client/platform-meta})))

(defn- newest
  [a b]
  (if (and a (older? (:version b) (:version a)))
    a
    b))

(defn rows-of
  "Rows of collection in the directories of identities, one per :id (its
   newest version), in no particular order."
  [ctx identities collection]
  (let [wanted
        (vec (distinct identities))

        ids
        (when (seq wanted)
          (common/get-aggregate-id-by-identity (assoc ctx :identity wanted)))

        rows
        (mapcat #(get-in (common/get-by-id ctx {:id %}) [:collections collection])
                (distinct (vals ids)))]
    (vec (vals (reduce (fn [acc row] (update acc (:id row) newest row)) {} rows)))))

(defn rows
  [ctx identity collection]
  (rows-of ctx [identity] collection))

(defn identities
  "Identities of every directory of a kind (\"user\" or \"team\")."
  [ctx kind]
  (vec (for [{:keys [id] :as row} (rows ctx platform :directories)
             :when (= kind (:kind row))]
         id)))

(defn- directory-id
  "edd resolves the id before it checks the caller's role, so a command
   without an identity gets a fresh id and fails authorization or
   validation instead of the lookup."
  [ctx {:keys [attrs]}]
  (or (when (string? (:identity attrs))
        (common/get-aggregate-id-by-identity ctx (:identity attrs)))
      (common/create-identity)))

(def ^:private Identity
  [:string {:min 1}])

(def ^:private platform-only
  {:role [:non-interactive]})

(defn register
  [ctx]
  (-> ctx
      (edd/reg-cmd :upsert-directory-row upsert-row
                   :consumes [:map [:attrs [:map
                                            [:identity Identity]
                                            [:collection keyword?]
                                            [:row [:map [:id any?]]]]]]
                   :id-fn directory-id
                   :auth platform-only)
      (edd/reg-cmd :remove-directory-row remove-row
                   :consumes [:map [:attrs [:map
                                            [:identity Identity]
                                            [:collection keyword?]
                                            [:row-id any?]
                                            [:version [:maybe int?]]]]]
                   :id-fn directory-id
                   :auth platform-only)
      (edd/reg-event :directory-created apply-created)
      (edd/reg-event :directory-row-upserted apply-upserted)
      (edd/reg-event :directory-row-removed apply-removed)
      (edd/reg-event-fx :directory-created listed-in-platform)))
