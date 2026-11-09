(ns manager-web.run.views
  (:require ["@mui/icons-material/Replay" :default ReplayIcon]
            ["@mui/icons-material/Stop" :default StopIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/List" :default List]
            ["@mui/material/ListItem" :default ListItem]
            ["@mui/material/ListItemText" :default ListItemText]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Step" :default Step]
            ["@mui/material/StepLabel" :default StepLabel]
            ["@mui/material/Stepper" :default Stepper]
            [clojure.string :as string]
            [manager-web.components.build :as build]
            [manager-web.components.chips :as chips]
            [manager-web.components.layout :as layout]
            [manager-web.components.markdown :as markdown]
            [manager-web.components.patch :as patch]
            [manager-web.components.review :as review]
            [manager-web.components.team-context :as team-context]
            [manager-web.components.usage :as usage]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.run.elements :as els]
            [manager-web.roles :as roles]
            [manager-web.run.events :as events]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(def automated-steps ["Queued" "Starting sandbox" "Agent working" "Collecting result" "Done"])
(def ci-steps ["Queued" "Starting sandbox" "Agent working" "Collecting result" "Following build" "Done"])
(def session-steps ["Queued" "Starting sandbox" "Ready"])

(def step-of
  {:queued 0
   :preparing 1
   :running 2
   :awaiting-result 2
   :finalizing 3
   :following-ci 4})

(defn- session-activity?
  [run]
  (= :session (format/kw (:activity run))))

(defn progress-steps
  "The step labels and the active step of a run."
  [run]
  (let [status
        (format/kw (:status run))

        step
        (get step-of status 0)]
    (cond
      (session-activity? run) [session-steps (min step 1)]
      (or (= :following-ci status) (get-in run [:result :ci])) [ci-steps step]
      :else [automated-steps step])))

(defn- progress
  [run]
  (let [[steps step]
        (progress-steps run)

        status
        (format/kw (:status run))]
    [:> Paper {:sx (:panel-roomy theme/sx) :data-testid (el/id els/progress)}
     (into [:> Stepper {:activeStep step :alternativeLabel true}]
           (for [label steps]
             ^{:key label}
             [:> Step [:> StepLabel label]]))
     [RawTypography {:variant "body2" :color "text.secondary" :sx (:progress-note theme/sx)}
      (case status
        :awaiting-result "The agent is working in its sandbox. You can watch it live with Open session. This page updates by itself."
        :following-ci (str (if (format/gerrit? (:scm-provider run)) "The change is pushed for review." "The pull request is pushed.")
                           " The platform checks its build every minute until it passes or fails.")
        "This page updates by itself.")]]))

(defn pull-request-lead
  "The words before the link to a delivered pull request or Gerrit change."
  [scm-provider {:keys [updated draft]}]
  (if (format/gerrit? scm-provider)
    (if updated "New patch set on change " "Change ")
    (cond updated "Pushed to pull request " draft "Draft pull request " :else "Pull request ")))

(defn- pull-request-alert
  [scm-provider {:keys [updated draft url id branch target] :as pull-request}]
  (let [gerrit?
        (format/gerrit? scm-provider)]
    [RawAlert {:severity (if draft "warning" "success") :el-id els/pull-request}
     [:<>
      (pull-request-lead scm-provider pull-request)
      [layout/external-link {:href url :el-id els/pull-request-link} (if gerrit? (str id) (str "#" id))]
      (cond
        gerrit? (if updated " (" " pushed for review (")
        updated " ("
        :else " opened (")
      (when gerrit? "Patch set ")
      [:span {:data-testid (el/id els/pull-request-ref)} [layout/mono branch]]
      " into " [layout/mono target] "). It is not merged."
      (when draft " It's a draft because the agent couldn't verify the change.")]]))

(defn- implementation
  [run]
  (let [{:keys [implementation files pull-request] :as result}
        (:result run)

        {:keys [summary changes tests notes]}
        implementation]
    [RawStack {:spacing 2}
     (when pull-request
       [pull-request-alert (:scm-provider run) pull-request])
     [markdown/markdown summary]
     (when (seq changes)
       [RawBox {}
        [RawTypography {:variant "subtitle2"} "Changes"]
        (into [:> List {:dense true :disablePadding true}]
              (for [change changes]
                ^{:key change}
                [:> ListItem {:disableGutters true}
                 [:> ListItemText {:primary (str "• " change)}]]))])
     (when (seq tests)
       [RawAlert {:severity "info"}
        [:<> [:strong "How the agent verified it:"] (str " " (string/join "; " tests))]])
     (when-not (string/blank? notes)
       [RawAlert {:severity "warning"} notes])
     [RawTypography {:variant "subtitle2"}
      (str "Patch: " (format/plural (count files) "file") " changed")]
     [patch/patch-view {:patch (:patch result) :file-name (str "run-" (:id run) ".patch")}]]))

(defn- result-view
  [run]
  (case (format/kw (:activity run))
    :review [review/review-result (:result run) (:scm-provider run)]
    :analysis [markdown/markdown (get-in run [:result :document :markdown])]
    :implement [implementation run]
    nil))

(defn- input-items
  [inputs]
  (let [items
        (for [[key value] inputs
              :when (and (some? value) (not= "" value))]
          [(format/words key) (str value)])]
    (if (seq items) items [["" "No inputs"]])))

(defn started-by
  "Who a run acts for: the person, or the platform for runs it started on its own."
  [{:keys [platform-started created-by]}]
  (if platform-started
    "The platform"
    created-by))

(defn runs-as
  "Whose credentials a run works with."
  [me {:keys [platform-started created-by team-id]}]
  (cond
    platform-started (str "Team " (roles/team-name me team-id) ": " (roles/credential-order me team-id nil))
    (= created-by (:sub me)) (str "You: " (roles/credential-order me team-id "your"))
    :else (str created-by ": " (roles/credential-order me team-id "their"))))

(defn may-cancel?
  "The person who started a run and the admins of its team may cancel it."
  [me {:keys [platform-started created-by team-id]}]
  (or (and (not platform-started) (= created-by (:sub me)))
      (roles/team-admin? me team-id)))

(defn- detail-items
  [me {:keys [id team-id created-at session project session-visibility] :as run}]
  (cond-> [["Team" [layout/link {:page :team :params {:team-id team-id} :el-id els/team-link
                                 :attrs {:data-team team-id}}
                    (roles/team-name me team-id)]]
           ["Started by" [:span {:data-testid (el/id els/started-by)} (started-by run)]]
           ["Runs as" [:span {:data-testid (el/id els/runs-as)} (runs-as me run)]]
           ["Started" (format/date-time created-at)]
           ["Run ID" [layout/mono (str id)]]]
    session-visibility (conj ["Session shared with"
                              (roles/visibility-label session-visibility
                                                      {:tenant (:tenant me) :team-name (roles/team-name me team-id)
                                                       :owner (:created-by run) :owner? (= (:sub me) (:created-by run))})])
    project (conj ["Project" [layout/link {:page :project :params {:team-id team-id :project-id project} :el-id els/project}
                              project]])
    session (conj ["Session" [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
                              [layout/link {:page :session :params {:session-id (:id session)} :el-id els/session-link}
                               (:name session)]
                              [chips/session-status-chip (:status session)]]])))

(defn- session-action
  "Into the sandbox for those who may work in it; the others get its page,
   which says why and offers a fork."
  [run]
  (let [session
        (:session run)]
    (if (:can-open session)
      [layout/external-button {:href (:open-url session)
                               :variant (if (session-activity? run) "contained" "outlined")
                               :el-id els/open-session}
       "Open session"]
      [:> Button (merge (layout/nav-props :session {:session-id (:id session)})
                        {:variant "outlined" :data-testid (el/id els/view-session)})
       "View session"])))

(defn- actions
  [me run]
  (let [active?
        (format/run-active? (:status run))

        retrying?
        @(rf/subscribe [::data/busy? [::events/retry (:id run)]])]
    (cond-> []
      (:session run)
      (conj [session-action run])

      (and active? (may-cancel? me run))
      (conj [:> Button {:color "warning" :startIcon (r/as-element [:> StopIcon])
                        :data-testid (el/id els/cancel)
                        :on-click #(rf/dispatch [::events/cancel (:id run)])}
             "Cancel run"])

      (and (not active?) (not (session-activity? run)))
      (conj [:> Button {:startIcon (r/as-element [:> ReplayIcon]) :loading retrying?
                        :data-testid (el/id els/retry)
                        :on-click #(rf/dispatch [::events/retry (:id run)])}
             "Run again"]))))

(defn- run-view
  [run]
  (let [action-error
        @(rf/subscribe [::data/error ::events/action])

        status
        (format/kw (:status run))

        me
        @(rf/subscribe [::session/me])

        team-id
        (:team-id run)]
    [layout/page-stack
     [layout/page-header {:crumbs [layout/entity-crumbs me
                                   {:team-id team-id
                                    :project (:project run)
                                    :list {:label "Runs" :page :runs
                                           :query (team-context/query (when (roles/team-of me team-id) team-id))}
                                    :label (format/run-title run)
                                    :els {:crumbs els/crumbs :tenant els/crumb-tenant :team els/crumb-team
                                          :project els/crumb-project :list els/back}}]
                          :title (format/run-title run)
                          :title-el-id els/title
                          :el-id els/page
                          :meta [[chips/team-chip team-id (el/id els/team-chip)]
                                 [chips/run-status-chip (:status run) (el/id els/status)]
                                 [chips/activity-chip (:activity run)]
                                 [RawTypography {:variant "body2"}
                                  (str (format/trigger-label (:trigger run)) " · " (format/relative-time (:created-at run)))]]
                          :actions (seq (actions me run))}]
     (when (format/run-active? status)
       [progress run])
     (when-not (string/blank? (:error-message run))
       [RawAlert {:severity (if (= :cancelled status) "warning" "error") :el-id els/run-error}
        (if (= :cancelled status) (:error-message run) (str "The run failed: " (:error-message run)))])
     [layout/error-alert action-error els/action-error]
     (when (:result run)
       [layout/section {:title "Result" :el-id els/result}
        [:> Paper {:sx (:result-paper theme/sx)}
         [result-view run]]])
     (when-let [ci (get-in run [:result :ci])]
       [layout/section {:title "Build" :el-id els/build :description "Read from the CI system through its MCP server."}
        [build/build-panel ci]])
     [layout/two-columns
      [layout/section {:title "Inputs" :el-id els/inputs}
       [layout/detail-list (input-items (:inputs run))]]
      [layout/section {:title "Details" :el-id els/details}
       [layout/detail-list (detail-items me run)]]]
     [usage/usage-section (:usage run)]]))

(defn main-panel
  []
  (let [run-id
        @(rf/subscribe [::data/ui :run :run-id])

        back
        {:page :runs :label "All runs" :el-id els/back-to-list}]
    (cond
      (uuid? run-id)
      [data/loaded {:key [:run run-id]
                    :query {:query-id :get-run :run-id run-id}
                    :interval 4000
                    :poll-while #(format/run-active? (:status %))
                    :error-el-id els/error
                    :failed [layout/way-back back]}
       run-view]

      (some? run-id)
      [layout/not-found {:title "Run not found" :el-id els/not-found :back back}
       "No run has this address. It may have been mistyped."])))
