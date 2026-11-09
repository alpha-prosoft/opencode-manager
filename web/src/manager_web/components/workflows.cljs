(ns manager-web.components.workflows
  "Workflow lists with their run dialog."
  (:require ["@mui/icons-material/PlayArrow" :default PlayArrowIcon]
            ["@mui/icons-material/Terminal" :default TerminalIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/DialogActions" :default DialogActions]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogTitle" :default DialogTitle]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Tooltip" :default Tooltip]
            [clojure.string :as string]
            [manager-web.api :as api]
            [manager-web.components.chips :as chips]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.components.schema-form :as schema-form]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
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

(rf/reg-event-fx
 ::open
 (fn [{:keys [db]} [_ {:keys [team-id workflow activity]}]]
   {:db (-> db
            (assoc ::dialog {:team-id team-id :workflow workflow :activity activity
                             :inputs {} :pull-requests [] :nonce (random-uuid)})
            (data/clear-error ::start-run))
    :fx [(when (schema-form/takes-pull-request? (inputs-schema workflow activity))
           [::api/call {:query {:query-id :list-open-pull-requests
                                :team-id team-id
                                :workflow-id (:id workflow)}
                        :on-success [::pull-requests-loaded]
                        :on-failure [::pull-requests-failed]}])]}))

(rf/reg-event-db
 ::pull-requests-loaded
 (fn [db [_ pull-requests]]
   (if (::dialog db)
     (assoc-in db [::dialog :pull-requests] (vec pull-requests))
     db)))

(rf/reg-event-db
 ::pull-requests-failed
 (fn [db [_ message]]
   (if (::dialog db)
     (assoc-in db [::dialog :pull-requests-error] message)
     db)))

(rf/reg-event-db
 ::close
 (fn [db _]
   (dissoc db ::dialog)))

(rf/reg-event-db
 ::set-input
 (fn [db [_ field value]]
   (update-in db [::dialog :inputs] schema-form/set-input field value)))

(defn start-run-spec
  [{:keys [team-id workflow inputs]} run-id]
  {:id ::start-run
   :command {:cmd-id :start-run
             :id run-id
             :attrs {:team-id team-id
                     :workflow-id (:id workflow)
                     :inputs inputs}}
   :navigate [:run {:run-id (str run-id)}]
   :on-success [::close]})

(rf/reg-event-fx
 ::submit
 (fn [{:keys [db]} _]
   {:fx [[:dispatch [::data/run (start-run-spec (::dialog db) (random-uuid))]]]}))

(rf/reg-sub ::dialog (fn [db _] (::dialog db)))

(defn run-dialog
  []
  (let [{:keys [workflow activity inputs pull-requests pull-requests-error nonce] :as dialog}
        @(rf/subscribe [::dialog])

        busy?
        @(rf/subscribe [::data/busy? ::start-run])

        error
        @(rf/subscribe [::data/error ::start-run])

        interactive?
        (:interactive activity)

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
        [:> DialogTitle (:name spec)]
        [:> DialogContent
         [RawStack {:spacing 2.5}
          [RawTypography {:variant "body2" :color "text.secondary"}
           (str (if (string/blank? (:description spec)) (:description activity) (:description spec))
                " "
                (if interactive?
                  "A private sandbox starts with the workflow's agent; you can open it as soon as it's ready."
                  "The agent works in its own sandbox and reports a result. You can follow it on the run page."))]
          ^{:key nonce}
          [schema-form/schema-form {:schema (inputs-schema workflow activity)
                                    :value inputs
                                    :on-change #(rf/dispatch [::set-input %1 %2])
                                    :pull-requests pull-requests}]
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
          (if interactive? "Start session" "Run")]]])]))

(defn- workflow-row
  [catalog {:keys [team-id team-name workflow]}]
  (let [spec
        (:spec workflow)

        meta
        (chips/activity-meta (:activity spec))

        activity
        (find-activity catalog (:activity spec))

        interactive?
        (:interactive activity)

        automatic?
        (and (:trigger spec) (not= :manual (format/kw (get-in spec [:trigger :type]))))]
    [layout/list-row {}
     [layout/icon-tile (:icon meta)]
     [RawBox {:sx (:grow theme/sx)}
      [RawTypography {:variant "h3"} (or (:name spec) (:id workflow))]
      [RawTypography {:variant "body2" :color "text.secondary"} (:description spec)]
      [RawTypography {:variant "caption" :color "text.secondary"}
       (string/join " · " (remove string/blank? [(:label meta) (when spec (format/workflow-trigger spec))]))]
      (when-not (:valid workflow)
        [RawBox {:sx (:spaced-top theme/sx)}
         [layout/definition-errors workflow]])]
     [:> Tooltip {:title (if automatic? "Also runs automatically. You can start it by hand too." "")}
      [:span
       [:> Button {:variant (if interactive? "contained" "outlined")
                   :startIcon (r/as-element [:> (if interactive? TerminalIcon PlayArrowIcon)])
                   :disabled (or (not (:valid workflow)) (nil? activity))
                   :sx (:no-shrink theme/sx)
                   :data-testid (el/id (els/workflow-start team-id (:id workflow)))
                   :on-click #(rf/dispatch [::open {:team-id team-id :workflow workflow :activity activity
                                                    :team-name team-name}])}
        (if interactive? "Start session" "Run")]]]]))

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
          [layout/link {:page :team :params {:team-id team-id} :variant "subtitle2" :color "text.secondary"
                        :sx (:team-group-link theme/sx) :el-id (els/workflow-team-link team-id)}
           team-name]
          (into [:> Paper] (map row (filter #(= team-id (:team-id %)) shown)))])
       [run-dialog]])))
