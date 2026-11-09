(ns manager-web.team.views
  (:require ["@mui/icons-material/GroupsOutlined" :default GroupsIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/LinearProgress" :default LinearProgress]
            ["@mui/material/Menu" :default Menu]
            ["@mui/material/MenuItem" :default MenuItem]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Typography" :default Typography]
            [clojure.string :as string]
            [edd.subs :as edd-subs]
            [manager-web.components.chips :as chips]
            [manager-web.components.credential-form :as credential-form]
            [manager-web.components.layout :as layout]
            [manager-web.components.project-card :as project-card]
            [manager-web.components.required-credentials :as required-credentials]
            [manager-web.components.runs-table :as runs-table]
            [manager-web.components.sessions-table :as sessions-table]
            [manager-web.components.team-context :as team-context]
            [manager-web.components.usage :as usage]
            [manager-web.components.workflows :as workflows]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.session :as session]
            [manager-web.team.elements :as els]
            [manager-web.team.events :as events]
            [manager-web.theme :as theme]
            [manager-web.webhooks.views :as webhooks]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(def permissions
  {"allow" "allowed"
   "ask" "asks first"
   "deny" "blocked"})

(defn admin-role?
  [team]
  (= :admin (format/kw (:role team))))

(defn tools-text
  [permission-map]
  (if (seq permission-map)
    (string/join ", " (for [[tool value] permission-map
                            :let [value (some-> value name)]]
                        (str (name tool) " " (get permissions value value))))
    "OpenCode defaults"))

(defn- term
  [label & children]
  [:<>
   [RawTypography {:component "dt" :variant "label"} label]
   (into [RawBox {:component "dd" :sx (:definition-value theme/sx)}] children)])

(defn- harness-card
  [{:keys [id spec] :as harness}]
  (let [policy
        (concat (get-in spec [:policy :deny-commands]) (get-in spec [:policy :deny-paths]))]
    [:> Paper {:sx (:panel theme/sx) :data-testid (el/id (els/harness-card id))}
     [RawStack {:spacing 1.25}
      [layout/row
       [RawTypography {:variant "h3"} id]
       (when spec
         [chips/network-chip (:network spec)])]
      (when-not (string/blank? (:description spec))
        [RawTypography {:variant "body2" :color "text.secondary"} (:description spec)])
      [layout/definition-errors harness]
      (when spec
        [RawBox {:component "dl" :sx (:definitions theme/sx)}
         [term "Models" (string/join ", then " (get-in spec [:llm :models]))]
         [term "Tools" (tools-text (:permissions spec))]
         [term "Resources" (str (get-in spec [:resources :cpus]) " CPU, " (get-in spec [:resources :memory-mb])
                                " MB memory, stops after " (:idle-minutes spec) " idle min")]
         (when (seq policy)
           [term "Always blocked"
            (into [layout/row]
                  (for [rule policy]
                    ^{:key rule}
                    [:> Chip {:color "warning" :label (r/as-element [layout/mono rule])}]))])])]]))

(defn- agent-card
  [{:keys [id spec source] :as agent}]
  [:> Paper {:sx (:panel theme/sx) :data-testid (el/id (els/agent-card id))}
   [RawStack {:spacing 0.75}
    [RawTypography {:variant "h3"} id]
    (when-not (string/blank? (:description spec))
      [RawTypography {:variant "body2" :color "text.secondary"} (:description spec)])
    [:> Typography {:variant "caption" :color "text.secondary"}
     "Instructions in " [layout/mono (or (:prompt-file spec) source)]]
    [layout/definition-errors agent]]])

(defn- of-kind
  [team kind]
  (filter #(= kind (format/kw (:kind %))) (:definitions team)))

(defn- definition-cards
  [kind definitions card empty-el-id]
  (if (empty? definitions)
    [layout/empty-state {:title (str "No " kind) :el-id empty-el-id}
     (str "The team adds " kind " as JSON files in its repository.")]
    (into [RawBox {:sx (:definition-grid theme/sx)}]
          (for [definition definitions]
            ^{:key (:id definition)}
            [card definition]))))

(defn- setup
  [team]
  [RawStack {:spacing 4}
   [layout/section {:title "Agents" :description "Who does the work: the instructions given to the model."}
    [definition-cards "agents" (of-kind team :agents) agent-card els/agents-empty]]
   [layout/section {:title "Harnesses"
                    :description "Where the work happens: model, tools, network and limits of the sandbox. The agent cannot change these."}
    [definition-cards "harnesses" (of-kind team :harnesses) harness-card els/harnesses-empty]]])

(defn- budget-bar
  [budget]
  (let [used
        (events/budget-percent budget)]
    (when (:daily-tokens budget)
      [:> Paper {:sx (:panel theme/sx) :data-testid (el/id els/budget)}
       [:> LinearProgress {:variant "determinate" :value used :sx (:budget-bar theme/sx)
                           :color (cond (>= used 100) "error" (> used 80) "warning" :else "primary")}]
       [RawTypography {:variant "body2" :color "text.secondary" :sx (:spaced-top theme/sx)}
        (str (format/tokens (:used-today budget)) " of " (format/tokens (:daily-tokens budget))
             " tokens used today. When the team reaches it, agents can't call the LLM until midnight UTC.")]])))

(defn- limits
  [team running]
  [layout/section {:title "Budget and limits" :el-id els/limits
                   :description "Set in team.json; the platform enforces them for every session and run of the team."}
   [layout/stat-grid
    (for [[id label value hint] (events/limit-items team running)]
      ^{:key id}
      [layout/stat {:label label :value value :hint hint :el-id (els/limit id)}])]
   [budget-bar (:budget team)]])

(defn- connection-models
  [{:keys [models]}]
  (if (seq models) (string/join ", " models) "Every model of the connection"))

(defn- connections
  [team]
  (let [connections
        (:llm-connections team)]
    [layout/section {:title "Models" :el-id els/connections
                     :description "LLM connections and models the team's harnesses may use, within what the tenant grants."}
     (if (seq connections)
       [layout/table {}
        (layout/head "Connection" "Models")
        (for [connection connections]
          ^{:key (str (:id connection))}
          [layout/table-row {}
           [layout/cell {:sx (:strong theme/sx)} (:id connection)]
           [layout/cell (connection-models connection)]])]
       [RawTypography {:variant "body2" :color "text.secondary"} "Every connection and model the tenant grants."])]))

(defn- server-id
  [server]
  (if (map? server) (:id server) server))

(defn- mcp-servers
  [team]
  (let [servers
        (:mcp-servers team)]
    [layout/section {:title "MCP servers" :el-id els/mcp-servers
                     :description "Tools the team's sandboxes reach through the platform gateway."}
     (if (seq servers)
       (into [layout/row]
             (for [server servers]
               ^{:key (str (server-id server))}
               [:> Chip {:label (r/as-element [layout/mono (server-id server)])}]))
       [RawTypography {:variant "body2" :color "text.secondary"} "Every MCP server the tenant grants the team."])]))

(defn- ci-text
  [{:keys [mcp-server jobs]}]
  (if (seq jobs)
    (str (string/join ", " jobs) (when mcp-server (str " via " (name mcp-server))))
    "—"))

(defn- projects
  [team]
  (let [items
        (:projects team)]
    [layout/section {:title "Projects" :el-id els/projects
                     :description "Repositories the team works on and the builds that check them."}
     (if (seq items)
       [layout/table {}
        (layout/head "Project" "Repository" "Builds")
        (for [{:keys [id name scm ci]} items]
          ^{:key id}
          [layout/table-row {:data-testid (el/id (els/project id))}
           [layout/cell {:sx (:strong theme/sx)} (or name id)]
           [layout/cell (when scm
                          [:<> (some-> (:provider scm) clojure.core/name) " " [layout/mono (:repository scm)]])]
           [layout/cell (ci-text ci)]])]
       [RawTypography {:variant "body2" :color "text.secondary"} "No projects in team.json."])]))

(defn- usage-tab
  [{:keys [usage usage-by-model]}]
  [layout/section {:title "LLM usage" :description "Last 7 days, measured by the platform's gateway."}
   [usage/usage-stats usage]
   (when (seq usage-by-model)
     (let [right
           {:align "right"}]
       [layout/table {:el-id els/usage-by-model}
        (layout/head "Model" ["Calls" right] ["Prompt tokens" right] ["Completion tokens" right])
        (for [row usage-by-model]
          ^{:key (:model row)}
          [layout/table-row {}
           [layout/cell (:model row)]
           [layout/cell right (:calls row)]
           [layout/cell right (format/tokens (:prompt-tokens row))]
           [layout/cell right (format/tokens (:completion-tokens row))]])]))])

(defn- team-value
  [{:keys [id value tenant-set]}]
  (let [[state color label]
        (cond
          value [:team "success" (str "Team value · " (:hint value))]
          tenant-set [:tenant "default" "Uses the tenant value"]
          :else [:missing "warning" "Not set"])]
    [:> Chip {:color color :label label
              :data-testid (el/id (els/credential-state id)) :data-state (name state)}]))

(defn- member-value
  [{:keys [id] :as credential}]
  (let [[state label]
        (events/member-credential-state credential)]
    [:> Chip {:color (if (= :team state) "info" "default") :label label
              :data-testid (el/id (els/credential-state id)) :data-state (name state)}]))

(defn- credential-row
  "A team credential: admins see its hint and set it, members only whether
   the team has one."
  [team-id admin? {:keys [id name description value provided-by] :as credential}]
  [layout/list-row {:el-id (els/credential id)}
   [RawBox {:sx (:grow theme/sx)}
    [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
     [RawTypography {:variant "h3"} name]
     (if admin? [team-value credential] [member-value credential])]
    [RawTypography {:variant "body2" :color "text.secondary"} description]
    (if admin?
      [RawTypography {:variant "caption" :color "text.secondary" :component "div"}
       (str "Provided by: " (format/provided-by-label provided-by)
            (when value (str " · saved " (format/relative-time (:updated-at value)) " by " (:updated-by value))))]
      [RawTypography {:variant "body2"} (events/member-credential-note credential)])]
   (when admin?
     [credential-form/actions {:scope [:team team-id]
                               :credential credential
                               :has-value? (some? value)
                               :set-label "Set"
                               :set-el-id (els/set-credential id)
                               :clear (when value
                                        {:label "Clear" :el-id (els/clear-credential id)
                                         :error ::events/credentials-action
                                         :refetch [[:team-credentials team-id] :my-credentials]})}])])

(defn- credential-list
  [team-id admin? credentials]
  (if (empty? credentials)
    [layout/empty-state {:title "No team credentials" :el-id els/credentials-empty}
     "No credential in tenant.json lets teams provide their own value."]
    (into [:> Paper {:data-testid (el/id els/credentials)}]
          (for [credential credentials]
            ^{:key (:id credential)}
            [credential-row team-id admin? credential]))))

(defn- own-values-link
  [el-id]
  [:> Button (merge (layout/nav-props :accounts) {:size "small" :data-testid (el/id el-id)})
   "Your own values"])

(defn- team-credentials
  [team-id admin?]
  [layout/section {:title "Team credentials"
                   :description (if admin?
                                  "Values this team uses instead of the tenant's. Stored encrypted and never shown again; members only see whether one is set."
                                  "Which credentials the team has set. Team admins set the team's values; you never see them.")
                   :action [own-values-link els/own-value-link]}
   [layout/error-alert @(rf/subscribe [::data/error ::events/credentials-action]) els/credentials-action-error]
   [data/loaded {:key [:team-credentials team-id]
                 :query {:query-id :list-team-credentials :team-id team-id}
                 :error-el-id els/credentials-error}
    #(vector credential-list team-id admin? %)]
   (when admin?
     [credential-form/credential-dialog {:scope [:team team-id]
                                         :refetch [[:team-credentials team-id]]}])])

(defn- required-credential-row
  [me team my-credentials {:keys [id description required-by] :as credential}]
  (let [own-state
        (required-credentials/own-state my-credentials (:id team) id)

        [color label]
        (required-credentials/own-state-chips own-state)]
    [layout/list-row {:el-id (els/required-credential id)}
     [RawBox {:sx (:grow theme/sx)}
      [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
       [RawTypography {:variant "h3"} (:name credential)]
       (for [[source label] (required-credentials/source-labels me (:name team) required-by)]
         ^{:key source}
         [:> Chip {:variant "outlined" :size "small" :label label
                   :data-testid (el/id (els/required-source id source))}])
       (when own-state
         [:> Chip {:size "small"
                   :color color
                   :label label
                   :data-testid (el/id (els/required-own-state id)) :data-state (name own-state)}])]
      [RawTypography {:variant "body2" :color "text.secondary"} description]]
     (when (= :missing own-state)
       [:> Button (merge (layout/nav-props :accounts)
                         {:variant "contained" :size "small" :data-testid (el/id (els/required-add id))})
        "Add yours"])]))

(defn- required-credential-list
  [me team required]
  (let [my-credentials
        @(rf/subscribe [::data/data :my-credentials])]
    (if (empty? required)
      [layout/empty-state {:title "Nothing required" :el-id els/required-credentials-empty}
       "Neither tenant.json nor this team's team.json requires people to add their own credentials."]
      (into [:> Paper {:data-testid (el/id els/required-credentials)}]
            (for [credential required]
              ^{:key (:id credential)}
              [required-credential-row me team my-credentials credential])))))

(defn- required-credentials
  [team]
  (let [me
        @(rf/subscribe [::session/me])]
    [layout/section {:title "Required of every member"
                     :description (str "Each person working in " (:name team)
                                       " must add their own value under Accounts before they start work here; a team or tenant value doesn't count. "
                                       "Tenant admins require them in tenant.json, team admins in the team's .platform/team.json (required_credentials).")}
     [data/poll {:key :my-credentials :query required-credentials/query}]
     [data/loaded {:key [:required-credentials (:id team)]
                   :query {:query-id :list-required-credentials :team-id (:id team)}
                   :error-el-id els/credentials-error}
      #(vector required-credential-list me team %)]]))

(defn- settings
  [team]
  (let [sessions
        @(rf/subscribe [::data/data :sessions])]
    [RawStack {:spacing 4 :el-id els/configuration}
     [data/poll {:key :sessions :query {:query-id :list-sessions} :interval 10000}]
     [limits team (events/running-count (:id team) sessions)]
     [layout/two-columns
      [connections team]
      [mcp-servers team]]
     [projects team]
     [required-credentials team]
     [team-credentials (:id team) (admin-role? team)]]))

(defn- team-session-menu
  [team catalog]
  (r/with-let [anchor
               (r/atom nil)]
    (let [choices
          (events/interactive-workflows team catalog)

          close
          #(reset! anchor nil)]
      (when (seq choices)
        [:<>
         [:> Button {:variant "contained" :startIcon (r/as-element [:> GroupsIcon])
                     :data-testid (el/id els/start-team-session)
                     :on-click #(reset! anchor (.-currentTarget %))}
          "Start team session"]
         [:> Menu {:anchorEl @anchor :open (some? @anchor) :on-close close
                   :data-testid (el/id els/team-session-menu)}
          (for [{:keys [workflow activity] :as item} choices]
            ^{:key (:id workflow)}
            [:> MenuItem {:data-testid (el/id (els/team-session-workflow (:id workflow)))
                          :on-click (fn []
                                      (close)
                                      (rf/dispatch [::workflows/open (assoc item :activity activity :team-session? true)]))}
             (or (get-in workflow [:spec :name]) (:id workflow))])]]))))

(defn- team-sessions
  [team catalog]
  (let [action-error
        @(rf/subscribe [::data/error ::session-action])]
    [layout/section {:title "Team sessions"
                     :description "Sessions of this team you can see. Team sessions belong to the team and work with its credentials: members work in them, others may fork them."
                     :action (when (admin-role? team) [team-session-menu team catalog])}
     [layout/error-alert action-error els/sessions-action-error]
     [data/loaded {:key :sessions :query {:query-id :list-sessions} :interval 10000 :error-el-id els/sessions-error}
      (fn [sessions]
        [sessions-table/sessions-table {:sessions (team-context/of-team (:id team) sessions)
                                        :show-team? false
                                        :empty "Nobody shared a session of this team with you yet."
                                        :actions {:error-id ::session-action :refetch [:sessions]}}])]
     [workflows/run-dialog]]))

(defn- team-runs
  [team-id]
  [data/loaded {:key [:runs team-id]
                :query (runs-table/query team-id)
                :interval 10000
                :error-el-id els/runs-error}
   (fn [runs]
     [:<>
      [runs-table/runs-table {:runs runs :show-team? false :empty "Nobody has run this team's workflows yet."}]
      [runs-table/cut-note runs]])])

(defn- project-cards
  [team]
  (when (seq (:projects team))
    [layout/section {:title "Projects" :el-id els/project-cards
                     :description "Repositories the team works on. Open one for its workflows, runs, sessions, changes and builds."}
     [project-card/project-grid {:card-el-id els/project-card :grid-el-id els/project-grid} (:projects team)]]))

(defn- team-view
  [team]
  (let [tab
        (data/tab-of events/tabs (:tab @(rf/subscribe [::edd-subs/url-params])))

        me
        @(rf/subscribe [::session/me])

        catalog
        @(rf/subscribe [::data/data :catalog])]
    [layout/page-stack
     [data/poll {:key :catalog :query {:query-id :list-activities}}]
     [layout/page-header {:crumbs [layout/entity-crumbs me {:label (:name team)
                                                            :els {:crumbs els/crumbs :tenant els/crumb-tenant}}]
                          :title (:name team)
                          :title-el-id els/title
                          :el-id els/page
                          :description (:description team)
                          :actions [[layout/scope-chip [:team (:name team)] els/scope]
                                    [chips/role-chip (:id team) (el/id els/role)]]
                          :meta [[:> Typography {:variant "caption"}
                                  [layout/mono (:id team)]
                                  (str " · " (format/source-label team) " · version ")
                                  [layout/mono (format/short-commit (:commit team))]
                                  (str " · synced " (format/relative-time (:synced-at team)))]]}]
     (when (:error-message team)
       [RawAlert {:severity "error" :el-id els/sync-error} (str "Last sync failed: " (:error-message team))])
     [project-cards team]
     [layout/tabs {:value tab
                   :tabs events/tabs
                   :on-change #(layout/navigate! :team {:team-id (:id team)} {:tab (name %)})
                   :tab-el-id els/tab}]
     (case tab
       :workflows [workflows/workflow-list {:workflows (workflows/team-workflows team) :catalog (or catalog [])}]
       :runs [team-runs (:id team)]
       :sessions [team-sessions team (or catalog [])]
       :setup [setup team]
       :usage [usage-tab team]
       :webhooks [webhooks/webhooks-section {:team-id (:id team)}]
       :settings [settings team]
       nil)]))

(defn main-panel
  []
  (when-let [team-id
             @(rf/subscribe [::data/ui :team :team-id])]
    [data/loaded {:key [:team team-id]
                  :query {:query-id :get-team :team-id team-id}
                  :error-el-id els/error
                  :failed [layout/way-back {:page :teams :label "All teams" :el-id els/back-to-list}]}
     team-view]))
