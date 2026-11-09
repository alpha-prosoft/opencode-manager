(ns manager-web.session.views
  (:require ["@mui/icons-material/EditOutlined" :default EditIcon]
            ["@mui/icons-material/LockOutlined" :default LockIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/DialogActions" :default DialogActions]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogTitle" :default DialogTitle]
            ["@mui/material/MenuItem" :default MenuItem]
            ["@mui/material/TextField" :default TextField]
            [clojure.string :as string]
            [manager-web.components.audit :as audit]
            [manager-web.components.chips :as chips]
            [manager-web.components.conversations :as conversations]
            [manager-web.components.layout :as layout]
            [manager-web.components.live-activity :as live-activity]
            [manager-web.components.session-actions :as session-actions]
            [manager-web.components.usage :as usage]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.session.elements :as els]
            [manager-web.session.events :as events]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawCircularProgress RawStack RawTypography]]))

(def sharing
  [[:private "Only the owner can work in it; team admins can see it"]
   [:team "Everyone in the team"]
   [:public "Everyone on the platform"]])

(def blocked-reasons
  {:locked "This session is locked, so nobody can continue in it. Fork it to continue in your own copy."
   :personal "This session works with its owner's personal credentials, so only they can work in it. Fork it to continue with your own."
   :private "This session is private to its owner. Fork it to continue in your own copy."
   :team-only "This session works with its team's credentials, so only team members can work in it."
   :hidden "You can't open this session."})

(defn- detail-items
  [{:keys [is-owner sub team-id run-id created-at forked-from locked-at locked-by url]}]
  (cond-> [["Owner" (if is-owner "You" sub)]
           ["Team" (if team-id
                     [layout/link {:page :team :params {:team-id team-id} :el-id els/team-link} team-id]
                     "—")]
           ["Started by run" (if run-id
                               [layout/link {:page :run :params {:run-id run-id} :el-id els/run-link}
                                [layout/mono (str run-id)]]
                               "—")]
           ["Created" (format/date-time created-at)]]
    forked-from (conj ["Forked from" [layout/link {:page :session :params {:session-id forked-from} :el-id els/forked-from}
                                      [layout/mono (str forked-from)]]])
    locked-at (conj ["Locked" (str (format/date-time locked-at) " by " locked-by)])
    true (conj ["Address" [layout/mono url]])))

