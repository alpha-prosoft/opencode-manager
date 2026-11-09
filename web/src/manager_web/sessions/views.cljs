(ns manager-web.sessions.views
  (:require ["@mui/material/ToggleButton" :default ToggleButton]
            ["@mui/material/ToggleButtonGroup" :default ToggleButtonGroup]
            [clojure.string :as string]
            [manager-web.components.layout :as layout]
            [manager-web.components.sessions-table :as sessions-table]
            [manager-web.components.team-context :as team-context]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
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

(defn description
  "What the list holds for this person, admins' extra view included."
  [me team-id]
  (let [admin-of
        (filterv #(= :admin (roles/claimed-role me (:id %))) (:teams me))]
    (str "A session is one sandboxed OpenCode instance. "
         (if team-id
           (str "Showing " (roles/team-name me team-id) ": your own, the ones shared with the team, and with everyone in " (:tenant me) ".")
           (str "You see your own, the ones your teams share, and ones shared with everyone in " (:tenant me) "."))
         (cond
           (roles/tenant-admin? me) " As tenant admin you also see everyone's private sessions."
           (seq admin-of) (str " As admin of " (string/join ", " (map #(roles/team-name me (:id %)) admin-of))
                               " you also see its members' private sessions.")
           :else ""))))

(defn main-panel
  []
  (let [action-error
        @(rf/subscribe [::data/error ::session-action])

        filter-value
        @(rf/subscribe [::data/ui :sessions :filter :all])

        me
        @(rf/subscribe [::session/me])

        team-id
        (team-context/selected)]
    [layout/page-stack
     [layout/page-header {:title "Sessions"
                          :el-id els/page
                          :description (description me team-id)
                          :actions [[filter-toggle filter-value]]}]
     [layout/error-alert action-error els/action-error]
     [data/loaded {:key :sessions :query {:query-id :list-sessions} :interval 5000 :error-el-id els/error}
      (fn [sessions]
        [sessions-table/sessions-table {:sessions (filter #(events/matches? filter-value %) (team-context/of-team team-id sessions))
                                        :empty (cond
                                                 (not (and (= :all filter-value) (nil? team-id))) "No sessions match this filter."
                                                 (empty? (:teams me)) (str "Nobody has shared a session with everyone in " (:tenant me) " yet. Once you're in a team, start one from Home.")
                                                 :else nil)
                                        :actions {:error-id ::session-action :refetch [:sessions]}}])]]))
