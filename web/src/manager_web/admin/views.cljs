(ns manager-web.admin.views
  (:require ["@mui/icons-material/Sync" :default SyncIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/MenuItem" :default MenuItem]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/TextField" :default TextField]
            ["@mui/material/Typography" :default Typography]
            [clojure.string :as string]
            [manager-web.admin.elements :as els]
            [manager-web.admin.events :as events]
            [manager-web.components.chips :as chips]
            [manager-web.components.credential-form :as credential-form]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(def ^:private right
  {:align "right"})

(defn- ui
  [key default]
  @(rf/subscribe [::data/ui :admin key default]))

(defn runs-today-hint
  [runs-today]
  (let [parts
        (for [[status count] runs-today]
          (str count " " (string/lower-case (chips/status-label :run status))))]
    (if (seq parts) (string/join ", " parts) "none yet")))

(defn- overview-panel
  [{:keys [status teams users sessions runs-today tokens-today cost-today]}]
  (let [report
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
       "Sync repositories now"]
      [:> Button {:variant "outlined" :loading (and busy? (= :idle running)) :disabled busy?
                  :data-testid (el/id els/stop-idle)
                  :on-click #(rf/dispatch [::events/maintenance :idle])}
       "Stop idle sessions"]]
     (when report
       [:div {:data-testid (el/id els/report) :data-ok (str (boolean (:ok? report))) :data-action (name (:action report))}
        [RawAlert {:severity (if (:ok? report) "success" "error")} (:text report)]])
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
                     :hint "from model prices in platform.json"}]]]
     [:> Typography {:variant "body2" :color "text.secondary"}
      "Onboarding repository version " [layout/mono (format/short-commit (:commit status))]
      (str ", synced " (format/relative-time (:synced-at status)) ".")]]))

(defn- overview
  []
  [data/loaded {:key :admin-overview :query {:query-id :get-admin-overview} :interval 15000
                :error-el-id (els/panel-error :overview)}
   overview-panel])

