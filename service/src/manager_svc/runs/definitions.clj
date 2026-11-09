(ns manager-svc.runs.definitions
  "Team definitions (workflows, agents, harnesses) read through the platform
   facade, with the defaults of manager/app/platform/spec.py applied and
   enumerations as strings so they compare the same whichever way the registry
   spells them."
  (:require [clojure.string :as string]
            [manager-svc.platform.api :as platform]))

(def ^:private free-form-keys
  #{:inputs :permissions :env :headers})

(defn kebab
  "Keys to kebab-case keywords, except inside free-form maps."
  [value]
  (cond
    (map? value)
    (into {}
          (map (fn [[k v]]
                 (let [kk (keyword (string/replace (name k) "_" "-"))]
                   [kk (if (contains? free-form-keys kk) v (kebab v))])))
          value)

    (sequential? value)
    (mapv kebab value)

    :else value))

(defn- enum
  [value]
  (when (some? value)
    (name value)))

(defn- trigger
  [raw]
  (let [t
        (kebab (or raw {:type "manual"}))

        kind
        (string/replace (or (enum (:type t)) "manual") "-" "_")]
    (case kind
      "pull_request" (merge {:target-branches [] :poll-minutes 15 :backfill false}
                            t
                            {:type "pull_request" :provider (enum (:provider t))})
      "schedule" (merge {:inputs {}} t {:type "schedule"})
      (assoc t :type "manual"))))

(defn- valid?
  [definition]
  (and definition (not (false? (:valid definition)))))

(defn workflow
  "A valid workflow spec {:id :name :description :activity :agent :harness
   :trigger :options :visibility}, or nil."
  [system tenant team-id workflow-id]
  (let [definition
        (platform/definition system tenant team-id :workflows workflow-id)]
    (when (valid? definition)
      (let [spec
            (kebab (:spec definition))]
        (assoc spec
               :id workflow-id
               :name (or (:name spec) workflow-id)
               :description (or (:description spec) "")
               :activity (enum (:activity spec))
               :visibility (or (enum (:visibility spec)) "team")
               :options (or (:options spec) {})
               :trigger (trigger (:trigger spec)))))))

(defn missing-workflow
  "What a person or a failed run is told about a workflow that can't run."
  [team-id workflow-id]
  (str "Workflow " team-id "/" workflow-id " does not exist or is invalid"))

(defn workflows
  [system tenant team-id]
  (for [definition (platform/workflows system tenant team-id)
        :when (valid? definition)
        :let [spec (workflow system tenant team-id (:id definition))]
        :when spec]
    spec))

(defn workflow-names
  "{[team-id workflow-id] name} for the given teams of tenant."
  [system tenant team-ids]
  (into {}
        (for [team-id team-ids
              definition (platform/workflows system tenant team-id)]
          [[team-id (:id definition)]
           (or (get-in definition [:spec :name]) (:id definition))])))

(defn agent-spec
  [system tenant team-id agent-id]
  (let [definition
        (platform/definition system tenant team-id :agents agent-id)]
    (when (valid? definition)
      (kebab (:spec definition)))))

(defn harness-spec
  [system tenant team-id harness-id]
  (let [definition
        (platform/definition system tenant team-id :harnesses harness-id)]
    (when (valid? definition)
      (let [spec
            (kebab (:spec definition))]
        (merge {:network "none"
                :permissions {}
                :idle-minutes 15
                :mcp []
                :skills []}
               spec
               {:network (or (enum (:network spec)) "none")
                :resources (merge {:cpus 1.0 :memory-mb 2048 :pids 512} (:resources spec))
                :policy (merge {:deny-commands [] :deny-paths []} (:policy spec))})))))

(defn team
  [system tenant team-id]
  (kebab (platform/team system tenant team-id)))