(defn- sharing-section
  [session]
  (let [visibility
        (format/kw (:visibility session))

        saving?
        @(rf/subscribe [::data/busy? [::events/visibility (:id session)]])]
    [layout/section {:title "Sharing" :description "Who can see and open this session."}
     (if (:can-manage session)
       (into [:> TextField {:select true :fullWidth true :value (name visibility) :id (el/id els/sharing)
                            :disabled saving?
                            :slotProps {:input {:endAdornment (when saving?
                                                                (r/as-element [RawCircularProgress {:size 20 :el-id els/sharing-saving}]))}}
                            :on-change #(rf/dispatch [::events/set-visibility (:id session) (keyword (.. % -target -value))])}]
             (for [[option label] sharing]
               ^{:key option}
               [:> MenuItem {:value (name option) :data-testid (el/id (els/sharing-option option))} label]))
       [RawTypography {:el-id els/sharing-text} (get (into {} sharing) visibility)])]))

(defn- rename-dialog
  [session]
  (let [values
        @(rf/subscribe [::data/ui :session :rename nil])

        busy?
        @(rf/subscribe [::data/busy? [::events/rename (:id session)]])

        error
        @(rf/subscribe [::data/error ::events/rename])

        close
        #(when-not busy? (rf/dispatch [::events/close-rename]))]
    [:> Dialog {:open (some? values) :on-close close :fullWidth true :maxWidth "sm"
                :data-testid (el/id els/rename-dialog)}
     (when values
       [:form {:on-submit (fn [e]
                            (.preventDefault e)
                            (rf/dispatch [::events/rename (:id session)]))}
        [:> DialogTitle "Rename session"]
        [:> DialogContent
         [RawStack {:spacing 2 :sx (:form-top theme/sx)}
          [:> TextField {:label "Name" :required true :fullWidth true :id (el/id els/rename-name)
                         :value (str (:name values))
                         :slotProps {:htmlInput {:maxLength 100}}
                         :on-change #(rf/dispatch [::events/set-rename :name (.. % -target -value)])}]
          [:> TextField {:label "Description" :fullWidth true :multiline true :minRows 2
                         :id (el/id els/rename-description)
                         :value (str (:description values))
                         :on-change #(rf/dispatch [::events/set-rename :description (.. % -target -value)])}]
          [layout/error-alert error els/rename-error]]]
        [:> DialogActions
         [:> Button {:on-click close :disabled busy? :data-testid (el/id els/rename-cancel)} "Cancel"]
         [:> Button {:type "submit" :variant "contained" :loading busy?
                     :disabled (string/blank? (:name values))
                     :data-testid (el/id els/rename-save)}
          "Save"]]])]))

(defn- rename-button
  [session]
  [:> Button {:size "small" :startIcon (r/as-element [:> EditIcon]) :data-testid (el/id els/rename)
              :on-click #(rf/dispatch [::events/open-rename session])}
   "Rename"])

(defn- overview
  [session]
  [RawStack {:spacing 4}
   [layout/two-columns
    [layout/section {:title "Details" :el-id els/details}
     [layout/detail-list (detail-items session)]]
    [sharing-section session]]
   [usage/usage-section (:usage session)]])

(defn- session-view
  [session]
  (let [tab
        @(rf/subscribe [::data/ui :session :tab :overview])

        live?
        (format/session-live? (:status session))

        action-error
        @(rf/subscribe [::data/error ::events/action])

        blocked
        (format/kw (:open-blocked session))]
    [layout/page-stack
     [layout/page-header
      {:back {:page :sessions :label "Sessions" :el-id els/back}
       :title (:name session)
       :title-el-id els/title
       :el-id els/page
       :description (when-not (string/blank? (:description session)) (:description session))
       :meta [[chips/session-status-chip (:status session) (el/id els/status)]
              (when (:activity session) [chips/activity-chip (:activity session)])
              [chips/visibility-chip (:visibility session)]
              (when (:locked-at session)
                [:> Chip {:color "warning" :icon (r/as-element [:> LockIcon]) :label "Locked"
                          :data-testid (el/id els/locked)}])
              (when (:personal session)
                [:> Chip {:label (if (:is-owner session) "Uses your credentials" "Uses the owner's credentials")}])
              [RawTypography {:variant "body2"} (str "Last activity " (format/relative-time (:last-activity-at session)))]]
       :actions [(when (:can-manage session) [rename-button session])
                 [session-actions/session-actions session {:prominent? true
                                                           :error-id ::events/action
                                                           :refetch [[:session (:id session)]]
                                                           :on-deleted [:sessions]}]]}]
     [layout/error-alert action-error els/action-error]
     [rename-dialog session]
     (when blocked
       [RawAlert {:severity "info" :el-id els/blocked} (get blocked-reasons blocked)])
     [layout/tabs {:value tab
                   :tabs [[:overview "Overview"]
                          [:conversations "Conversations"]
                          [:activity "Live activity"]
                          [:audit (str "Audit trail (" (or (:audit-events session) 0) ")")]]
                   :on-change #(rf/dispatch [::data/set-ui :session :tab %])
                   :tab-el-id els/tab}]
     (case tab
       :overview [overview session]
       :conversations [conversations/conversations session]
       :activity [live-activity/live-activity {:session-id (:id session) :live? live?}]
       :audit [audit/audit-timeline (:id session)]
       nil)]))

(defn main-panel
  []
  (when-let [session-id
             @(rf/subscribe [::data/ui :session :session-id])]
    [data/loaded {:key [:session session-id]
                  :query {:query-id :get-session :session-id session-id}
                  :interval 5000
                  :poll-while #(format/session-live? (:status %))
                  :error-el-id els/error}
     session-view]))
