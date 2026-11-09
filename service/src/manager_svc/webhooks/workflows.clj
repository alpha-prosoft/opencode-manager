(ns manager-svc.webhooks.workflows
  "The workflows a webhook targets, read through the platform facade, and
   what a request maps to for one of them."
  (:require [manager-svc.platform.api :as platform]
            [manager-svc.webhooks.mapping :as mapping]))

(defn workflow
  "{:id :name :activity :options :project :inputs-schema} of a valid team
   workflow, or nil."
  [system tenant team-id workflow-id]
  (when-let [{:keys [spec]}
             (and team-id workflow-id (platform/definition system tenant team-id :workflows workflow-id))]
    (let [activity
          (some-> (:activity spec) name)

          options
          (or (:options spec) {})]
      {:id workflow-id
       :name (or (:name spec) workflow-id)
       :activity activity
       :options options
       :project (:project spec)
       :inputs-schema (platform/inputs-schema system activity options)})))

(defn team-workflows
  [system tenant team-id]
  (vec (keep #(workflow system tenant team-id (:id %)) (platform/workflows system tenant team-id))))

(defn evaluate
  "What a request ({:body-text :headers :query}) maps to for the webhook's
   target: {:inputs :missing :errors [{:field :message}] :valid}."
  [system tenant {:keys [target mapping]} request]
  (if-let [{:keys [activity options inputs-schema]}
           (workflow system tenant (:team-id target) (:workflow-id target))]
    (let [{:keys [inputs missing]}
          (mapping/inputs inputs-schema mapping (mapping/request-data request))

          {:keys [errors]}
          (platform/validate-inputs system activity inputs options)

          field-errors
          (mapping/field-errors errors)]
      {:inputs inputs
       :missing missing
       :errors field-errors
       :valid (empty? field-errors)})
    {:inputs {}
     :missing []
     :errors [{:field nil :message (str "Workflow " (:team-id target) "/" (:workflow-id target)
                                        " does not exist or is invalid")}]
     :valid false}))

(defn preview
  "evaluate for a mapping as someone typed it: rows are normalized, and a
   mapping that couldn't be saved answers its problems as errors."
  [system tenant target mapping request]
  (let [rows
        (mapv mapping/row mapping)

        problems
        (some-> (workflow system tenant (:team-id target) (:workflow-id target))
                :inputs-schema
                (mapping/mapping-problems rows))]
    (if (seq problems)
      {:inputs {}
       :missing []
       :errors (mapv (fn [problem] {:field nil :message problem}) problems)
       :valid false}
      (evaluate system tenant {:target target :mapping rows} request))))
