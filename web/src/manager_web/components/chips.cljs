(ns manager-web.components.chips
  "Status, verdict, visibility, activity, role and network chips. Colors come
   from theme/status-colors."
  (:require ["@mui/icons-material/AdminPanelSettingsOutlined" :default AdminIcon]
            ["@mui/icons-material/Build" :default BuildIcon]
            ["@mui/icons-material/Circle" :default CircleIcon]
            ["@mui/icons-material/Description" :default DescriptionIcon]
            ["@mui/icons-material/Groups" :default GroupsIcon]
            ["@mui/icons-material/GroupsOutlined" :default GroupsOutlinedIcon]
            ["@mui/icons-material/Lock" :default LockIcon]
            ["@mui/icons-material/Public" :default PublicIcon]
            ["@mui/icons-material/RateReview" :default RateReviewIcon]
            ["@mui/icons-material/Terminal" :default TerminalIcon]
            ["@mui/icons-material/Widgets" :default WidgetsIcon]
            ["@mui/material/Chip" :default Chip]
            [manager-web.components.layout :as layout]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawStack]]))

(def ^:private status-labels
  {:session {:running "Running"
             :starting "Starting"
             :stopped "Stopped"
             :error "Error"}
   :run {:queued "Queued"
         :preparing "Starting sandbox"
         :running "Agent working"
         :awaiting-result "Agent working"
         :finalizing "Collecting result"
         :following-ci "Following build"
         :completed "Completed"
         :failed "Failed"
         :cancelled "Cancelled"}
   :build {:waiting "Waiting for the build"
           :running "Building"
           :passed "Build passed"
           :failed "Build failed"
           :timeout "Stopped waiting"}
   :verdict {:approve "Approved"
             :approve-with-nits "Approved with nits"
             :request-changes "Changes requested"}})

(defn status-color
  "The MUI color of a status of a kind (:run, :session, :build, :stage,
   :verdict, :severity)."
  ([kind status]
   (status-color kind status "default"))
  ([kind status default]
   (get-in theme/status-colors [kind (format/kw status)] default)))

(defn status-label
  [kind status]
  (or (get-in status-labels [kind (format/kw status)])
      (some-> status name)))

(defn status-chip
  "A chip for a status of a kind; :dot? adds the status dot."
  [kind status & [{:keys [el-id dot?]}]]
  [:> Chip (cond-> {:label (status-label kind status)
                    :color (status-color kind status)
                    :data-testid el-id
                    :data-status (some-> (format/kw status) name)}
             dot? (assoc :icon (r/as-element [:> CircleIcon {:sx (:status-dot theme/sx)}])))])

(defn session-status-chip
  [status & [el-id]]
  [status-chip :session status {:el-id el-id :dot? true}])

(defn run-status-chip
  [status & [el-id]]
  [status-chip :run status {:el-id el-id :dot? true}])

(def activities
  {:session {:label "Workspace" :icon TerminalIcon}
   :review {:label "Code review" :icon RateReviewIcon}
   :implement {:label "Implementation" :icon BuildIcon}
   :analysis {:label "Analysis" :icon DescriptionIcon}})

(defn activity-meta
  [activity]
  (or (get activities (format/kw activity))
      {:label (some-> activity name) :icon WidgetsIcon}))

(def ^:private visibility-icons
  {:private LockIcon
   :team GroupsIcon
   :tenant PublicIcon})

(defn visibility-icon
  [value]
  (get visibility-icons (format/kw value) LockIcon))

(defn visibility-chip
  [value]
  (let [tenant
        (:tenant @(rf/subscribe [::session/me]))]
    [:> Chip {:icon (r/as-element [:> (visibility-icon value)]) :label (roles/visibility-label value tenant)}]))

(defn session-visibility-chip
  "Who sees the session, from the viewer's side (\"Private to dave\")."
  [session & [el-id]]
  (let [me
        @(rf/subscribe [::session/me])]
    [:> Chip {:icon (r/as-element [:> (visibility-icon (:visibility session))])
              :label (roles/session-visibility-label me session)
              :data-testid el-id
              :data-visibility (some-> (format/kw (:visibility session)) name)}]))

(defn admin-access-chip
  "Says when the viewer sees a session only as an admin."
  [session & [el-id]]
  (when-let [label
             (roles/admin-access @(rf/subscribe [::session/me]) session)]
    [:> Chip {:color "secondary" :icon (r/as-element [:> AdminIcon]) :label label :data-testid el-id}]))

(defn team-chip
  "The team something belongs to, by name."
  [team-id & [el-id]]
  (when team-id
    [:> Chip {:icon (r/as-element [:> GroupsOutlinedIcon])
              :label (roles/team-name @(rf/subscribe [::session/me]) team-id)
              :data-testid el-id
              :data-team team-id}]))

(defn activity-chip
  [activity]
  (let [{:keys [label icon]}
        (activity-meta activity)]
    [:> Chip {:icon (r/as-element [:> icon]) :label label}]))

(def ^:private role-chip-labels
  {:admin ["Team admin" "primary"]
   :member ["Member" "default"]
   :tenant-admin ["Tenant admin, not a member" "secondary"]})

(defn role-chip
  "The person's role in a team and where it comes from: their own (admin,
   member) or the tenant's (a tenant admin who isn't a member)."
  [team-id & [el-id]]
  (when-let [role
             (roles/role-in-team @(rf/subscribe [::session/me]) team-id)]
    (let [[label color]
          (role-chip-labels role)]
      [:> Chip {:label label :color color :data-testid el-id :data-role (name role)}])))

(defn team-heading
  "The heading of a group of things of one team: its name, linking to the
   team, and the viewer's role in it."
  [team-id team-name {:keys [link-el-id role-el-id]}]
  [RawStack {:direction "row" :spacing 1 :sx (:team-heading theme/sx)}
   [layout/link {:page :team :params {:team-id team-id} :variant "subtitle2" :color "text.secondary"
                 :el-id link-el-id}
    team-name]
   [role-chip team-id (when role-el-id (el/id role-el-id))]])

(def ^:private sync-chips
  {:failed ["error" "Sync failed"]
   :synced ["success" "Synced"]
   :never ["default" "Not synced yet"]})

(defn sync-chip
  "How the last sync of a tenant's repositories went (:failed, :synced or
   :never)."
  [state el-id]
  (let [[color label]
        (sync-chips state)]
    [:> Chip {:color color :label label :data-testid (el/id el-id) :data-state (name state)}]))

(defn network-chip
  [network]
  (let [internet?
        (= :internet (format/kw network))]
    [:> Chip {:color (if internet? "warning" "success")
              :label (if internet? "Internet access" "No internet")}]))
