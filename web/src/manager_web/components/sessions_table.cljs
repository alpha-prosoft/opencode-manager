(ns manager-web.components.sessions-table
  (:require ["@mui/material/Chip" :default Chip]
            ["@mui/material/Typography" :default Typography]
            [clojure.string :as string]
            [manager-web.components.chips :as chips]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.components.session-actions :as session-actions]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]))

(defn team-session?
  "Sessions the platform owns for a team (started by :start-team-session, a
   trigger or a schedule) work with the team's credentials."
  [session]
  (true? (:team-session session)))

(defn owner-label
  [me session]
  (roles/session-owner me session))

(defn context-line
  "What the wide columns say, as one line for narrow screens: team, owner and
   who sees it."
  [me session show-team?]
  (string/join " · " (remove string/blank? [(when show-team? (roles/team-name me (:team-id session)))
                                            (owner-label me session)
                                            (roles/session-visibility-label me session)])))

(defn project-link
  "The session's project: a link for members of its team, else its name."
  [me {:keys [id team-id project]}]
  (when project
    [:> Typography {:variant "caption" :color "text.secondary" :component "div"}
     "Project "
     (if (roles/can-open-team? me team-id)
       [layout/link {:page :project :params {:team-id team-id :project-id project} :color "inherit"
                     :el-id (els/session-project id)}
        project]
       [:span {:data-testid (el/id (els/session-project id))} project])]))

(defn sessions-table
  "A table of sessions, or an empty state saying `empty`. actions are the
   session-actions opts."
  [{:keys [sessions empty empty-title actions show-team?] :or {show-team? true}}]
  (let [me
        @(rf/subscribe [::session/me])

        wide
        {:sx (:wide-only theme/sx)}]
    (if (empty? sessions)
      [layout/empty-state {:title (or empty-title "No sessions") :el-id els/sessions-empty}
       (or empty "Start a workspace from Home, or run a workflow.")]
      [layout/table {:el-id els/sessions-table}
       (apply layout/head (remove nil? ["Session" (when show-team? ["Team" wide]) ["Activity" wide] ["Owner" wide]
                                        ["Shared with" wide] "Status" ["Last activity" wide] ""]))
       (for [session sessions
             :let [id (:id session)]]
         ^{:key (str id)}
         [layout/table-row {:hover true}
          [layout/cell
           [layout/link {:page :session :params {:session-id id} :sx (:strong-link theme/sx)
                         :el-id (els/session-link id)}
            (:name session)]
           [:> Typography {:variant "caption" :color "text.secondary" :component "div" :sx (:row-context theme/sx)
                           :data-testid (el/id (els/session-context id))}
            [:> (chips/visibility-icon (:visibility session)) {:sx (:inline-icon theme/sx)}]
            (context-line me session show-team?)]
           [project-link me session]]
          (when show-team?
            [layout/cell {:sx (:wide-only-nowrap theme/sx)}
             [:span {:data-testid (el/id (els/session-team id)) :data-team (:team-id session)}
              (roles/team-name me (:team-id session))]])
          [layout/cell wide
           (when (:activity session) [chips/activity-chip (:activity session)])]
          [layout/cell wide
           (if (team-session? session)
             [:> Chip {:color "primary" :label (owner-label me session)
                       :data-testid (el/id (els/session-owner id)) :data-owner "team"}]
             [:span {:data-testid (el/id (els/session-owner id))
                     :data-owner (if (:is-owner session) "you" (:sub session))}
              (owner-label me session)])]
          [layout/cell wide [chips/session-visibility-chip session (el/id (els/session-sharing id))]]
          [layout/cell [chips/session-status-chip (:status session)]]
          [layout/cell {:sx (:wide-only-nowrap theme/sx)}
           (format/relative-time (:last-activity-at session))]
          [layout/cell [session-actions/session-actions session actions]]])])))
