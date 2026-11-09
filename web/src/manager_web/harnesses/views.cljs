(ns manager-web.harnesses.views
  (:require ["@mui/icons-material/CheckCircle" :default CheckCircleIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Typography" :default Typography]
            [clojure.string :as string]
            [manager-web.components.chips :as chips]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.harnesses.elements :as els]
            [manager-web.theme :as theme]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(defn credential-text
  [{:keys [name source]}]
  (case (format/kw source)
    :user (str name ": your account")
    :platform (str name ": shared account")
    (str name ": needs your credential")))

(defn- harness-card
  [{:keys [team-id team-name id ready valid description network models skills mcp-servers credentials errors workflows]}]
  (let [missing
        (remove :source credentials)]
    [:> Paper {:sx (:panel-roomy theme/sx) :data-testid (el/id (els/harness team-id id))}
     [RawStack {:spacing 1.5}
      [layout/row
       [RawTypography {:variant "h3"} id]
       [RawTypography {:variant "body2" :color "text.secondary"} team-name]
       [RawBox {:sx (:spacer theme/sx)}]
       (if ready
         [:> Chip {:color "success" :icon (r/as-element [:> CheckCircleIcon]) :label "Ready for you"
                   :data-testid (el/id (els/ready team-id id))}]
         [:> Chip {:color "warning" :label (if valid "Needs setup" "Has problems")
                   :data-testid (el/id (els/needs-setup team-id id))}])]
      (when-not (string/blank? description)
        [RawTypography {:color "text.secondary"} description])
      [layout/row
       [chips/network-chip network]
       (when (first models)
         [:> Chip {:label (first models)}])
       (for [skill skills]
         ^{:key skill}
         [:> Chip {:label (str "Skill: " skill)}])]
      (for [server mcp-servers]
        ^{:key (:id server)}
        [RawBox {}
         [RawTypography {:variant "subtitle2"} (:name server)]
         [RawTypography {:variant "body2" :color "text.secondary"} (:description server)]
         (when (:tools server)
           [:> Typography {:variant "caption" :color "text.secondary"}
            "Tools: " [layout/mono (string/join ", " (:tools server))]])])
      (when (seq credentials)
        [RawTypography {:variant "body2"} (string/join " · " (map credential-text credentials))])
      (when (seq missing)
        [RawAlert {:severity "warning"
                   :action (r/as-element
                            [:> Button (merge (layout/nav-props :accounts)
                                              {:color "inherit" :size "small"
                                               :data-testid (el/id (els/add-credential team-id id))})
                             "Add"])}
         (str "Add your " (string/join " and " (map :name missing)) " credential to use these tools.")])
      [layout/definition-errors {:errors errors}]
      (when (seq workflows)
        [RawTypography {:variant "caption" :color "text.secondary"}
         (str "Used by: " (string/join ", " (map :name workflows)))])]]))

(defn- harness-grid
  [harnesses]
  (if (empty? harnesses)
    [layout/empty-state {:title "No harnesses" :el-id els/empty-state} "Your teams haven't defined any harnesses yet."]
    (into [RawBox {:sx (:harness-grid theme/sx) :el-id els/grid}]
          (for [harness harnesses]
            ^{:key (str (:team-id harness) "/" (:id harness))}
            [harness-card harness]))))

(defn main-panel
  []
  [layout/page-stack
   [layout/page-header {:title "Harnesses"
                        :el-id els/page
                        :description "The kinds of agent sandboxes your teams offer: which model, tools, skills and network each one gets. Tools run behind the platform gateway, using your own credentials where you've added them."}]
   [data/loaded {:key :harnesses :query {:query-id :list-harnesses} :error-el-id els/error}
    harness-grid]])
