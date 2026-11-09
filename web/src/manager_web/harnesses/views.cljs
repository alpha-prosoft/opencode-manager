(ns manager-web.harnesses.views
  (:require ["@mui/icons-material/CheckCircle" :default CheckCircleIcon]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Typography" :default Typography]
            [clojure.string :as string]
            [manager-web.components.chips :as chips]
            [manager-web.components.credential-scope :as scope]
            [manager-web.components.layout :as layout]
            [manager-web.components.team-context :as team-context]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.harnesses.elements :as els]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawBox RawStack RawTypography]]))

(defn credential-text
  [{:keys [name source]}]
  (case (format/kw source)
    :user (str name ": your account")
    :team (str name ": team account")
    :tenant (str name ": shared account")
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
       [RawBox {:sx (:spacer theme/sx)}]
       (if ready
         [:> Chip {:color "success" :icon (r/as-element [:> CheckCircleIcon]) :label (str "Ready for you in " team-name)
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
      [scope/missing-alert {:names (map :name missing)
                            :purpose "these tools"
                            :add-el-id (els/add-credential team-id id)}]
      [layout/definition-errors {:errors errors}]
      (when (seq workflows)
        [RawTypography {:variant "caption" :color "text.secondary"}
         (str "Used by: " (string/join ", " (map :name workflows)))])]]))

(defn by-team
  "Harnesses grouped by team, in the order of the person's teams."
  [harnesses]
  (let [groups
        (group-by :team-id harnesses)]
    (for [team-id (distinct (map :team-id harnesses))]
      [team-id (get groups team-id)])))

(defn- harness-groups
  [me team-id harnesses]
  (let [shown
        (team-context/of-team team-id harnesses)]
    (cond
      (empty? (:teams me))
      [layout/empty-state {:title "No harnesses" :el-id els/empty-state}
       (str "You're in " (:tenant me) " but not in a team yet. Harnesses belong to teams; ask your tenant admin to add you to one.")]

      (empty? shown)
      [layout/empty-state {:title "No harnesses" :el-id els/empty-state}
       (if team-id
         (str (roles/team-name me team-id) " hasn't defined any harnesses yet.")
         "Your teams haven't defined any harnesses yet.")]

      :else
      (into [RawStack {:spacing 3 :el-id els/grid}]
            (for [[group-team-id items] (by-team shown)]
              ^{:key group-team-id}
              [RawBox {:data-testid (el/id (els/team-group group-team-id))}
               [chips/team-heading group-team-id (roles/team-name me group-team-id)
                {:link-el-id (els/team-link group-team-id)}]
               (into [RawBox {:sx (:harness-grid theme/sx)}]
                     (for [harness items]
                       ^{:key (str (:team-id harness) "/" (:id harness))}
                       [harness-card harness]))])))))

(defn main-panel
  []
  (let [me
        @(rf/subscribe [::session/me])

        team-id
        (team-context/selected)]
    [layout/page-stack
     [layout/page-header {:title "Harnesses"
                          :el-id els/page
                          :description (str "The kinds of agent sandboxes "
                                            (if team-id (roles/team-name me team-id) "your teams")
                                            " offer: which model, tools, skills and network each one gets. Tools run behind the platform gateway, using your own credentials where you've added them.")}]
     [data/loaded {:key :harnesses :query {:query-id :list-harnesses} :error-el-id els/error}
      #(vector harness-groups me team-id %)]]))
