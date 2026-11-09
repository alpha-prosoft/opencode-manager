(ns manager-web.components.workflows
  "Workflow lists with their run dialog."
  (:require ["@mui/icons-material/GroupsOutlined" :default GroupsIcon]
            ["@mui/icons-material/PlayArrow" :default PlayArrowIcon]
            ["@mui/icons-material/Terminal" :default TerminalIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/DialogActions" :default DialogActions]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogTitle" :default DialogTitle]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Tooltip" :default Tooltip]
            ["@mui/material/Typography" :default Typography]
            [clojure.string :as string]
            [manager-web.api :as api]
            [manager-web.components.chips :as chips]
            [manager-web.components.credential-scope :as scope]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.components.schema-form :as schema-form]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(defn team-workflows
  "The workflow definitions of a team detail as list items."
  [team]
  (for [definition (:definitions team)
        :when (= :workflows (format/kw (:kind definition)))]
    {:team-id (:id team)
     :team-name (:name team)
     :workflow definition}))

(defn find-activity
  [catalog activity-id]
  (some #(when (= (some-> activity-id name) (some-> (:id %) name)) %) catalog))

(defn inputs-schema
  "What a run of the workflow takes: its own inputs schema (a review of a branch
   takes a branch, not a pull request), else the activity's."
  [workflow activity]
  (or (:inputs-schema workflow) (:inputs-schema activity)))

(defn scm-provider
  "The provider of the pull requests a workflow works on: its options',
   else its trigger's."
  [{:keys [spec]}]
  (or (get-in spec [:options :scm :provider])
      (get-in spec [:options :pull-request :provider])
      (get-in spec [:trigger :provider])))

(defn interactive?
  "True for a workflow people work in themselves: its activity is
   interactive, as a workspace always is."
  [workflow activity]
  (or (true? (:interactive activity))
      (= :session (format/kw (get-in workflow [:spec :activity])))))

(defn default-visibility
  "Who sees the session of a new run unless the person chooses otherwise; the
   same as the service would choose: a person's interactive session starts
   private, other sessions are shared with the team, unless the workflow
   shares with the tenant."
  [workflow activity team-session?]
  (let [tenant?
        (= :tenant (format/kw (get-in workflow [:spec :visibility])))]
    (cond
      team-session? (if tenant? :tenant :team)
      (interactive? workflow activity) (if tenant? :tenant :private)
      tenant? :tenant
      :else :team)))

(defn opened
  "A fresh dialog; nonce tells its replies from those of an earlier one."
  [{:keys [team-id workflow activity team-session?]} nonce]
  {:team-id team-id :workflow workflow :activity activity
   :team-session? (boolean team-session?)
   :visibility (default-visibility workflow activity team-session?)
   :default-visibility (default-visibility workflow activity team-session?)
   :inputs {} :pull-requests [] :nonce nonce})

(rf/reg-event-fx
 ::open
 (fn [{:keys [db]} [_ {:keys [team-id workflow activity] :as choice}]]
   (let [nonce
         (random-uuid)]
     {:db (-> db
              (assoc ::dialog (opened choice nonce))
              (data/clear-error ::start-run))
      :fx [(when (schema-form/takes-pull-request? (inputs-schema workflow activity))
             [::api/call {:query {:query-id :list-open-pull-requests
                                  :team-id team-id
                                  :workflow-id (:id workflow)}
                          :on-success [::pull-requests-loaded nonce]
                          :on-failure [::pull-requests-failed nonce]}])]})))

(defn answered
  "The dialog with the reply to the request of nonce under key; a reply to
   an earlier dialog, or to one since closed, changes nothing."
  [dialog nonce key value]
  (if (and dialog (= nonce (:nonce dialog)))
    (assoc dialog key value)
    dialog))

(defn- answer
  [db nonce key value]
  (if-let [dialog
           (answered (::dialog db) nonce key value)]
    (assoc db ::dialog dialog)
    db))

(rf/reg-event-db
 ::pull-requests-loaded
 (fn [db [_ nonce pull-requests]]
   (answer db nonce :pull-requests (vec pull-requests))))

(rf/reg-event-db
 ::pull-requests-failed
 (fn [db [_ nonce message]]
   (answer db nonce :pull-requests-error message)))

(rf/reg-event-db
 ::close
 (fn [db _]
   (dissoc db ::dialog)))

(rf/reg-event-db
 ::set-input
 (fn [db [_ field value]]
   (update-in db [::dialog :inputs] schema-form/set-input field value)))

(defn start-run-spec
  [{:keys [team-id workflow inputs visibility]} run-id]
  {:id ::start-run
   :command {:cmd-id :start-run
             :id run-id
             :attrs (cond-> {:team-id team-id
                             :workflow-id (:id workflow)
                             :inputs inputs}
                      visibility (assoc :visibility visibility))}
   :navigate [:run {:run-id (str run-id)}]
   :refetch-on-failure [:my-credentials]
   :on-success [::close]})

(rf/reg-event-db
 ::set-visibility
 (fn [db [_ visibility]]
   (assoc-in db [::dialog :visibility] visibility)))

(defn start-team-session-spec
  "A session in the team's scope: the platform owns it and it works with the
   team's credentials. run-id is the run that starts it, generated here."
  [{:keys [team-id workflow inputs visibility]} run-id]
  {:id ::start-run
   :command {:cmd-id :start-team-session
             :id run-id
             :attrs {:team-id team-id
                     :workflow-id (:id workflow)
                     :inputs inputs
                     :visibility (or visibility :team)}}
   :navigate [:run {:run-id (str run-id)}]
   :refetch-on-failure [:my-credentials]
   :on-success [::close]})

(defn submit-spec
  [dialog run-id]
  (if (:team-session? dialog)
    (start-team-session-spec dialog run-id)
    (start-run-spec dialog run-id)))

(rf/reg-event-fx
 ::submit
 (fn [{:keys [db]} _]
   {:fx [[:dispatch [::data/run (submit-spec (::dialog db) (random-uuid))]]]}))

(rf/reg-sub ::dialog (fn [db _] (::dialog db)))

(defn dialog-intro
  "What starting does: a session someone works in, or a run that reports a
   result."
  [{:keys [team-session? workflow activity]}]
  (cond
    team-session? "Starts an interactive session that belongs to the team and works with the team's credentials, not yours. Its members work in it; it reports no result."
    (interactive? workflow activity) "Starts an interactive session: a sandbox with the workflow's agent that you work in yourself. Open it as soon as it's ready; it reports no result."
    :else "Starts a run: the agent works alone in its own sandbox and reports a result, which you can follow on the run page."))

(defn runs-as-text
  "Whose credentials the new run or session works with."
  [me team-id team-session?]
  (if team-session?
    (str "Team " (roles/team-name me team-id) ". Credentials: " (roles/credential-order me team-id nil) "; never yours.")
    (str "You (" (:sub me) "). Credentials: " (roles/credential-order me team-id "your") ".")))

(defn team-text
  [me team-id]
  (str (roles/team-name me team-id) " (" team-id ")"
       (case (roles/role-in-team me team-id)
         :admin " · you are an admin"
         :member " · you are a member"
         :tenant-admin " · as tenant admin, not a member"
         "")))

(defn sharing-help
  [{:keys [team-session? visibility default-visibility team-id]} me]
  (str (if team-session?
         "Only the team's members can work in it. Others who can see it may fork it."
         (str "Who may see the agent's session. The run and its result are always visible to "
              (format/possessive (roles/team-name me team-id)) " members."))
       (when (and (= :tenant default-visibility) (= :tenant visibility))
         (str " This workflow shares with everyone in " (:tenant me) " unless you choose otherwise."))))

(defn- visibility-select
  [{:keys [team-session? visibility team-id] :as dialog} me]
  [layout/select {:fullWidth true
                  :id (el/id els/run-dialog-visibility)
                  :helperText (sharing-help dialog me)
                  :value visibility
                  :options (for [option (roles/visibility-options team-session?)]
                             [option (roles/visibility-label option {:tenant (:tenant me)
                                                                     :team-name (roles/team-name me team-id)
                                                                     :owner? true})])
                  :option-el-id els/run-dialog-visibility-option
                  :on-change #(rf/dispatch [::set-visibility (keyword %)])}])

(defn- context-summary
  [{:keys [team-id team-session?] :as dialog} me]
  [RawBox {:component "dl" :sx (:context-summary theme/sx) :el-id els/run-dialog-context}
   [RawTypography {:component "dt" :variant "body2" :sx (:context-label theme/sx)} "Team"]
   [RawTypography {:component "dd" :variant "body2" :el-id els/run-dialog-team :data-team team-id}
    (team-text me team-id)]
   [RawTypography {:component "dt" :variant "body2" :sx (:context-label theme/sx)} "Runs as"]
   [RawTypography {:component "dd" :variant "body2" :el-id els/run-dialog-runs-as
                   :data-scope (if team-session? "team" "person")}
    (runs-as-text me team-id team-session?)]
   [RawTypography {:component "dt" :variant "body2" :sx (:context-label theme/sx)} "Shared with"]
   [RawBox {:component "dd"}
    [visibility-select dialog me]]])

(defn missing-credentials
  "Names of the credentials the workflow's harness needs from the person
   that nobody provides."
  [harnesses team-id workflow]
  (let [harness-id
        (get-in workflow [:spec :harness])]
    (some->> harnesses
             (some #(when (and (= team-id (:team-id %)) (= harness-id (:id %))) %))
             :credentials
             (remove :source)
             (mapv :name))))

(defn- credentials-warning
  [{:keys [team-id workflow team-session?]}]
  [:<>
   [data/poll {:key :harnesses :query {:query-id :list-harnesses}}]
   [scope/missing-alert {:names (when-not team-session?
                                  (missing-credentials @(rf/subscribe [::data/data :harnesses]) team-id workflow))
                         :purpose "this workflow's tools"
                         :el-id els/run-dialog-missing-credentials
                         :add-el-id els/run-dialog-add-credential
                         :on-leave #(rf/dispatch [::close])}]])

(defn run-dialog
  []
  (let [{:keys [workflow activity inputs pull-requests pull-requests-error nonce team-session? team-id] :as dialog}
        @(rf/subscribe [::dialog])

        me
        @(rf/subscribe [::session/me])

        busy?
        @(rf/subscribe [::data/busy? ::start-run])

        error
        @(rf/subscribe [::data/error ::start-run])

        interactive?
        (interactive? workflow activity)

        spec
        (:spec workflow)

        close
        #(when-not busy? (rf/dispatch [::close]))]
    [:> Dialog {:open (some? dialog) :on-close close :fullWidth true :maxWidth "sm"
                :data-testid (el/id els/run-dialog)}
     (when dialog
       [:form {:on-submit (fn [e]
                            (.preventDefault e)
                            (rf/dispatch [::submit]))}
        [:> DialogTitle (str (when team-session? "Team session: ") (:name spec) " · " (roles/team-name me team-id))]
        [:> DialogContent
         [RawStack {:spacing 2.5}
          [context-summary dialog me]
          [credentials-warning dialog]
          [RawTypography {:variant "body2" :color "text.secondary"}
           (str (if (string/blank? (:description spec)) (:description activity) (:description spec))
                " "
                (dialog-intro dialog))]
          ^{:key nonce}
          [schema-form/schema-form {:schema (inputs-schema workflow activity)
                                    :value inputs
                                    :on-change #(rf/dispatch [::set-input %1 %2])
                                    :pull-requests pull-requests
                                    :scm-provider (scm-provider workflow)}]
          (when pull-requests-error
            [RawAlert {:severity "warning" :el-id els/run-dialog-pull-requests-error}
             (str "Could not list open pull requests, so enter the number by hand. " pull-requests-error)])
          (when error
            [RawAlert {:severity "error" :el-id els/run-dialog-error} error])]]
        [:> DialogActions
         [:> Button {:on-click close :disabled busy? :data-testid (el/id els/run-dialog-cancel)} "Cancel"]
         [:> Button {:type "submit" :variant "contained" :loading busy?
                     :startIcon (r/as-element [:> (if interactive? TerminalIcon PlayArrowIcon)])
                     :data-testid (el/id els/run-dialog-submit)}
          (cond team-session? "Start team session" interactive? "Start session" :else "Run")]]])]))

(defn start-tooltip
  [me team-id automatic?]
  (string/join " " (remove string/blank?
                           [(when (= :tenant-admin (roles/role-in-team me team-id))
                              (let [team (roles/team-name me team-id)]
                                (str "Runs in " team " as you, as tenant admin; " (format/possessive team) " credentials apply where you have none.")))
                            (when automatic? "Also runs automatically. You can start it by hand too.")])))

(defn- workflow-row
  [catalog {:keys [team-id team-name workflow]}]
  (let [spec
        (:spec workflow)

        meta
        (chips/activity-meta (:activity spec))

        activity
        (find-activity catalog (:activity spec))

        interactive?
        (interactive? workflow activity)

        automatic?
        (and (:trigger spec) (not= :manual (format/kw (get-in spec [:trigger :type]))))

        team-session?
        (and interactive? (:valid workflow) (roles/team-admin? @(rf/subscribe [::session/me]) team-id))

        open
        (fn [team-session?]
          #(rf/dispatch [::open {:team-id team-id :workflow workflow :activity activity
                                 :team-name team-name :team-session? team-session?}]))]
    [layout/list-row {}
     [layout/icon-tile (:icon meta)]
     [RawBox {:sx (:grow theme/sx)}
      [RawTypography {:variant "h3"} (or (:name spec) (:id workflow))]
      [RawTypography {:variant "body2" :color "text.secondary"} (:description spec)]
      [:> Typography {:variant "caption" :color "text.secondary"}
       (string/join " · " (remove string/blank? [(:label meta)
                                                 (when spec (format/workflow-trigger spec))]))
       (when-let [project (:project spec)]
         [:<> " · "
          [layout/link {:page :project :params {:team-id team-id :project-id project} :color "inherit"
                        :el-id (els/workflow-project team-id (:id workflow))}
           (str "Project " project)]])]
      (when-not (:valid workflow)
        [RawBox {:sx (:spaced-top theme/sx)}
         [layout/definition-errors workflow]])]
     [RawStack {:direction "row" :spacing 1 :sx (:no-shrink theme/sx)}
      (when team-session?
        [:> Tooltip {:title "A session owned by the team, working with the team's credentials"}
         [:> Button {:variant "outlined"
                     :startIcon (r/as-element [:> GroupsIcon])
                     :data-testid (el/id (els/workflow-team-session team-id (:id workflow)))
                     :on-click (open true)}
          "Team session"]])
      [:> Tooltip {:title (start-tooltip @(rf/subscribe [::session/me]) team-id automatic?)}
       [:span
        [:> Button {:variant (if interactive? "contained" "outlined")
                    :startIcon (r/as-element [:> (if interactive? TerminalIcon PlayArrowIcon)])
                    :disabled (or (not (:valid workflow)) (nil? activity))
                    :data-testid (el/id (els/workflow-start team-id (:id workflow)))
                    :on-click (open false)}
         (if interactive? "Start session" "Run")]]]]]))

(defn workflow-list
  [{:keys [workflows catalog show-team? show-invalid?] :or {show-invalid? true}}]
  (let [shown
        (filter #(or show-invalid? (get-in % [:workflow :valid])) workflows)

        teams
        (distinct (map (juxt :team-id :team-name) shown))

        row
        (fn [item]
          ^{:key (str (:team-id item) "/" (get-in item [:workflow :id]))}
          [workflow-row catalog item])]
    (cond
      (empty? shown)
      [layout/empty-state {:title "No workflows" :el-id els/workflows-empty}
       "Teams add workflows as JSON files in their repository."]

      (not show-team?)
      [:<>
       (into [:> Paper] (map row shown))
       [run-dialog]]

      :else
      [RawStack {:spacing 2}
       (for [[team-id team-name] teams]
         ^{:key team-id}
         [RawBox {}
          [chips/team-heading team-id team-name {:link-el-id (els/workflow-team-link team-id)
                                                 :role-el-id (els/workflow-team-role team-id)}]
          (into [:> Paper] (map row (filter #(= team-id (:team-id %)) shown)))])
       [run-dialog]])))
