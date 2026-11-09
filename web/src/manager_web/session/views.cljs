(ns manager-web.session.views
  (:require ["@mui/icons-material/CallSplit" :default CallSplitIcon]
            ["@mui/icons-material/EditOutlined" :default EditIcon]
            ["@mui/icons-material/GroupsOutlined" :default GroupsIcon]
            ["@mui/icons-material/LockOutlined" :default LockIcon]
            ["@mui/material/Alert" :default Alert]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/DialogActions" :default DialogActions]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogTitle" :default DialogTitle]
            ["@mui/material/TextField" :default TextField]
            [clojure.string :as string]
            [edd.subs :as edd-subs]
            [manager-web.components.audit :as audit]
            [manager-web.components.chips :as chips]
            [manager-web.components.conversations :as conversations]
            [manager-web.components.layout :as layout]
            [manager-web.components.live-activity :as live-activity]
            [manager-web.components.session-actions :as session-actions]
            [manager-web.components.sessions-table :as sessions-table]
            [manager-web.components.team-context :as team-context]
            [manager-web.components.usage :as usage]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [manager-web.session.elements :as els]
            [manager-web.session.events :as events]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawCircularProgress RawStack RawTypography]]))

(defn sharing
  [me session]
  (mapv (fn [option] [option (roles/session-visibility-label me (assoc session :visibility option))])
        (roles/visibility-options (sessions-table/team-session? session))))

(defn sharing-help
  [session]
  (if (sessions-table/team-session? session)
    "Who can see this team session. However widely it is shared, only the team's members can work in it; others may fork it."
    "Who can see and open this session."))

(defn may-change-sharing?
  "The owner changes who sees their session; team admins those of team sessions."
  [session]
  (and (:can-manage session)
       (or (:is-owner session) (sessions-table/team-session? session))))

(defn sharing-note
  "Who sees a session the viewer can't re-share, in one sentence."
  [me {:keys [visibility team-id sub] :as session}]
  (let [team
        (roles/team-name me team-id)]
    (case (format/kw visibility)
      :private (str "Private to " sub ": only " sub " and the admins of " team " see it.")
      :team (str "Shared with " team ": its members and admins see it.")
      :tenant (str "Shared with everyone in " (:tenant me) ".")
      (roles/session-visibility-label me session))))

(defn- admin-role-name
  [me team-id]
  (if (= :admin (roles/claimed-role me team-id))
    (str "admin of " (roles/team-name me team-id))
    "tenant admin"))

(def blocked-reasons
  {:locked (fn [_ _] "This session is locked, so nobody can continue in it. Fork it to continue in your own copy.")
   :personal (fn [_ {:keys [sub]}]
               (str "This session works with " (format/possessive sub) " personal credentials, so only " sub
                    " can work in it. Fork it to continue in your own copy, with your own credentials."))
   :private (fn [me {:keys [sub team-id can-manage]}]
              (if can-manage
                (str "This is " (format/possessive sub) " private session. As " (admin-role-name me team-id)
                     " you can see, rename and delete it, but only " sub " can work in it. Fork it to continue in your own copy.")
                (str "This session is private to " sub ". Fork it to continue in your own copy.")))
   :team-only (fn [me {:keys [team-id]}]
                (let [team (roles/team-name me team-id)]
                  (str "Only members of " team " can work in this team session (it uses " (format/possessive team) " credentials)."
                       " Fork it to get your own private copy in " team ", using your own credentials.")))
   :hidden (fn [_ _] "You can't open this session.")})

(defn blocked-text
  [blocked me session]
  (when-let [reason
             (get blocked-reasons blocked)]
    (reason me session)))

(defn forkable?
  [blocked]
  (contains? #{:locked :personal :private :team-only} blocked))

(defn- run-label
  [{:keys [labels created-at]}]
  (str (or (:workflow labels) "Workflow") " run · " (format/relative-time created-at)))

(defn- detail-items
  [me {:keys [team-id project run-id created-at forked-from locked-at locked-by url] :as session}]
  (let [member?
        (roles/can-open-team? me team-id)

        team
        (roles/team-name me team-id)]
    (cond-> [["Owner" (if (sessions-table/team-session? session)
                        (str (roles/session-owner me session) " (team session)")
                        (roles/session-owner me session))]
             ["Team" (cond
                       (nil? team-id) "—"
                       member? [layout/link {:page :team :params {:team-id team-id} :el-id els/team-link
                                             :attrs {:data-team team-id}} team]
                       :else [:span {:data-testid (el/id els/team-link) :data-team team-id}
                              (str team " (you're not a member)")])]
             ["Started by run" (cond
                                 (nil? run-id) "—"
                                 member? [layout/link {:page :run :params {:run-id run-id} :el-id els/run-link}
                                          (run-label session)]
                                 :else [:span {:data-testid (el/id els/run-link)}
                                        (str (run-label session) " (only " (format/possessive team) " members see its runs)")])]
             ["Project" (cond
                          (nil? project) "—"
                          member? [layout/link {:page :project :params {:team-id team-id :project-id project}
                                                :el-id els/project-link}
                                   project]
                          :else [:span {:data-testid (el/id els/project-link)} project])]
             ["Created" (format/date-time created-at)]]
      forked-from (conj ["Forked from" [layout/link {:page :session :params {:session-id forked-from} :el-id els/forked-from}
                                        "The original session"]])
      locked-at (conj ["Locked" (str (format/date-time locked-at) " by " locked-by)])
      true (conj ["Address" [layout/mono url]]))))

(defn- sharing-section
  [session]
  (let [me
        @(rf/subscribe [::session/me])

        visibility
        (format/kw (:visibility session))

        saving?
        @(rf/subscribe [::data/busy? [::events/visibility (:id session)]])]
    [layout/section {:title "Shared with" :description (sharing-help session)}
     (if (may-change-sharing? session)
       [layout/select {:fullWidth true
                       :size "medium"
                       :id (el/id els/sharing)
                       :disabled saving?
                       :slotProps {:input {:endAdornment (when saving?
                                                           (r/as-element [RawCircularProgress {:size 20 :el-id els/sharing-saving}]))}}
                       :value visibility
                       :options (sharing me session)
                       :option-el-id els/sharing-option
                       :on-change #(rf/dispatch [::events/set-visibility (:id session) (keyword %)])}]
       [RawTypography {:el-id els/sharing-text :data-visibility (name visibility)} (sharing-note me session)])]))

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
     [layout/detail-list (detail-items @(rf/subscribe [::session/me]) session)]]
    [sharing-section session]]
   [usage/usage-section (:usage session)]])

