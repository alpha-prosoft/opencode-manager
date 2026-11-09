(ns manager-web.components.chips
  "Status, verdict, visibility, activity, role and network chips. Colors come
   from theme/status-colors."
  (:require ["@mui/icons-material/Build" :default BuildIcon]
            ["@mui/icons-material/Circle" :default CircleIcon]
            ["@mui/icons-material/Description" :default DescriptionIcon]
            ["@mui/icons-material/Groups" :default GroupsIcon]
            ["@mui/icons-material/Lock" :default LockIcon]
            ["@mui/icons-material/Public" :default PublicIcon]
            ["@mui/icons-material/RateReview" :default RateReviewIcon]
            ["@mui/icons-material/Terminal" :default TerminalIcon]
            ["@mui/icons-material/Widgets" :default WidgetsIcon]
            ["@mui/material/Chip" :default Chip]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [reagent.core :as r]))

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

(def ^:private visibility
  {:private {:icon LockIcon :label "Private"}
   :team {:icon GroupsIcon :label "Team"}
   :public {:icon PublicIcon :label "Everyone"}})

(defn visibility-chip
  [value]
  (let [{:keys [icon label]}
        (get visibility (format/kw value) {:icon LockIcon :label (some-> value name)})]
    [:> Chip {:icon (r/as-element [:> icon]) :label label}]))

(defn activity-chip
  [activity]
  (let [{:keys [label icon]}
        (activity-meta activity)]
    [:> Chip {:icon (r/as-element [:> icon]) :label label}]))

(defn role-chip
  "A team role; admin-label names the admin role."
  ([role]
   (role-chip role "Team admin"))
  ([role admin-label]
   (when role
     (let [admin?
           (= :admin (format/kw role))]
       [:> Chip {:label (if admin? admin-label "Member")
                 :color (if admin? "primary" "default")}]))))

(defn network-chip
  [network]
  (let [internet?
        (= :internet (format/kw network))]
    [:> Chip {:color (if internet? "warning" "success")
              :label (if internet? "Internet access" "No internet")}]))
