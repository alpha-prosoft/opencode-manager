(ns manager-web.projects.views
  "Every project of the person's teams, as repository cards."
  (:require [manager-web.components.layout :as layout]
            [manager-web.components.project-card :as project-card]
            [manager-web.components.team-context :as team-context]
            [manager-web.data :as data]
            [manager-web.projects.elements :as els]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [re-frame.core :as rf]))

(defn description
  [me team-id]
  (if team-id
    (str "Repositories " (roles/team-name me team-id) " works on, from its team.json. Open one for its workflows, runs, sessions, changes and builds.")
    "Repositories your teams work on, from each team's team.json. Open one for its workflows, runs, sessions, changes and builds."))

(defn- project-list
  [team-id projects]
  (let [shown
        (team-context/of-team team-id projects)]
    (if (empty? shown)
      [layout/empty-state {:title "No projects" :el-id els/empty-state}
       "A team lists the repositories it works on under projects in its .platform/team.json."]
      [project-card/project-grid {:show-team? (nil? team-id) :card-el-id els/card :grid-el-id els/grid} shown])))

(defn main-panel
  []
  (let [me
        @(rf/subscribe [::session/me])

        team-id
        (team-context/selected)]
    [layout/page-stack
     [layout/page-header {:title "Projects" :el-id els/page :description (description me team-id)}]
     [data/loaded {:key :projects :query {:query-id :list-projects} :error-el-id els/error}
      #(vector project-list team-id %)]]))
