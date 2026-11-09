(ns manager-svc.platform.catalog
  "The activities workflows wire to (marketplace). Options are what a
   workflow file sets, inputs what a person or trigger passes to a run.
   options-schema and inputs-schema are JSON Schema documents (property names
   as written in workflow files); validation decodes them to kebab-case."
  (:require [clojure.java.io :as io]
            [manager-svc.platform.spec :as spec]))

(def branch
  [:re #"^[A-Za-z0-9._/-]{1,200}$"])

(def repository-slug
  [:re #"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"])

(defn- closed
  [& entries]
  (into [:map {:closed true}] entries))

(defn- optional
  [k schema]
  [k {:optional true} [:maybe schema]])

(def scm-source
  (closed
   [:provider (spec/enum :bitbucket :github)]
   [:repository repository-slug]
   [:credentials string?]))

(def session-options
  (closed
   (optional :repository spec/git-source)
   (optional :instructions-file string?)))

(def session-inputs
  (closed
   [:title {:default ""} [:string {:max 100}]]
   (optional :prompt [:string {:max 20000}])
   (optional :branch branch)))

(def review-options
  (closed
   (optional :scm scm-source)
   (optional :repository spec/git-source)
   (optional :guidelines-file string?)
   [:post-mode {:default :dry-run} (spec/enum :off :dry-run :auto)]
   [:max-comments {:default 30} [:int {:min 1 :max 100}]]))

(def review-inputs
  (closed
   [:pull-request [:int {:min 1}]]))

(def branch-review-inputs
  (closed
   [:branch branch]
   [:base {:default "main"} branch]))

(def pull-request-delivery
  (closed
   [:provider (spec/enum :bitbucket :github)]
   [:repository repository-slug]
   (optional :target-branch string?)))

(def ci-follow
  (closed
   [:mcp-server string?]
   [:job [:re {:error/message "should contain {pr}"} #"\{pr\}"]]
   [:timeout-minutes {:default 120} [:int {:min 5 :max 720}]]
   (optional :on-failure string?)))

(def implement-options
  (closed
   [:repository spec/git-source]
   (optional :pull-request pull-request-delivery)
   (optional :guidelines-file string?)
   (optional :ci ci-follow)))

(def implement-inputs
  (closed
   [:task [:string {:min 10 :max 20000}]]
   (optional :pull-request [:int {:min 1}])
   (optional :branch branch)))

(def analysis-options
  (closed
   (optional :repository spec/git-source)
   (optional :template-file string?)))

(def analysis-inputs
  (closed
   [:brief [:string {:min 10 :max 20000}]]))

(def validation
  {"session" {:options session-options :inputs session-inputs}
   "review" {:options review-options :inputs review-inputs :branch-inputs branch-review-inputs}
   "implement" {:options implement-options :inputs implement-inputs}
   "analysis" {:options analysis-options :inputs analysis-inputs}})

(defn branch-review?
  "A review workflow that reviews a branch of options.repository instead of a pull request."
  [activity-id options]
  (and (= "review" (name activity-id))
       (some? (:repository options))))

(defn- inputs-part
  [activity-id options]
  (if (branch-review? activity-id options) :branch-inputs :inputs))

(defn- describe
  [{:keys [id name category description icon interactive result_tool options_schema inputs_schema
           branch_inputs_schema]}]
  (cond-> {:id id
           :name name
           :category category
           :description description
           :icon icon
           :interactive interactive
           :result-tool result_tool
           :options-schema options_schema
           :inputs-schema inputs_schema}
    branch_inputs_schema (assoc :branch-inputs-schema branch_inputs_schema)))

(def activities
  (delay
    (mapv describe (spec/read-json (slurp (io/resource "manager_svc/platform/schemas/activities.json"))))))

(defn activity
  [activity-id]
  (some #(when (= (name activity-id) (:id %)) %) @activities))

(defn- validate-part
  [part activity-id raw]
  (if-let [schema
           (get-in validation [(name activity-id) part])]
    (spec/validate schema (or raw {}))
    {:value nil
     :errors [(str "unknown activity " (name activity-id))]}))

(defn validate-options
  "{:value decoded-options :errors [...]}"
  [activity-id options]
  (validate-part :options activity-id options))

(defn validate-inputs
  "{:value decoded-inputs :errors [...]} against the inputs a workflow with
   these (decoded) options takes."
  ([activity-id inputs]
   (validate-inputs activity-id inputs nil))
  ([activity-id inputs options]
   (validate-part (inputs-part activity-id options) activity-id inputs)))

(defn inputs-schema
  "JSON Schema of the inputs a workflow with these (decoded) options takes."
  [activity-id options]
  (let [described
        (activity activity-id)]
    (if (branch-review? activity-id options)
      (:branch-inputs-schema described)
      (:inputs-schema described))))

(def schema-files
  {:platform "platform.schema.json"
   :agents "agents.schema.json"
   :harnesses "harnesses.schema.json"
   :workflows "workflows.schema.json"})

(def json-schemas
  (delay
    (into {}
          (map (fn [[k file]]
                 [k (spec/read-json (slurp (io/resource (str "manager_svc/platform/schemas/" file))))]))
          schema-files)))
