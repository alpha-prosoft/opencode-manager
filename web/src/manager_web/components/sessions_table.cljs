(ns manager-web.components.sessions-table
  (:require [manager-web.components.chips :as chips]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.components.session-actions :as session-actions]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [web.primitives.components :refer [RawTypography]]))

(defn sessions-table
  "A table of sessions, or an empty state saying `empty`. actions are the
   session-actions opts."
  [{:keys [sessions empty actions]}]
  (let [wide
        {:sx (:wide-only theme/sx)}]
    (if (empty? sessions)
      [layout/empty-state {:title "No sessions" :el-id els/sessions-empty}
       (or empty "Start a workspace from Home, or run a workflow.")]
      [layout/table {:el-id els/sessions-table}
       (layout/head "Session" ["Activity" wide] ["Owner" wide] ["Shared with" wide] "Status"
                    ["Last activity" wide] "")
       (for [session sessions]
         ^{:key (str (:id session))}
         [layout/table-row {:hover true}
          [layout/cell
           [layout/link {:page :session :params {:session-id (:id session)} :sx (:strong-link theme/sx)
                         :el-id (els/session-link (:id session))}
            (:name session)]
           (when (:team-id session)
             [RawTypography {:variant "body2" :color "text.secondary"} (:team-id session)])]
          [layout/cell wide
           (when (:activity session) [chips/activity-chip (:activity session)])]
          [layout/cell wide (if (:is-owner session) "You" (:sub session))]
          [layout/cell wide [chips/visibility-chip (:visibility session)]]
          [layout/cell [chips/session-status-chip (:status session)]]
          [layout/cell {:sx (:wide-only-nowrap theme/sx)}
           (format/relative-time (:last-activity-at session))]
          [layout/cell [session-actions/session-actions session actions]]])])))
