(ns manager-web.teams.views
  (:require ["@mui/material/Card" :default Card]
            ["@mui/material/CardActionArea" :default CardActionArea]
            ["@mui/material/CardContent" :default CardContent]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Stack" :default Stack]
            [clojure.string :as string]
            [manager-web.components.chips :as chips]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.teams.elements :as els]
            [manager-web.theme :as theme]
            [web.primitives.components :refer [RawBox RawStack RawTypography]]))

(defn project-line
  "A project as a team card lists it: its name, repository and builds."
  [{:keys [id scm ci] :as project}]
  (string/join " · " (remove string/blank?
                             [(or (:name project) id)
                              (when (:repository scm)
                                (str (some-> (:provider scm) name) " " (:repository scm)))
                              (when (seq (:jobs ci))
                                (str "builds " (string/join ", " (:jobs ci))))])))

(defn role-attribute
  "The team role as the card's data-role: \"admin\", \"member\" or nil."
  [role]
  (some-> role format/kw name))

(defn- team-card
  [{:keys [id role description counts invalid status synced-at projects] :as team}]
  [:> Card
   [:> CardActionArea (merge (layout/nav-props :team {:team-id id})
                             {:sx (:full-height theme/sx)
                              :data-testid (el/id (els/team-card id))
                              :data-role (role-attribute role)})
    [:> CardContent {:component Stack :spacing 1}
     [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
      [RawTypography {:variant "h3" :sx (:grow theme/sx)} (:name team)]
      [chips/role-chip id (el/id (els/team-role id))]]
     [RawTypography {:variant "body2" :color "text.secondary"} description]
     [layout/row
      [:> Chip {:variant "filled" :label (str (:workflows counts 0) " workflows")}]
      (when (pos? (or invalid 0))
        [:> Chip {:color "warning" :label (str invalid " with problems")}])
      (when (= :error (format/kw status))
        [:> Chip {:color "error" :label "Sync failed"}])]
     (when (seq projects)
       [RawStack {:spacing 0.5 :el-id (els/team-projects id)}
        (for [project projects]
          ^{:key (:id project)}
          [RawTypography {:variant "caption" :component "div" :data-testid (el/id (els/team-project id (:id project)))}
           (project-line project)])])
     [RawTypography {:variant "caption" :color "text.secondary" :noWrap true}
      (str (format/source-label team) " · synced " (format/relative-time synced-at))]]]])

(defn- team-grid
  [teams]
  (if (empty? teams)
    [layout/empty-state {:title "You're not in any team" :el-id els/empty-state}
     "Your tenant admin adds teams in the onboarding repository; your identity provider assigns you to them."]
    (into [RawBox {:sx (:card-grid theme/sx) :el-id els/grid}]
          (for [team teams]
            ^{:key (:id team)}
            [team-card team]))))

(defn main-panel
  []
  [layout/page-stack
   [layout/page-header {:title "Teams"
                        :el-id els/page
                        :description "Each team keeps its agents, harnesses and workflows as JSON in its own repository. The platform syncs them every few minutes."}]
   [data/loaded {:key :teams :query {:query-id :list-teams} :error-el-id els/error}
    team-grid]])
