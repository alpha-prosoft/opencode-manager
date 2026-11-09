(ns manager-web.components.project-card
  "A project as a card, like a repository in a list of them: it opens the
   project's page."
  (:require ["@mui/icons-material/SourceOutlined" :default ProjectIcon]
            ["@mui/material/Card" :default Card]
            ["@mui/material/CardActionArea" :default CardActionArea]
            ["@mui/material/CardContent" :default CardContent]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Stack" :default Stack]
            [clojure.string :as string]
            [manager-web.components.layout :as layout]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [web.primitives.components :refer [RawBox RawStack RawTypography]]))

(defn repository-label
  [{:keys [scm]}]
  (string/join " " (remove string/blank? [(some-> (:provider scm) name) (:repository scm)])))

(defn ci-label
  [{:keys [ci]}]
  (when-let [job
             (or (:job ci) (first (:jobs ci)))]
    (str "CI " job (some->> (:mcp-server ci) name (str " via ")))))

(defn project-card
  "card-el-id is the el-id of the card for a project."
  [{:keys [show-team? card-el-id]} {:keys [id team-id team-name branch workflows] :as project}]
  [:> Card
   [:> CardActionArea (merge (layout/nav-props :project {:team-id team-id :project-id id})
                             {:sx (:full-height theme/sx)
                              :data-testid (el/id (card-el-id team-id id))
                              :data-team team-id})
    [:> CardContent {:component Stack :spacing 1}
     [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
      [:> ProjectIcon {:color "primary" :fontSize "small"}]
      [RawTypography {:variant "h3" :sx (:grow theme/sx)} (:name project)]]
     (when show-team?
       [RawTypography {:variant "body2" :color "text.secondary"} team-name])
     [RawTypography {:variant "body2" :sx (:mono-wrap theme/sx)} (repository-label project)]
     [layout/row
      [:> Chip {:label (str "branch " branch)}]
      (when (some? workflows)
        [:> Chip {:variant "filled" :label (format/plural workflows "workflow")}])
      (when-let [ci (ci-label project)]
        [:> Chip {:label ci}])]]]])

(defn project-grid
  [opts projects]
  (into [RawBox {:sx (:card-grid theme/sx) :el-id (:grid-el-id opts)}]
        (for [project projects]
          ^{:key (str (:team-id project) "/" (:id project))}
          [project-card opts project])))
