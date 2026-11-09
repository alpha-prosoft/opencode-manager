(ns manager-web.components.team-context
  "The team the person works in, shown in the sidebar and kept in the URL
   (?team=ops) so links, reloads and the back button keep it. Home, Runs,
   Sessions and Harnesses show only that team; nil means every team. On a
   team's page, or one of its projects', the context is that team."
  (:require ["@mui/icons-material/GroupsOutlined" :default GroupsIcon]
            ["@mui/material/Divider" :default Divider]
            ["@mui/material/ListItemText" :default ListItemText]
            ["@mui/material/MenuItem" :default MenuItem]
            ["@mui/material/TextField" :default TextField]
            [edd.subs :as edd-subs]
            [manager-web.components.elements :as els]
            [manager-web.el :as el]
            [manager-web.roles :as roles]
            [manager-web.routes :as routes]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawBox RawTypography]]))

(def all-teams
  "all")

(def manage-teams
  "manage")

(def scoped-pages
  "Pages that show one team when a team is chosen."
  #{:home :runs :sessions :harnesses :projects :webhooks})

(defn in-team?
  "True when item (a run, session or workflow item with :team-id) belongs to
   team-id, or team-id is nil."
  [team-id item]
  (or (nil? team-id) (= team-id (:team-id item))))

(defn of-team
  [team-id items]
  (filterv #(in-team? team-id %) items))

(defn current-team
  "The chosen team when the person is still in it, else nil."
  [chosen teams]
  (when (some #(= chosen (:id %)) teams)
    chosen))

(def team-pages
  "Pages of one team, whose path names it."
  #{:team :project})

(defn context-team
  "The team of the context: the team of a team's or project's page, else
   the URL's."
  [panel url-team page-team teams]
  (current-team (if (contains? team-pages panel) (some-> page-team str) url-team) teams))

(defn query
  "The URL query that keeps team-id as the context."
  [team-id]
  (if team-id {:team team-id} {}))

(defn target
  "Where choosing team-id (nil: every team) leads from panel, as navigate
   arguments: the same page when it shows one team, the team's page from
   the teams pages, else Home."
  [panel team-id]
  (cond
    (contains? #{:team :teams :project} panel) (if team-id [:team {:team-id team-id}] [:teams])
    (contains? scoped-pages panel) [panel {} (query team-id)]
    :else [:home {} (query team-id)]))

(rf/reg-sub
 ::url-team
 :<- [::edd-subs/url-params]
 (fn [params _]
   (:team params)))

(rf/reg-event-fx
 ::choose
 (fn [{:keys [db]} [_ team-id]]
   {:fx [[:dispatch (into [::routes/navigate] (target (:edd.db/active-panel db) team-id))]]}))

(defn selected
  []
  (let [me
        @(rf/subscribe [::session/me])

        panel
        @(rf/subscribe [::edd-subs/active-panel])]
    (context-team panel
                  @(rf/subscribe [::url-team])
                  (:team-id @(rf/subscribe [::routes/path-params]))
                  (:teams me))))

(defn option-label
  [me {:keys [id]}]
  [(roles/team-name me id) (roles/role-in-team-label me id)])

(defn switcher
  "The team picker in the sidebar: every team of the person with their role
   in it, all of them, and a way to the team list."
  []
  (let [me
        @(rf/subscribe [::session/me])

        teams
        (:teams me)

        value
        (selected)]
    (cond
      (nil? (:tenant me)) nil

      (empty? teams)
      [RawTypography {:variant "caption" :color "text.secondary" :sx (:team-switcher-none theme/sx)
                      :el-id els/team-context-none}
       "Not in a team yet"]

      :else
      [RawBox {:sx (:team-switcher theme/sx) :el-id els/team-context-box}
       (into [:> TextField {:select true :size "small" :fullWidth true :label "Team"
                            :value (or value (if (< 1 (count teams)) all-teams (:id (first teams))))
                            :id (el/id els/team-context)
                            :slotProps {:htmlInput {:data-testid (el/id els/team-context-value)}
                                        :inputLabel {:shrink true}
                                        :input {:startAdornment (r/as-element [:> GroupsIcon {:fontSize "small" :sx (:icon-gap theme/sx)}])}
                                        :select {:renderValue (fn [chosen]
                                                                (if (= all-teams chosen)
                                                                  (str "All my teams (" (count teams) ")")
                                                                  (roles/team-name me chosen)))}}
                            :on-change #(let [chosen (.. % -target -value)]
                                          (rf/dispatch (if (= manage-teams chosen)
                                                         [::routes/navigate :teams]
                                                         [::choose (when-not (= all-teams chosen) chosen)])))}]
             (concat
              (when (< 1 (count teams))
                [^{:key all-teams}
                 [:> MenuItem {:value all-teams :data-testid (el/id (els/team-context-option all-teams))}
                  [:> ListItemText {:primary "All my teams" :sx (:team-option theme/sx)}]]])
              (for [team teams
                    :let [[team-name role] (option-label me team)]]
                ^{:key (:id team)}
                [:> MenuItem {:value (:id team) :data-testid (el/id (els/team-context-option (:id team)))
                              :data-role (some-> (roles/role-in-team me (:id team)) name)}
                 [:> ListItemText {:primary team-name :secondary role :sx (:team-option theme/sx)}]])
              [^{:key "divider"} [:> Divider]
               ^{:key manage-teams}
               [:> MenuItem {:value manage-teams :data-testid (el/id els/team-context-manage)}
                [:> ListItemText {:primary "Open the teams page" :sx (:team-option theme/sx)}]]]))])))
