(ns manager-web.activities.views
  (:require ["@mui/icons-material/ExpandMore" :default ExpandMoreIcon]
            ["@mui/material/Accordion" :default Accordion]
            ["@mui/material/AccordionDetails" :default AccordionDetails]
            ["@mui/material/AccordionSummary" :default AccordionSummary]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Typography" :default Typography]
            [clojure.string :as string]
            [manager-web.activities.elements :as els]
            [manager-web.components.chips :as chips]
            [manager-web.components.layout :as layout]
            [manager-web.components.schema-form :as schema-form]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [reagent.core :as r]
            [web.primitives.components :refer [RawBox RawStack RawTypography]]))

(defn- ref-name
  [ref]
  (last (string/split ref #"/")))

(defn field-type
  [field]
  (let [ref
        (:$ref field)

        options
        (some->> (schema-form/attr field :anyOf :any-of)
                 (map #(if (:$ref %) (ref-name (:$ref %)) (some-> (:type %) name)))
                 (remove #(= "null" %))
                 seq)]
    (cond
      (:enum field) (string/join " | " (:enum field))
      ref (ref-name ref)
      options (string/join " | " options)
      (:type field) (name (:type field))
      :else "object")))

(defn defaults
  [schema]
  (into {}
        (for [[name field] (:properties schema)
              :when (some? (:default field))]
          [name (:default field)])))

(defn- fields
  [{:keys [title description schema]}]
  (let [properties
        (:properties schema)

        required
        (set (map format/kw (:required schema)))]
    (when (seq properties)
      [RawStack {:spacing 1}
       [RawBox {}
        [RawTypography {:variant "subtitle2"} title]
        [RawTypography {:variant "body2" :color "text.secondary"} description]]
       [layout/table {}
        (layout/head "Field" "Type" "What it does")
        (for [[field-name field] properties]
          ^{:key field-name}
          [layout/table-row {}
           [layout/cell
            [layout/mono (name field-name)]
            (when (contains? required (format/kw field-name))
              [:> Chip {:label "required" :sx (:chip-gap theme/sx)}])]
           [layout/cell [layout/mono (field-type field)]]
           [layout/cell
            (:description field)
            (when (some? (:default field))
              [:> Typography {:variant "body2" :color "text.secondary"}
               "Default: " [layout/mono (js/JSON.stringify (clj->js (:default field)))]])]])]])))

(defn- example-workflow
  [activity]
  (format/json {:name (:name activity)
                :activity (:id activity)
                :agent "my-agent"
                :harness "my-harness"
                :options (defaults (:options-schema activity))}))

(defn- activity-accordion
  [activity]
  [:> Accordion {:data-testid (el/id (els/activity (:id activity)))}
   [:> AccordionSummary {:expandIcon (r/as-element [:> ExpandMoreIcon])}
    [RawStack {:direction "row" :spacing 2 :sx (:centered-row theme/sx)}
     [layout/icon-tile (:icon (chips/activity-meta (:id activity)))]
     [RawBox {:sx (:min-width-0 theme/sx)}
      [RawTypography {:variant "h3"} (:name activity)]
      [RawTypography {:variant "body2" :color "text.secondary"} (:description activity)]]]]
   [:> AccordionDetails
    [RawStack {:spacing 3}
     [RawTypography {:variant "body2"}
      (if (:interactive activity)
        "Interactive: the run opens a session that a person works in."
        "Automated: the agent works alone and hands back a structured result, which the platform checks before showing it.")]
     [fields {:title "Options" :description "Set once, in the team's workflow file." :schema (:options-schema activity)}]
     [fields {:title "Inputs" :description "Filled in by whoever runs the workflow." :schema (:inputs-schema activity)}]
     (when (:branch-inputs-schema activity)
       [fields {:title "Inputs when reviewing a branch" :description "Instead of the inputs above, when the workflow sets repository."
                :schema (:branch-inputs-schema activity)}])
     [RawBox {:data-testid (el/id (els/example (:id activity)))}
      [RawTypography {:variant "subtitle2" :gutterBottom true} "Example workflow file"]
      [layout/code-block {} (example-workflow activity)]]]]])

(defn- catalog
  [activities]
  (into [RawBox {:el-id els/catalog}]
        (for [activity activities]
          ^{:key (:id activity)}
          [activity-accordion activity])))

(defn main-panel
  []
  [layout/page-stack
   [layout/page-header {:title "Activities"
                        :el-id els/page
                        :description "The kinds of work an agent can do on this platform. Teams build workflows from them: a workflow file picks one activity, an agent and a harness, and fixes the activity's options. People fill in the inputs each time they run it."}]
   [data/loaded {:key :catalog :query {:query-id :list-activities} :error-el-id els/error}
    catalog]])
