(ns manager-web.tenants.views
  "The operator's view of every tenant: sync state, sync one now, register
   another."
  (:require ["@mui/icons-material/Sync" :default SyncIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/TextField" :default TextField]
            [clojure.string :as string]
            [manager-web.components.chips :as chips]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [manager-web.tenants.elements :as els]
            [manager-web.tenants.events :as events]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(def ^:private right
  {:align "right"})

(defn- sync-cell
  [{:keys [name synced-at commit error-message] :as tenant}]
  (let [state
        (events/sync-state tenant)]
    [RawStack {:spacing 0.5 :sx (:start-aligned theme/sx)}
     [chips/sync-chip state (els/sync-status name)]
     [RawTypography {:variant "caption" :color "text.secondary"}
      (if (= :failed state)
        error-message
        [:<> (format/relative-time synced-at) " · " [layout/mono (format/short-commit commit)]])]]))

(defn- sync-button
  [tenant-name]
  (let [busy?
        @(rf/subscribe [::data/busy? [::events/sync tenant-name]])]
    [:> Button {:size "small" :startIcon (r/as-element [:> SyncIcon]) :loading busy? :sx (:one-line theme/sx)
                :data-testid (el/id (els/sync tenant-name))
                :on-click #(rf/dispatch [::data/run (events/sync-spec tenant-name)])}
     "Sync now"]))

(defn- tenants-table
  [tenants]
  [layout/table {:el-id els/table}
   (layout/head "Tenant" "Onboarding repository" "Last sync" ["Teams" right] "Configured in" "")
   (for [{:keys [name source teams registered] :as tenant} tenants]
     ^{:key name}
     [layout/table-row {:data-testid (el/id (els/row name))}
      [layout/cell {:sx (:strong theme/sx)} name]
      [layout/cell [layout/mono (str source)]]
      [layout/cell [sync-cell tenant]]
      [layout/cell right (or teams 0)]
      [layout/cell (if registered "Registered here" "Server configuration")]
      [layout/cell {:align "right"} [sync-button name]]])])

(defn- register-form
  []
  (let [values
        @(rf/subscribe [::data/ui :tenants :register events/empty-form])

        registered
        @(rf/subscribe [::data/ui :tenants :registered nil])

        busy?
        @(rf/subscribe [::data/busy? ::events/register])

        error
        @(rf/subscribe [::data/error ::events/register])

        change
        (fn [k]
          #(rf/dispatch-sync [::events/set-field k (.. % -target -value)]))]
    [:form {:on-submit (fn [e]
                         (.preventDefault e)
                         (rf/dispatch [::events/register values]))}
     [RawStack {:spacing 2}
      [layout/error-alert error els/register-error]
      (when registered
        [RawAlert {:severity "success" :el-id els/registered}
         (str "Registered " registered ". Its repositories sync in the background.")])
      [RawStack {:direction {:xs "column" :sm "row"} :spacing 2}
       [:> TextField {:label "Tenant name" :size "small" :required true :value (:name values)
                      :id (el/id els/register-name) :on-change (change :name)}]
       [:> TextField {:label "Onboarding repository" :size "small" :required true :fullWidth true
                      :value (:onboarding values) :id (el/id els/register-onboarding)
                      :helperText "A git URL, an https:// archive or a local path holding tenant.json"
                      :on-change (change :onboarding)}]]
      [RawBox
       [:> Button {:type "submit" :variant "contained" :loading busy?
                   :disabled (or (string/blank? (:name values)) (string/blank? (:onboarding values)))
                   :data-testid (el/id els/register-submit)}
        "Register tenant"]]]]))

(defn- reload-button
  []
  [:> Button {:variant "outlined" :startIcon (r/as-element [:> SyncIcon])
              :loading @(rf/subscribe [::data/busy? ::events/reload])
              :data-testid (el/id els/reload)
              :on-click #(rf/dispatch [::data/run events/reload-spec])}
   "Reload configuration"])

(defn- reload-result
  []
  (let [result
        @(rf/subscribe [::data/ui :tenants :reload nil])]
    [:<>
     [layout/error-alert @(rf/subscribe [::data/error ::events/reload]) els/reload-error]
     (when result
       [layout/section {:title "Configuration reloaded" :el-id els/reload-result
                        :description "The claims parser file, the tenants file and the identity provider's keys, read again just now. A part that failed keeps its last good value."}
        [layout/detail-list
         (for [[id label part] (events/reload-parts result)]
           [label [:> Chip {:color (if (events/part-failed? part) "error" "success")
                            :label (events/part-text part)
                            :data-testid (el/id (els/reload-part id))
                            :data-state (if (events/part-failed? part) "failed" "ok")}]])]])]))

(defn main-panel
  []
  (let [me
        @(rf/subscribe [::session/me])

        action-error
        @(rf/subscribe [::data/error ::events/action])]
    [layout/page-stack
     [layout/page-header {:title "Tenants"
                          :el-id els/page
                          :actions [[layout/scope-chip [:platform] els/scope]
                                    (when (roles/operator? me) [reload-button])]
                          :description "Every tenant of this platform and the sync state of its onboarding repository. Each tenant syncs on its own, so one failing never holds up another. Tenant admins, teams and members come from each tenant's identity provider groups, not from here."}]
     (if (roles/operator? me)
       [:<>
        [layout/error-alert action-error els/action-error]
        [reload-result]
        [data/loaded {:key :tenants :query {:query-id :list-tenants} :interval 5000 :error-el-id els/error}
         tenants-table]
        [layout/section {:title "Register a tenant"
                         :description "The platform syncs the tenant's onboarding repository (tenant.json) and its teams' repositories. Membership comes from your identity provider's groups."}
         [register-form]]]
       [RawAlert {:severity "info" :el-id els/forbidden} "Only platform operators manage tenants."])]))
