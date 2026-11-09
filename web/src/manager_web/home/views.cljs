(ns manager-web.home.views
  (:require ["@mui/icons-material/Close" :default CloseIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Tooltip" :default Tooltip]
            [manager-web.components.layout :as layout]
            [manager-web.components.runs-table :as runs-table]
            [manager-web.components.sessions-table :as sessions-table]
            [manager-web.components.team-context :as team-context]
            [manager-web.components.workflows :as workflows]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.home.elements :as els]
            [manager-web.home.events :as events]
            [manager-web.home.subs :as subs]
            [manager-web.roles :as roles]
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

(defn context-text
  "Which teams Home shows."
  [me team-id]
  (cond
    (empty? (:teams me)) (str "You're in " (:tenant me) " but not in a team yet.")
    team-id (str "Showing " (roles/team-name me team-id) " (" (roles/role-in-team-label me team-id) ").")
    (= 1 (count (:teams me))) (str "Showing " (roles/team-name me (:id (first (:teams me)))) ".")
    :else (str "Showing your " (count (:teams me)) " teams.")))

(defn workflows-text
  [me team-id]
  (str (if team-id
         (str "Workflows of " (roles/team-name me team-id) ".")
         (str "Workflows from " (if (= 1 (count (:teams me))) "your team." (str "your " (count (:teams me)) " teams."))))
       " Workflows with problems are hidden;"))

(defn problems-link
  "Where the hidden workflows' problems are: the chosen team's page, the
   only team's, or the list of teams. As layout/link props."
  [me team-id]
  (let [team
        (or team-id
            (when (= 1 (count (:teams me))) (:id (first (:teams me)))))]
    (if team
      {:page :team :params {:team-id team}}
      {:page :teams})))

(defn shared-with-tenant
  "Sessions shared with everyone in the tenant."
  [sessions]
  (filterv #(= :tenant (format/kw (:visibility %))) sessions))

(defn- start-section
  [me catalog team-id]
  [layout/section {:title "Start something"
                   :el-id els/start-section
                   :description [:<>
                                 (workflows-text me team-id)
                                 " "
                                 [layout/link (assoc (problems-link me team-id) :el-id els/problems-link)
                                  (if (= :team (:page (problems-link me team-id)))
                                    "see the team's page for why."
                                    "see the teams' pages for why.")]]}
   [layout/loadable @(rf/subscribe [::subs/workflows]) els/workflows-error
    (fn [items]
      [workflows/workflow-list {:workflows (team-context/of-team team-id items)
                                :catalog (or catalog [])
                                :show-team? true
                                :show-invalid? false}])]])

(def shared-shown
  10)

(defn- shared-section
  [me sessions]
  [layout/section {:title (str "Shared with everyone in " (:tenant me))
                   :el-id els/shared-section
                   :description "The latest sessions people shared with the whole tenant. You can look at them and fork them into your own copy."
                   :action [:> Button (merge (layout/nav-props :sessions)
                                             {:size "small" :data-testid (el/id els/all-sessions)})
                            "All sessions"]}
   [sessions-table/sessions-table {:sessions (take shared-shown (shared-with-tenant sessions))
                                   :empty-title "Nothing shared yet"
                                   :empty (str "Nobody has shared a session with everyone in " (:tenant me) " yet.")
                                   :actions {:error-id ::session-action :refetch [:sessions]}}]])

(defn main-panel
  []
  (let [me
        @(rf/subscribe [::session/me])

        catalog
        @(rf/subscribe [::data/data :catalog])

        sessions
        @(rf/subscribe [::data/data :sessions])

        action-error
        @(rf/subscribe [::data/error ::session-action])

        team-id
        (team-context/selected)

        live
        (team-context/of-team team-id (filter #(format/session-live? (:status %)) sessions))

        no-teams?
        (and (empty? (:teams me)) (some? (:tenant me)))]
    [layout/page-stack
     [data/poll {:key :catalog :query {:query-id :list-activities}}]
     [data/poll {:key :teams :query {:query-id :list-teams} :then [::events/teams-loaded]}]
     [data/poll {:key :sessions :query {:query-id :list-sessions} :interval 10000}]
     [layout/page-header {:title (str "Hi " (session/display-name me))
                          :title-el-id els/title
                          :el-id els/page
                          :description [:<>
                                        "Run your team's agents in sandboxes, then review what they did. "
                                        [:span {:data-testid (el/id els/context) :data-team (or team-id "all")}
                                         (context-text me team-id)]]}]
     [how-it-works]
     (when no-teams?
       [RawAlert {:severity "info" :el-id els/no-teams}
        (str "You're in " (:tenant me) " but not in a team yet, so there's nothing to run. Your identity provider assigns teams;"
             " ask your tenant admin to add you to one. Meanwhile you can look at sessions shared with everyone in "
             (:tenant me) " and fork them.")])
     [layout/error-alert action-error els/error]
     (if no-teams?
       (when (some? sessions) [shared-section me sessions])
       [start-section me catalog team-id])
     (when (seq live)
       [layout/section {:title "Running now"
                        :el-id els/running-section
                        :description "Sandboxes that are up. Idle ones stop on their own and start again when opened."}
        [sessions-table/sessions-table {:sessions live
                                        :actions {:error-id ::session-action :refetch [:sessions]}}]])
     (when-not no-teams?
       [layout/section {:title "Recent runs"
                        :el-id els/recent-runs
                        :action [:> Button (merge (layout/nav-props :runs nil (team-context/query team-id))
                                                  {:size "small" :data-testid (el/id els/all-runs)})
                                 "All runs"]}
        [data/loaded {:key [:runs team-id] :query (runs-table/query team-id) :interval 10000 :error-el-id els/runs-error}
         #(vector runs-table/runs-table {:runs (take 5 %)})]])]))
