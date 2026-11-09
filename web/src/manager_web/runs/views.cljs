(ns manager-web.runs.views
  (:require [manager-web.components.chips :as chips]
            [manager-web.components.layout :as layout]
            [manager-web.components.runs-table :as runs-table]
            [manager-web.components.team-context :as team-context]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.runs.elements :as els]
            [manager-web.roles :as roles]
            [manager-web.runs.events :as events]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]))

(def filter-options
  (concat [[:all "All runs"] [:active "In progress"]]
          (for [status [:completed :failed :cancelled]]
            [status (chips/status-label :run status)])))

(defn- filter-select
  [value]
  [layout/select {:label "Show"
                  :sx (:filter-select theme/sx)
                  :id (el/id els/filter-select)
                  :slotProps {:htmlInput {:data-testid (el/id els/filter-value)}}
                  :value value
                  :options filter-options
                  :option-el-id els/filter-option
                  :on-change #(rf/dispatch [::data/set-ui :runs :filter (keyword %)])}])

(defn description
  [me team-id]
  (cond
    (empty? (:teams me)) (str "Runs of your teams' workflows. You're in " (:tenant me) " but not in a team yet, so there are none to show.")
    team-id (str "Every workflow run of " (roles/team-name me team-id) ": started by hand, on a schedule, or by a new pull request.")
    :else "Every workflow run in your teams: started by hand, on a schedule, or by a new pull request."))

(defn main-panel
  []
  (let [filter-value
        @(rf/subscribe [::data/ui :runs :filter :all])

        me
        @(rf/subscribe [::session/me])

        team-id
        (team-context/selected)]
    [layout/page-stack
     [layout/page-header {:title "Runs"
                          :el-id els/page
                          :description (description me team-id)
                          :actions [[filter-select filter-value]]}]
     [data/loaded {:key [:runs team-id] :query (runs-table/query team-id) :interval 5000 :error-el-id els/error}
      (fn [runs]
        [:<>
         [runs-table/runs-table {:runs (filter #(events/matches? filter-value %) runs)
                                 :empty (cond
                                          (empty? (:teams me)) "Runs appear here once you're in a team. Ask your tenant admin to add you to one."
                                          (not (and (= :all filter-value) (nil? team-id))) "No runs match this filter."
                                          :else "Runs appear here once you run a workflow.")}]
         [runs-table/cut-note runs]])]]))
