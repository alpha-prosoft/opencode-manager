(ns manager-web.home.views
  (:require ["@mui/icons-material/Close" :default CloseIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Tooltip" :default Tooltip]
            [manager-web.components.layout :as layout]
            [manager-web.components.runs-table :as runs-table]
            [manager-web.components.sessions-table :as sessions-table]
            [manager-web.components.workflows :as workflows]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.home.elements :as els]
            [manager-web.home.events :as events]
            [manager-web.home.subs :as subs]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(def steps
  [["Pick a workflow" "Your team defines workflows in its repository: a code review, an implementation, an analysis, or a workspace."]
   ["The agent runs in a sandbox" "Each run gets its own locked-down OpenCode container with only the model, tools and network the team allows."]
   ["Get a result" "Review findings, a patch with test results, or a document. You can open the session to see exactly what the agent did."]
   ["Everything is recorded" "Every LLM call is metered and every message and tool call is kept in a tamper-evident audit trail."]])

(defn- how-it-works
  []
  (when-not @(rf/subscribe [::subs/intro-hidden?])
    [:> Paper {:sx (:intro theme/sx) :data-testid (el/id els/intro)}
     [:> Tooltip {:title "Hide"}
      [:> IconButton {:size "small" :aria-label "Hide introduction" :sx (:intro-close theme/sx)
                      :data-testid (el/id els/hide-intro)
                      :on-click #(rf/dispatch [::events/hide-intro])}
       [:> CloseIcon {:fontSize "small"}]]]
     [RawTypography {:variant "h2" :gutterBottom true} "How it works"]
     (into [RawBox {:component "ol" :sx (:intro-steps theme/sx)}]
           (map-indexed (fn [index [title body]]
                          ^{:key title}
                          [RawStack {:component "li" :direction "row" :spacing 1.5}
                           [RawTypography {:variant "h3" :color "primary" :sx (:step-number theme/sx)} (inc index)]
                           [RawBox {}
                            [RawTypography {:variant "subtitle2"} title]
                            [RawTypography {:variant "body2" :color "text.secondary"} body]]])
                        steps))]))

(defn- start-section
  [catalog]
  [layout/section {:title "Start something"
                   :el-id els/start-section
                   :description "Workflows from all your teams. Workflows with problems are hidden; see the team page for details."}
   [layout/loadable @(rf/subscribe [::subs/workflows]) els/workflows-error
    (fn [items]
      [workflows/workflow-list {:workflows items
                                :catalog (or catalog [])
                                :show-team? true
                                :show-invalid? false}])]])

(defn main-panel
  []
  (let [me
        @(rf/subscribe [::session/me])

        catalog
        @(rf/subscribe [::data/data :catalog])

        teams
        @(rf/subscribe [::data/data :teams])

        sessions
        @(rf/subscribe [::data/data :sessions])

        action-error
        @(rf/subscribe [::data/error ::session-action])

        live
        (filter #(format/session-live? (:status %)) sessions)

        no-teams?
        (and (some? teams) (empty? teams))]
    [layout/page-stack
     [data/poll {:key :catalog :query {:query-id :list-activities}}]
     [data/poll {:key :teams :query {:query-id :list-teams} :then [::events/teams-loaded]}]
     [data/poll {:key :sessions :query {:query-id :list-sessions} :interval 10000}]
     [layout/page-header {:title (str "Hi " (session/display-name me))
                          :title-el-id els/title
                          :el-id els/page
                          :description "Run your team's agents in sandboxes, then review what they did."}]
     [how-it-works]
     (when no-teams?
       [RawAlert {:severity "info" :el-id els/no-teams}
        "You're not in a team yet, so there's nothing to run. A platform admin can add you to a team in the onboarding repository. Meanwhile you can open sessions others shared with everyone."])
     [layout/error-alert action-error els/error]
     (when-not no-teams?
       [start-section catalog])
     (when (seq live)
       [layout/section {:title "Running now"
                        :el-id els/running-section
                        :description "Sandboxes that are up. Idle ones stop on their own and start again when opened."}
        [sessions-table/sessions-table {:sessions live
                                        :actions {:error-id ::session-action :refetch [:sessions]}}]])
     [layout/section {:title "Recent runs"
                      :el-id els/recent-runs
                      :action [:> Button (merge (layout/nav-props :runs)
                                                {:size "small" :data-testid (el/id els/all-runs)})
                               "All runs"]}
      [data/loaded {:key :runs :query {:query-id :list-runs} :interval 10000 :error-el-id els/runs-error}
       #(vector runs-table/runs-table {:runs (take 5 %)})]]]))
