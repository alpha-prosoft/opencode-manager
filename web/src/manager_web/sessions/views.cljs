(ns manager-web.sessions.views
  (:require ["@mui/material/ToggleButton" :default ToggleButton]
            ["@mui/material/ToggleButtonGroup" :default ToggleButtonGroup]
            [manager-web.components.layout :as layout]
            [manager-web.components.sessions-table :as sessions-table]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.sessions.elements :as els]
            [manager-web.sessions.events :as events]
            [re-frame.core :as rf]))

(defn- filter-toggle
  [value]
  [:> ToggleButtonGroup {:size "small" :exclusive true :value (name value)
                         :on-change (fn [_ next] (when next (rf/dispatch [::data/set-ui :sessions :filter (keyword next)])))}
   (for [[option label] [[:all "All"] [:mine "Mine"] [:running "Running"]]]
     ^{:key option}
     [:> ToggleButton {:value (name option) :data-testid (el/id (els/filter-option option))} label])])

(defn main-panel
  []
  (let [action-error
        @(rf/subscribe [::data/error ::session-action])

        filter-value
        @(rf/subscribe [::data/ui :sessions :filter :all])]
    [layout/page-stack
     [layout/page-header {:title "Sessions"
                          :el-id els/page
                          :description "A session is one sandboxed OpenCode instance. You see your own, your team's shared ones, and ones shared with everyone."
                          :actions [[filter-toggle filter-value]]}]
     [layout/error-alert action-error els/action-error]
     [data/loaded {:key :sessions :query {:query-id :list-sessions} :interval 5000 :error-el-id els/error}
      (fn [sessions]
        [sessions-table/sessions-table {:sessions (filter #(events/matches? filter-value %) sessions)
                                        :empty (when-not (= :all filter-value) "No sessions match this filter.")
                                        :actions {:error-id ::session-action :refetch [:sessions]}}])]]))
