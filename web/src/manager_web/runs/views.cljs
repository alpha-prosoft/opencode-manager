(ns manager-web.runs.views
  (:require ["@mui/material/MenuItem" :default MenuItem]
            ["@mui/material/TextField" :default TextField]
            [manager-web.components.chips :as chips]
            [manager-web.components.layout :as layout]
            [manager-web.components.runs-table :as runs-table]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.runs.elements :as els]
            [manager-web.runs.events :as events]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]))

(def filter-options
  (concat [[:all "All runs"] [:active "In progress"]]
          (for [status [:completed :failed :cancelled]]
            [status (chips/status-label :run status)])))

(defn- filter-select
  [value]
  (into [:> TextField {:select true :size "small" :label "Show" :value (name value) :sx (:filter-select theme/sx)
                       :id (el/id els/filter-select)
                       :slotProps {:htmlInput {:data-testid (el/id els/filter-value)}}
                       :on-change #(rf/dispatch [::data/set-ui :runs :filter (keyword (.. % -target -value))])}]
        (for [[option label] filter-options]
          ^{:key option}
          [:> MenuItem {:value (name option) :data-testid (el/id (els/filter-option option))} label])))

(defn main-panel
  []
  (let [filter-value
        @(rf/subscribe [::data/ui :runs :filter :all])]
    [layout/page-stack
     [layout/page-header {:title "Runs"
                          :el-id els/page
                          :description "Every workflow run in your teams: started by hand, on a schedule, or by a new pull request."
                          :actions [[filter-select filter-value]]}]
     [data/loaded {:key :runs :query {:query-id :list-runs} :interval 5000 :error-el-id els/error}
      (fn [runs]
        [runs-table/runs-table {:runs (filter #(events/matches? filter-value %) runs)
                                :empty (when-not (= :all filter-value) "No runs match this filter.")}])]]))
