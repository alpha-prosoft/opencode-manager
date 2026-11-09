(ns manager-web.teams.views
  (:require ["@mui/material/Card" :default Card]
            ["@mui/material/CardActionArea" :default CardActionArea]
            ["@mui/material/CardContent" :default CardContent]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Stack" :default Stack]
            [manager-web.components.chips :as chips]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.teams.elements :as els]
            [manager-web.theme :as theme]
            [web.primitives.components :refer [RawBox RawStack RawTypography]]))

(defn- team-card
  [{:keys [id name role description counts invalid status synced-at] :as team}]
  [:> Card
   [:> CardActionArea (merge (layout/nav-props :team {:team-id id})
                             {:sx (:full-height theme/sx)
                              :data-testid (el/id (els/team-card id))})
    [:> CardContent {:component Stack :spacing 1}
     [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
      [RawTypography {:variant "h3" :sx (:grow theme/sx)} name]
      [chips/role-chip role]]
     [RawTypography {:variant "body2" :color "text.secondary"} description]
     [layout/row
      [:> Chip {:variant "filled" :label (str (:workflows counts 0) " workflows")}]
      (when (pos? (or invalid 0))
        [:> Chip {:color "warning" :label (str invalid " with problems")}])
      (when (= :error (format/kw status))
        [:> Chip {:color "error" :label "Sync failed"}])]
     [RawTypography {:variant "caption" :color "text.secondary" :noWrap true}
      (str (format/source-label team) " · synced " (format/relative-time synced-at))]]]])

(defn- team-grid
  [teams]
  (if (empty? teams)
    [layout/empty-state {:title "You're not in any team" :el-id els/empty-state}
     "A platform admin adds teams and members in the onboarding repository."]
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
