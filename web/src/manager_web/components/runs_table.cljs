(ns manager-web.components.runs-table
  (:require ["@mui/material/Chip" :default Chip]
            [manager-web.components.build :as build]
            [manager-web.components.chips :as chips]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [manager-web.urls :as urls]
            [web.primitives.components :refer [RawBox RawTypography]]))

(defn- pull-request-label
  [{:keys [id updated draft]}]
  (str (cond updated "Pushed to" draft "Draft" :else "PR") " #" id))

(defn- pull-request-chip
  [run-id pull-request]
  (let [props
        {:label (pull-request-label pull-request)
         :data-testid (el/id (els/run-pull-request run-id))}]
    [:> Chip (if-let [url
                      (urls/safe-url (:url pull-request))]
               (assoc props :component "a" :href url :target "_blank" :rel "noopener noreferrer" :clickable true)
               props)]))

(defn- outcome
  [run-id {:keys [pull-request build verdict]}]
  [RawBox {:sx (:outcome theme/sx)}
   [layout/row
    (when pull-request
      [pull-request-chip run-id pull-request])
    (when build
      [build/build-chip (:state build)])
    (when verdict
      [chips/status-chip :verdict verdict])]])

(defn runs-table
  "A table of runs, or an empty state saying `empty`."
  [{:keys [runs show-team? empty]
    :or {show-team? true
         empty "Runs appear here once you run a workflow."}}]
  (let [wide
        {:sx (:wide-only theme/sx)}]
    (if (empty? runs)
      [layout/empty-state {:title "No runs yet" :el-id els/runs-empty} empty]
      [layout/table {:el-id els/runs-table}
       (apply layout/head (remove nil? ["Workflow" ["Activity" wide] (when show-team? ["Team" wide]) "Status" "Started"]))
       (for [run runs]
         ^{:key (str (:id run))}
         [layout/table-row {:hover true}
          [layout/cell
           [layout/link {:page :run :params {:run-id (:id run)} :sx (:strong-link theme/sx)
                         :el-id (els/run-link (:id run))}
            (or (:workflow-name run) (:workflow-id run))]
           [RawTypography {:variant "body2" :color "text.secondary"} (format/trigger-label (:trigger run))]
           (when (:outcome run)
             [outcome (:id run) (:outcome run)])]
          [layout/cell wide [chips/activity-chip (:activity run)]]
          (when show-team? [layout/cell wide (:team-id run)])
          [layout/cell [chips/run-status-chip (:status run)]]
          [layout/cell {:sx (:nowrap theme/sx)} (format/relative-time (:created-at run))]])])))