(defn- session-view
  [session]
  (let [tab
        (data/tab-of events/tabs (:tab @(rf/subscribe [::edd-subs/url-params])))

        live?
        (format/session-live? (:status session))

        action-error
        @(rf/subscribe [::data/error ::events/action])

        blocked
        (format/kw (:open-blocked session))

        me
        @(rf/subscribe [::session/me])

        team-id
        (:team-id session)

        list-query
        (team-context/query (when (roles/team-of me team-id) team-id))]
    [layout/page-stack
     [layout/page-header
      {:crumbs [layout/entity-crumbs me
                {:team-id team-id
                 :project (:project session)
                 :list {:label "Sessions" :page :sessions :query list-query}
                 :label (:name session)
                 :els {:crumbs els/crumbs :tenant els/crumb-tenant :team els/crumb-team
                       :project els/crumb-project :list els/back}}]
       :title (:name session)
       :title-el-id els/title
       :el-id els/page
       :description (when-not (string/blank? (:description session)) (:description session))
       :meta [[chips/team-chip team-id (el/id els/team-chip)]
              [chips/session-status-chip (:status session) (el/id els/status)]
              (when (:activity session) [chips/activity-chip (:activity session)])
              [chips/session-visibility-chip session (el/id els/visibility)]
              [chips/admin-access-chip session (el/id els/admin-access)]
              (when (:locked-at session)
                [:> Chip {:color "warning" :icon (r/as-element [:> LockIcon]) :label "Locked"
                          :data-testid (el/id els/locked)}])
              (when (sessions-table/team-session? session)
                [:> Chip {:color "primary" :icon (r/as-element [:> GroupsIcon]) :data-testid (el/id els/team-session)
                          :label (str "Team session · " (format/possessive (roles/team-name me team-id)) " credentials")}])
              (when (:personal session)
                [:> Chip {:label (if (:is-owner session) "Uses your credentials" "Uses the owner's credentials")}])
              [RawTypography {:variant "body2"} (str "Last activity " (format/relative-time (:last-activity-at session)))]]
       :actions [(when (:can-manage session) [rename-button session])
                 [session-actions/session-actions session {:prominent? true
                                                           :hide-fork? (forkable? blocked)
                                                           :error-id ::events/action
                                                           :refetch [[:session (:id session)]]
                                                           :on-deleted [:sessions nil list-query]}]]}]
     [layout/error-alert action-error els/action-error]
     [rename-dialog session]
     (when blocked
       [:> Alert {:severity "info" :data-testid (el/id els/blocked) :data-reason (name blocked)
                  :action (when (forkable? blocked)
                            (r/as-element
                             [:> Button {:size "small" :color "inherit" :startIcon (r/as-element [:> CallSplitIcon])
                                         :loading @(rf/subscribe [::data/busy? [:fork-session (:id session)]])
                                         :data-testid (el/id els/fork-to-continue)
                                         :on-click #(rf/dispatch [::session-actions/fork session ::events/action])}
                              "Fork to continue"]))}
        (blocked-text blocked me session)])
     [layout/tabs {:value tab
                   :tabs (for [[id label] events/tabs]
                           [id (if (= :audit id) (str label " (" (or (:audit-events session) 0) ")") label)])
                   :on-change #(layout/navigate! :session {:session-id (str (:id session))} {:tab (name %)})
                   :tab-el-id els/tab}]
     (case tab
       :overview [overview session]
       :conversations [conversations/conversations session]
       :activity [live-activity/live-activity {:session-id (:id session) :live? live?}]
       :audit [audit/audit-timeline (:id session)]
       nil)]))

(defn main-panel
  []
  (let [session-id
        @(rf/subscribe [::data/ui :session :session-id])

        back
        {:page :sessions :label "All sessions" :el-id els/back-to-list}]
    (cond
      (uuid? session-id)
      [data/loaded {:key [:session session-id]
                    :query {:query-id :get-session :session-id session-id}
                    :interval 5000
                    :poll-while #(format/session-live? (:status %))
                    :error-el-id els/error
                    :failed [layout/way-back back]}
       session-view]

      (some? session-id)
      [layout/not-found {:title "Session not found" :el-id els/not-found :back back}
       "No session has this address. It may have been mistyped."])))
