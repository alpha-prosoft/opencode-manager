(ns manager-web.admin.views
  (:require ["@mui/icons-material/Sync" :default SyncIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Typography" :default Typography]
            [clojure.string :as string]
            [edd.subs :as edd-subs]
            [manager-web.admin.elements :as els]
            [manager-web.admin.events :as events]
            [manager-web.components.chips :as chips]
            [manager-web.components.credential-form :as credential-form]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [manager-web.tenants.events :as tenant-events]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(def ^:private right
  {:align "right"})

(defn- team-label
  [team-id]
  (if (string/blank? team-id)
    "—"
    (roles/team-name @(rf/subscribe [::session/me]) team-id)))

(defn- ui
  [key default]
  @(rf/subscribe [::data/ui :admin key default]))

(defn runs-today-hint
  [runs-today]
  (let [parts
        (for [[status count] runs-today]
          (str count " " (string/lower-case (chips/status-label :run status))))]
    (if (seq parts) (string/join ", " parts) "none yet")))

(defn- sync-outcome
  "How the sync asked for here went, once the tenant's status shows it."
  [status]
  (let [asked
        (ui :sync nil)

        outcome
        (events/sync-outcome asked status @(rf/subscribe [::data/data :teams]))

        pending?
        (= :pending (:state outcome))]
    (when outcome
      [:<>
       [data/poll {:key :teams :query {:query-id :list-teams} :interval (when pending? 3000)}]
       (when pending?
         [data/poll {:key :admin-overview :query {:query-id :get-admin-overview} :interval 3000}])
       [RawAlert {:severity (case (:state outcome) :pending "info" :synced "success" "error")
                  :el-id els/sync-outcome :data-state (name (:state outcome))}
        [:span
         (events/sync-outcome-text outcome)
         (when-not pending?
           [:<> " "
            [layout/link {:page :admin :query {:tab "teams"} :el-id els/sync-outcome-teams} "See each team"]])]]])))

(defn- overview-panel
  [{:keys [status teams users sessions runs-today tokens-today cost-today]}]
  (let [tenant
        (:tenant @(rf/subscribe [::session/me]))

        report
        (ui :report nil)

        busy?
        @(rf/subscribe [::data/busy? ::events/maintenance])

        running
        (ui :running nil)]
    [RawStack {:spacing 3}
     [layout/row
      [:> Button {:variant "contained" :startIcon (r/as-element [:> SyncIcon])
                  :loading (and busy? (= :sync running)) :disabled busy?
                  :data-testid (el/id els/sync)
                  :on-click #(rf/dispatch [::events/maintenance :sync])}
       (str "Sync " (format/possessive tenant) " repositories")]
      [:> Button {:variant "outlined" :loading (and busy? (= :idle running)) :disabled busy?
                  :data-testid (el/id els/stop-idle)
                  :on-click #(rf/dispatch [::events/maintenance :idle])}
       (str "Stop idle sessions in " tenant)]]
     (when report
       [:div {:data-testid (el/id els/report) :data-ok (str (boolean (:ok? report))) :data-action (name (:action report))}
        [RawAlert {:severity (if (:ok? report) "success" "error")} (:text report)]])
     (when (and (:ok? report) (= :sync (:action report)))
       [sync-outcome status])
     (when (:error-message status)
       [RawAlert {:severity "error" :el-id els/sync-error}
        (str "The last onboarding sync failed: " (:error-message status))])
     [RawBox {:el-id els/overview-stats}
      [layout/stat-grid
       [layout/stat {:label "Teams" :value teams :hint (str users " people")}]
       [layout/stat {:label "Running sessions" :value (:running sessions) :hint (str (:total sessions) " in total")}]
       [layout/stat {:label "Runs today" :value (reduce + 0 (vals runs-today)) :hint (runs-today-hint runs-today)}]
       [layout/stat {:label "Tokens today" :value (format/tokens tokens-today)}]
       [layout/stat {:label "Estimated cost today" :value (format/money cost-today)
                     :hint "from model prices in tenant.json"}]]]
     [layout/row
      [chips/sync-chip (tenant-events/sync-state status) els/sync-status]
      [:> Typography {:variant "body2" :color "text.secondary"}
       "Onboarding repository version " [layout/mono (format/short-commit (:commit status))]
       (str ", synced " (format/relative-time (:synced-at status)) ".")]]]))

(defn- overview
  []
  [data/loaded {:key :admin-overview :query {:query-id :get-admin-overview} :interval 15000
                :error-el-id (els/panel-error :overview)}
   overview-panel])

(defn- users-table
  [users]
  (if (empty? users)
    [layout/empty-state {:title "Nobody yet" :el-id els/users-empty}
     (str "Nobody in " (:tenant @(rf/subscribe [::session/me])) " ran a workflow or used a model in the last 30 days.")]
    [layout/table {:el-id els/users-table}
     (layout/head "Person" ["Runs, 30 days" right] ["Tokens, 30 days" right])
     (for [user users]
       ^{:key (:username user)}
       [layout/table-row {}
        [layout/cell {:sx (:strong theme/sx)} (:username user)]
        [layout/cell right (:runs-30d user)]
        [layout/cell right (format/tokens (:tokens-30d user))]])]))

(defn- users
  []
  [layout/section {:title "People"
                   :description "People of this tenant who ran a workflow or called a model in the last 30 days. Teams and roles come from your identity provider's groups."}
   [data/loaded {:key :admin-users :query {:query-id :list-admin-users} :error-el-id (els/panel-error :people)}
    users-table]])

(defn- days-label
  [days]
  (if (= 1 days) "Last 24 hours" (str "Last " days " days")))

(defn- usage-table
  [rows]
  [layout/table {:el-id els/usage-table}
   (layout/head "Team" "Model" ["Calls" right] ["Prompt" right] ["From cache" right]
                ["Completion" right] ["Failed" right] ["Cost" right])
   (for [row rows]
     ^{:key (str (:team-id row) "-" (:connection row) "-" (:model row))}
     [layout/table-row {}
      [layout/cell (team-label (:team-id row))]
      [layout/cell (:model row)]
      [layout/cell right (:calls row)]
      [layout/cell right (format/tokens (:prompt-tokens row))]
      [layout/cell right (format/tokens (:cached-tokens row))]
      [layout/cell right (format/tokens (:completion-tokens row))]
      [layout/cell right (:errors row)]
      [layout/cell right (format/money (:cost row))]])])

(defn- team-usage-table
  [totals]
  [layout/table {:el-id els/team-usage-table}
   (layout/head "Team" ["Calls" right] ["Tokens" right] ["Failed" right] ["Cost" right])
   (for [{:keys [team-id calls tokens errors cost]} totals]
     ^{:key (str team-id)}
     [layout/table-row {:data-testid (el/id (els/team-usage team-id))}
      [layout/cell {:sx (:strong theme/sx)} (team-label team-id)]
      [layout/cell right calls]
      [layout/cell right (format/tokens tokens)]
      [layout/cell right errors]
      [layout/cell right (format/money cost)]])])

(defn- usage-description
  [rows]
  (if (some #(some? (:cost %)) rows)
    (str "Estimated cost: " (format/money (reduce + 0 (keep :cost rows)))
         ". Models without a price in tenant.json show no cost.")
    "Add model prices to llm_connections in tenant.json to see cost estimates."))

(defn- usage
  []
  (let [days
        (ui :days 7)

        rows
        @(rf/subscribe [::data/data [:admin-usage days]])]
    [layout/section {:title "Usage and cost"
                     :description (usage-description rows)
                     :action [layout/select {:id (el/id els/usage-days)
                                             :value days
                                             :options (for [option [1 7 30 90]] [option (days-label option)])
                                             :option-el-id els/usage-days-option
                                             :on-change #(rf/dispatch [::data/set-ui :admin :days (js/Number %)])}]}
     [data/loaded {:key [:admin-usage days] :query {:query-id :get-admin-usage :days days}
                   :error-el-id (els/panel-error :usage)}
      (fn [rows]
        (if (empty? rows)
          [layout/empty-state {:title "No LLM calls" :el-id els/usage-empty}
           "No LLM calls in this period."]
          [RawStack {:spacing 2}
           [team-usage-table (events/team-totals rows)]
           [usage-table rows]]))]]))

(defn- teams-table
  [teams]
  [layout/table {:el-id els/teams-table}
   (layout/head "Team" "Source" "Last sync" "Definitions")
   (for [{:keys [id name status synced-at counts invalid] :as team} teams]
     ^{:key id}
     [layout/table-row {}
      [layout/cell [layout/link {:page :team :params {:team-id id} :el-id (els/team-link id)} name]]
      [layout/cell (format/source-label team)]
      [layout/cell (if (= :ok (format/kw status))
                     (format/relative-time synced-at)
                     [:> Chip {:color "error" :label (or (:error-message team) "Failed")}])]
      [layout/cell
       (str (:workflows counts 0) " workflows, " (:agents counts 0) " agents, " (:harnesses counts 0) " harnesses")
       (when (pos? (or invalid 0))
         [:> Chip {:color "warning" :label (str invalid " with problems") :sx (:chip-gap theme/sx)}])]])])

(defn- teams
  []
  [layout/section {:title "Team repositories"
                   :description "Sync state of every team. Definitions with problems can't run until they are fixed in the repository."}
   [data/loaded {:key :teams :query {:query-id :list-teams} :error-el-id (els/panel-error :teams)}
    teams-table]])

(defn- connection-row
  [{:keys [id type models]}]
  (let [{:keys [state text]}
        (get (ui :tests {}) id)

        testing?
        @(rf/subscribe [::data/busy? [::events/test id]])]
    [layout/list-row {}
     [RawBox {:sx (:grow theme/sx)}
      [RawTypography {:variant "h3"} id]
      [RawTypography {:variant "body2" :color "text.secondary"}
       (str (some-> type name) ": " (string/join ", " models))]
      (when state
        [:> Typography {:variant "body2" :color (if (= :ok state) "success.main" "error.main")
                        :data-testid (el/id (els/test-result id)) :data-state (name state)}
         text])]
     [:> Button {:variant "outlined" :loading testing? :data-testid (el/id (els/test-connection id))
                 :on-click #(rf/dispatch [::events/test-connection id])}
      "Test connection"]]))

(defn- connection-list
  [tenant]
  [:<>
   (into [:> Paper {:data-testid (el/id els/connection-list)}]
         (for [connection (:llm-connections tenant)]
           ^{:key (:id connection)}
           [connection-row connection]))
   [:> Typography {:variant "body2" :color "text.secondary"}
    "Sandbox images teams may use: " [layout/mono (string/join ", " (:allowed-images tenant))]]])

(defn- connections
  []
  [layout/section {:title "LLM connections"
                   :el-id els/connections
                   :description "Sends a tiny prompt to the first model of a connection to check its address and credentials."}
   [data/loaded {:key :tenant :query {:query-id :get-tenant} :error-el-id (els/panel-error :connections)}
    connection-list]])

(defn- tenant-value-of
  [credential]
  (format/kw (:tenant-value credential)))

(defn- tenant-value
  [{:keys [hint] :as credential}]
  (let [value
        (tenant-value-of credential)]
    (cond
      (not (contains? (format/scopes (:provided-by credential)) :tenant)) "—"
      (= :admin value) [:> Chip {:color "success" :label (str "Set · " hint)}]
      (= :file value) [:> Chip {:color "success" :label "From secret file"}]
      :else [:> Chip {:color "error" :label "Missing"}])))

(defn- credentials-table
  [credentials]
  (let [me
        @(rf/subscribe [::session/me])

        tenant
        (:tenant me)]
    [layout/table {:el-id els/credentials-table}
     (layout/head "Credential" "Provided by" "Tenant value" ["Team values" right] ["Personal values" right] "Used by" "")
     (for [{:keys [id name hosts team-values personal-values used-by] :as credential} credentials
           :let [allowed
                 (format/scopes (:provided-by credential))

                 value
                 (tenant-value-of credential)]]
       ^{:key id}
       [layout/table-row {}
        [layout/cell
         [RawTypography {:sx (:strong theme/sx)} name]
         (when-not (= (str name) (str id))
           [layout/mono id])
         (when (seq hosts)
           [:> Typography {:variant "body2" :color "text.secondary"}
            "Sent only to " [layout/mono (string/join ", " hosts)]])]
        [layout/cell
         (format/provided-by-label (:provided-by credential))
         (when-let [text
                    (events/required-text tenant #(roles/team-name me %) credential)]
           [RawBox {:sx (:spaced-top theme/sx)}
            [:> Chip {:color "warning" :size "small" :label "Required" :title text
                      :data-testid (el/id (els/required-credential id))}]
            [:> Typography {:variant "body2" :color "text.secondary"} text]])]
        [layout/cell [tenant-value credential]]
        [layout/cell right (if (allowed :team) team-values "—")]
        [layout/cell right (if (allowed :user) personal-values "—")]
        [layout/cell (if (seq used-by) (string/join ", " used-by) "—")]
        [layout/cell {:align "right" :sx (:nowrap theme/sx)}
         (when (allowed :tenant)
           [credential-form/actions {:scope :tenant
                                     :credential credential
                                     :has-value? (some? value)
                                     :set-label "Set"
                                     :set-el-id (els/set-credential id)
                                     :dense? true
                                     :clear (when (= :admin value)
                                              {:label "Clear" :el-id (els/clear-credential id)
                                               :error ::events/credentials-action
                                               :refetch [:admin-credentials]})}])]])]))

(defn- credentials
  []
  (let [action-error
        @(rf/subscribe [::data/error ::events/credentials-action])]
    [layout/section {:title "Credentials"
                     :description "Declared in tenant.json; tenant values are set here and stored encrypted. Values are never shown. Team admins set team values on their team's page. Secret files under /config/secrets still work as tenant values."}
     [layout/error-alert action-error els/credentials-action-error]
     [data/loaded {:key :admin-credentials :query {:query-id :list-admin-credentials}
                   :error-el-id (els/panel-error :credentials)}
      credentials-table]
     [credential-form/credential-dialog {:scope :tenant :refetch [:admin-credentials]}]]))

(defn- mcp-server-list
  [servers]
  (if (empty? servers)
    [layout/empty-state {:title "No MCP servers" :el-id els/mcp-empty}
     "Add mcp_servers to tenant.json in the onboarding repository."]
    (into [:> Paper {:data-testid (el/id els/mcp-list)}]
          (for [{:keys [id name reachable detail credential credential-ready description url tools harnesses]} servers]
            ^{:key id}
            [layout/list-row {:el-id (els/mcp-server id)}
             [RawBox {:sx (:grow theme/sx)}
              [layout/row
               [RawTypography {:variant "h3"} name]
               [:> Chip {:color (if reachable "success" "error")
                         :label (if reachable "Reachable" (str "Unreachable (" detail ")"))}]
               (when-not credential-ready
                 [:> Chip {:color "error" :label (str credential " missing")}])]
              [RawTypography {:variant "body2" :color "text.secondary"} description]
              [:> Typography {:variant "body2" :color "text.secondary"}
               [layout/mono url]
               (when credential
                 [:<> " · credential " [layout/mono credential]])]
              [RawTypography {:variant "caption" :color "text.secondary"}
               (str "Tools: " (if tools (string/join ", " tools) "all")
                    " · used by " (if (seq harnesses) (string/join ", " harnesses) "no harness yet"))]]]))))

(defn- mcp-servers
  []
  [layout/section {:title "MCP servers"
                   :description "Declared in tenant.json. Sandboxes reach them only through the platform gateway, which adds credentials, enforces the tool list and records every call in the audit trail."}
   [data/loaded {:key :mcp-servers :query {:query-id :list-mcp-servers} :error-el-id (els/panel-error :mcp)}
    mcp-server-list]])

(def job-names
  {"platform-sync" "Sync repositories"
   "tenant-sync" "Sync repositories"
   "reap-sessions" "Stop idle sessions"})

(defn job-name
  "A background job as people read it. A workflow's job says what it is by
   its :kind: a schedule starts runs, a pull request poll looks for new
   ones. Without a kind it is named neutrally."
  [{:keys [id kind workflow team]}]
  (let [id
        (str id)

        subject
        (cond
          (and workflow team) (str workflow " · " team)
          (string/starts-with? id "trigger-") (subs id 8)
          :else id)]
    (cond
      (contains? job-names id) (get job-names id)
      (= :schedule (format/kw kind)) (str "Run on a schedule (" subject ")")
      (= :pull-request (format/kw kind)) (str "Check for new pull requests (" subject ")")
      (string/starts-with? id "trigger-") (str "Workflow trigger (" subject ")")
      :else id)))

(defn- schedules-table
  [jobs]
  [layout/table {:el-id els/schedules-table}
   (layout/head "Job" "Last run" "Next run")
   (for [{:keys [id paused last-run next-run] :as job} jobs]
     ^{:key (str id)}
     [layout/table-row {}
      [layout/cell (job-name job) " " (when paused [:> Chip {:label "Paused"}])]
      [layout/cell (format/relative-time last-run)]
      [layout/cell (format/date-time next-run)]])])

(defn- schedules
  []
  [layout/section {:title "Background jobs" :description "Recurring jobs the platform runs."}
   [data/loaded {:key :schedules :query {:query-id :list-schedules} :interval 15000
                 :error-el-id (els/panel-error :jobs)}
    schedules-table]])

(def tabs
  [[:overview "Overview" overview]
   [:people "People" users]
   [:usage "Usage & cost" usage]
   [:teams "Teams" teams]
   [:connections "LLM connections" connections]
   [:credentials "Credentials" credentials]
   [:mcp "MCP servers" mcp-servers]
   [:jobs "Background jobs" schedules]])

(defn main-panel
  []
  (let [me
        @(rf/subscribe [::session/me])

        tab
        (data/tab-of tabs (:tab @(rf/subscribe [::edd-subs/url-params])))

        panel
        (some (fn [[id _ panel]] (when (= id tab) panel)) tabs)]
    [layout/page-stack
     [layout/page-header {:title (str "Admin · " (roles/tenant-label me))
                          :el-id els/page
                          :actions [[layout/scope-chip [:tenant (roles/tenant-label me)] els/scope]]
                          :description (str "Health, people, spend and configuration of tenant " (roles/tenant-label me)
                                            ". Everything here acts on the whole tenant. Configuration itself lives in the onboarding repository; this page shows its effect.")}]
     (if (roles/tenant-admin? me)
       [:<>
        [layout/tabs {:value tab
                      :tabs (map (juxt first second) tabs)
                      :on-change #(layout/navigate! :admin nil {:tab (name %)})
                      :tab-el-id els/tab}]
        ^{:key tab}
        [panel]]
       [RawAlert {:severity "info" :el-id els/forbidden} "Only administrators of this tenant see its admin pages."])]))