(defn- users-table
  [users]
  (let [team-ids
        (sort (distinct (mapcat #(map name (keys (:teams %))) users)))]
    [layout/table {:el-id els/users-table}
     (apply layout/head (concat ["Person" "Platform"] team-ids [["Runs, 30 days" right] ["Tokens, 30 days" right]]))
     (for [user users]
       ^{:key (:username user)}
       [layout/table-row {}
        [layout/cell {:sx (:strong theme/sx)} (:username user)]
        [layout/cell (when (:platform-admin user) [:> Chip {:variant "filled" :color "primary" :label "Admin"}])]
        (for [team team-ids]
          ^{:key team}
          [layout/cell [chips/role-chip (get (:teams user) (keyword team)) "Admin"]])
        [layout/cell right (:runs-30d user)]
        [layout/cell right (format/tokens (:tokens-30d user))]])]))

(defn- users
  []
  [layout/section {:title "People and roles"
                   :description "Edit admins, team admins and members in platform.json in the onboarding repository."}
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
   (if (empty? rows)
     [^{:key "empty"}
      [layout/table-row {}
       [layout/cell {:colSpan 8 :sx (:muted theme/sx) :data-testid (el/id els/usage-empty)}
        "No LLM calls in this period."]]]
     (for [row rows]
       ^{:key (str (:team-id row) "-" (:connection row) "-" (:model row))}
       [layout/table-row {}
        [layout/cell (if (string/blank? (:team-id row)) "—" (:team-id row))]
        [layout/cell (:model row)]
        [layout/cell right (:calls row)]
        [layout/cell right (format/tokens (:prompt-tokens row))]
        [layout/cell right (format/tokens (:cached-tokens row))]
        [layout/cell right (format/tokens (:completion-tokens row))]
        [layout/cell right (:errors row)]
        [layout/cell right (format/money (:cost row))]]))])

(defn- usage-description
  [rows]
  (if (some #(some? (:cost %)) rows)
    (str "Estimated cost: " (format/money (reduce + 0 (keep :cost rows)))
         ". Models without a price in platform.json show no cost.")
    "Add model prices to llm_connections in platform.json to see cost estimates."))

(defn- usage
  []
  (let [days
        (ui :days 7)

        rows
        @(rf/subscribe [::data/data [:admin-usage days]])]
    [layout/section {:title "Usage and cost"
                     :description (usage-description rows)
                     :action (into [:> TextField {:select true :size "small" :value days :id (el/id els/usage-days)
                                                  :on-change #(rf/dispatch [::data/set-ui :admin :days
                                                                            (js/Number (.. % -target -value))])}]
                                   (for [option [1 7 30 90]]
                                     ^{:key option}
                                     [:> MenuItem {:value option :data-testid (el/id (els/usage-days-option option))}
                                      (days-label option)]))}
     [data/loaded {:key [:admin-usage days] :query {:query-id :get-admin-usage :days days}
                   :error-el-id (els/panel-error :usage)}
      usage-table]]))

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
  [platform]
  [:<>
   (into [:> Paper {:data-testid (el/id els/connection-list)}]
         (for [connection (:llm-connections platform)]
           ^{:key (:id connection)}
           [connection-row connection]))
   [:> Typography {:variant "body2" :color "text.secondary"}
    "Sandbox images teams may use: " [layout/mono (string/join ", " (:allowed-images platform))]]])

(defn- connections
  []
  [layout/section {:title "LLM connections"
                   :el-id els/connections
                   :description "Sends a tiny prompt to the first model of a connection to check its address and credentials."}
   [data/loaded {:key :platform :query {:query-id :get-platform} :error-el-id (els/panel-error :connections)}
    connection-list]])

(def provided-by
  {:platform "Admins only"
   :platform-or-user "Admins, people may use their own"
   :user "Each person"})

(defn- platform-value
  [{:keys [hint] :as credential}]
  (cond
    (= :user (format/kw (:provided-by credential))) "—"
    (= :admin (format/kw (:platform-value credential))) [:> Chip {:color "success" :label (str "Set · " hint)}]
    (= :file (format/kw (:platform-value credential))) [:> Chip {:color "success" :label "From secret file"}]
    :else [:> Chip {:color "error" :label "Missing"}]))

(defn- describe-credential
  [credential]
  {:title (str "Platform value of " (:name credential))
   :help (events/credential-help credential)})

(defn- credentials-table
  [credentials]
  [layout/table {:el-id els/credentials-table}
   (layout/head "Credential" "Provided by" "Platform value" ["Personal values" right] "Used by" "")
   (for [{:keys [id name hosts personal-values used-by] :as credential} credentials
         :let [source
               (format/kw (:provided-by credential))

               value
               (format/kw (:platform-value credential))]]
     ^{:key id}
     [layout/table-row {}
      [layout/cell
       [RawTypography {:sx (:strong theme/sx)} name]
       [layout/mono id]
       (when (seq hosts)
         [:> Typography {:variant "body2" :color "text.secondary"}
          "Sent only to " [layout/mono (string/join ", " hosts)]])]
      [layout/cell (get provided-by source)]
      [layout/cell [platform-value credential]]
      [layout/cell right (if (= :platform source) "—" personal-values)]
      [layout/cell (if (seq used-by) (string/join ", " used-by) "—")]
      [layout/cell {:align "right" :sx (:nowrap theme/sx)}
       (when-not (= :user source)
         [:> Button {:size "small" :data-testid (el/id (els/set-credential id))
                     :on-click #(rf/dispatch [::credential-form/edit :platform credential])}
          (if value "Replace" "Set")])
       (when (= :admin value)
         [:> Button {:size "small" :color "error" :data-testid (el/id (els/clear-credential id))
                     :on-click #(rf/dispatch [::events/clear-credential credential])}
          "Clear"])]])])

(defn- credentials
  []
  (let [action-error
        @(rf/subscribe [::data/error ::events/credentials-action])]
    [layout/section {:title "Credentials"
                     :description "Declared in platform.json; values are set here and stored encrypted. Values are never shown. Secret files under /config/secrets still work as platform values."}
     [layout/error-alert action-error els/credentials-action-error]
     [data/loaded {:key :admin-credentials :query {:query-id :list-admin-credentials}
                   :error-el-id (els/panel-error :credentials)}
      credentials-table]
     [credential-form/credential-dialog {:scope :platform :refetch [:admin-credentials] :describe describe-credential}]]))

(defn- mcp-server-list
  [servers]
  (if (empty? servers)
    [layout/empty-state {:title "No MCP servers" :el-id els/mcp-empty}
     "Add mcp_servers to platform.json in the onboarding repository."]
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
                   :description "Declared in platform.json. Sandboxes reach them only through the platform gateway, which adds credentials, enforces the tool list and records every call in the audit trail."}
   [data/loaded {:key :mcp-servers :query {:query-id :list-mcp-servers} :error-el-id (els/panel-error :mcp)}
    mcp-server-list]])

(def job-names
  {"platform-sync" "Sync repositories"
   "reap-sessions" "Stop idle sessions"})

(defn job-name
  [id]
  (let [id
        (str id)]
    (cond
      (contains? job-names id) (get job-names id)
      (string/starts-with? id "trigger-") (str "Check for new pull requests (" (subs id 8) ")")
      :else id)))

(defn- schedules-table
  [jobs]
  [layout/table {:el-id els/schedules-table}
   (layout/head "Job" "Last run" "Next run")
   (for [{:keys [id paused last-run next-run]} jobs]
     ^{:key (str id)}
     [layout/table-row {}
      [layout/cell (job-name id) " " (when paused [:> Chip {:label "Paused"}])]
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
  (let [tab
        (ui :tab :overview)

        panel
        (some (fn [[id _ panel]] (when (= id tab) panel)) tabs)]
    [layout/page-stack
     [layout/page-header {:title "Admin"
                          :el-id els/page
                          :description "Platform-wide health, people, spend and configuration. Configuration itself lives in the onboarding repository; this page shows its effect."}]
     [layout/tabs {:value tab
                   :tabs (map (juxt first second) tabs)
                   :on-change #(rf/dispatch [::data/set-ui :admin :tab %])
                   :tab-el-id els/tab}]
     ^{:key tab}
     [panel]]))
