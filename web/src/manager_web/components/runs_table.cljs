(ns manager-web.components.runs-table
  (:require ["@mui/material/Chip" :default Chip]
            ["@mui/material/Typography" :default Typography]
            [manager-web.components.build :as build]
            [manager-web.components.chips :as chips]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.el :as el]
            [clojure.string :as string]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [manager-web.urls :as urls]
            [re-frame.core :as rf]
            [web.primitives.components :refer [RawBox]]))

(defn pull-request-label
  [scm-provider {:keys [id updated draft]}]
  (str (cond updated "Pushed to " draft "Draft " :else "") (format/change-name scm-provider id)))

(defn- pull-request-chip
  [run-id scm-provider pull-request]
  (let [props
        {:label (pull-request-label scm-provider pull-request)
         :data-testid (el/id (els/run-pull-request run-id))}]
    [:> Chip (if-let [url
                      (urls/safe-url (:url pull-request))]
               (assoc props :component "a" :href url :target "_blank" :rel "noopener noreferrer" :clickable true)
               props)]))

(defn- outcome
  [{:keys [id scm-provider]} {:keys [pull-request build verdict]}]
  [RawBox {:sx (:outcome theme/sx)}
   [layout/row
    (when pull-request
      [pull-request-chip id scm-provider pull-request])
    (when build
      [build/build-chip (:state build)])
    (when verdict
      [chips/status-chip :verdict verdict])]])

(defn context-line
  "The team and who sees the run's session, for narrow screens."
  [me run show-team?]
  (string/join " · " (remove string/blank? [(when show-team? (roles/team-name me (:team-id run)))
                                            (when-let [visibility (:session-visibility run)]
                                              (roles/visibility-label visibility {:tenant (:tenant me)
                                                                                  :team-name (roles/team-name me (:team-id run))
                                                                                  :owner (:created-by run)
                                                                                  :owner? (= (:sub me) (:created-by run))}))])))

(def limit
  "How many runs a list asks the service for."
  100)

(defn query
  "The newest runs of the person's teams, of team-id, or of its project."
  ([team-id]
   (query team-id nil))
  ([team-id project-id]
   (cond-> {:query-id :list-runs :limit limit}
     team-id (assoc :team-id team-id)
     project-id (assoc :project-id project-id))))

(defn cut?
  "True when the service may hold more runs than it returned."
  [runs]
  (<= limit (count runs)))

(defn cut-note
  "Says that a list of runs ends at the limit, not at the first run."
  [runs]
  (when (cut? runs)
    [:> Typography {:variant "caption" :color "text.secondary" :data-testid (el/id els/runs-cut)}
     (str "Showing the newest " limit " runs; older ones aren't listed.")]))

(defn runs-table
  "A table of runs, or an empty state saying `empty`."
  [{:keys [runs show-team? empty empty-title]
    :or {show-team? true
         empty "Runs appear here once you run a workflow."}}]
  (let [me
        @(rf/subscribe [::session/me])

        wide
        {:sx (:wide-only theme/sx)}]
    (if (empty? runs)
      [layout/empty-state {:title (or empty-title "No runs yet") :el-id els/runs-empty} empty]
      [layout/table {:el-id els/runs-table}
       (apply layout/head (remove nil? ["Run" ["Activity" wide] (when show-team? ["Team" wide]) "Status" "Started"]))
       (for [run runs
             :let [id (:id run)
                   context (context-line me run show-team?)]]
         ^{:key (str id)}
         [layout/table-row {:hover true}
          [layout/cell
           [layout/link {:page :run :params {:run-id id} :sx (:strong-link theme/sx)
                         :el-id (els/run-link id)}
            (format/run-title run)]
           [:> Typography {:variant "body2" :color "text.secondary"}
            (format/trigger-label (:trigger run))
            (when-let [project (:project run)]
              [:<> " · "
               [layout/link {:page :project :params {:team-id (:team-id run) :project-id project} :color "inherit"
                             :el-id (els/run-project id)}
                project]])]
           (when-not (string/blank? context)
             [:> Typography {:variant "caption" :color "text.secondary" :component "div" :sx (:row-context theme/sx)
                             :data-testid (el/id (els/run-context id))}
              (when (:session-visibility run)
                [:> (chips/visibility-icon (:session-visibility run)) {:sx (:inline-icon theme/sx)}])
              context])
           (when (:outcome run)
             [outcome run (:outcome run)])]
          [layout/cell wide [chips/activity-chip (:activity run)]]
          (when show-team?
            [layout/cell wide
             [:span {:data-testid (el/id (els/run-team id)) :data-team (:team-id run)}
              (roles/team-name me (:team-id run))]])
          [layout/cell [chips/run-status-chip (:status run)]]
          [layout/cell {:sx (:nowrap theme/sx)} (format/relative-time (:created-at run))]])])))
