(ns manager-web.project.views
  "A project of a team: its repository and CI job, the workflows that work
   on it, its runs, sessions, open changes and builds."
  (:require ["@mui/icons-material/SourceOutlined" :default ProjectIcon]
            ["@mui/material/Chip" :default Chip]
            [edd.subs :as edd-subs]
            [manager-web.components.build :as build]
            [manager-web.components.chips :as chips]
            [manager-web.components.layout :as layout]
            [manager-web.components.project-card :as project-card]
            [manager-web.components.runs-table :as runs-table]
            [manager-web.components.sessions-table :as sessions-table]
            [manager-web.components.workflows :as workflows]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.project.elements :as els]
            [manager-web.project.events :as events]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [manager-web.webhooks.views :as webhooks]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(defn- runs-key
  [team-id project-id]
  [:project-runs team-id project-id])

(defn- project-runs
  [team-id project-id render]
  [data/loaded {:key (runs-key team-id project-id)
                :query (runs-table/query team-id project-id)
                :interval 10000
                :error-el-id els/runs-error}
   render])

(defn- runs-tab
  [team-id project-id]
  [project-runs team-id project-id
   (fn [runs]
     [:<>
      [runs-table/runs-table {:runs runs :show-team? false
                              :empty "Nobody has run a workflow of this project yet."}]
      [runs-table/cut-note runs]])])

(defn- sessions-tab
  [team-id project-id]
  (let [action-error
        @(rf/subscribe [::data/error ::session-action])

        key
        [:project-sessions team-id project-id]]
    [:<>
     [layout/error-alert action-error els/sessions-action-error]
     [data/loaded {:key key
                   :query {:query-id :list-sessions :team-id team-id :project-id project-id}
                   :interval 10000
                   :error-el-id els/sessions-error}
      #(vector sessions-table/sessions-table {:sessions %
                                              :show-team? false
                                              :empty "No session of this project is shared with you."
                                              :actions {:error-id ::session-action :refetch [key]}})]]))

(defn- change-table
  [provider changes]
  (if (empty? changes)
    [layout/empty-state {:title "No open changes" :el-id els/changes-empty}
     "Nothing is waiting for review in this repository."]
    [layout/table {:el-id els/changes}
     (layout/head "Change" "Author" "Branch")
     (for [{:keys [id author branch] :as change} changes]
       ^{:key (str id)}
       [layout/table-row {:data-testid (el/id (els/change id))}
        [layout/cell {:sx (:strong theme/sx)} (events/change-label provider change)]
        [layout/cell author]
        [layout/cell [layout/mono branch]]])]))

(defn- changes-tab
  [{:keys [team project changes-workflow]}]
  (if changes-workflow
    [data/loaded {:key [:project-changes (:id team) (:id project)]
                  :query {:query-id :list-open-pull-requests :team-id (:id team) :workflow-id changes-workflow}
                  :error-el-id els/changes-error}
     #(vector change-table (get-in project [:scm :provider]) %)]
    [layout/empty-state {:title "Changes aren't read here" :el-id els/changes-empty}
     "No review or implementation workflow of the team works on this project, so the platform doesn't read its open changes."]))

(defn- build-table
  [runs]
  (let [builds
        (events/builds runs)]
    (if (empty? builds)
      [layout/empty-state {:title "No builds yet" :el-id els/builds-empty}
       "Builds of the changes the platform delivers appear here once CI checks them."]
      [layout/table {:el-id els/builds}
       (layout/head "Run" "Build" "Started")
       (for [{:keys [id outcome] :as run} builds
             :let [{:keys [state number]} (:build outcome)]]
         ^{:key (str id)}
         [layout/table-row {:data-testid (el/id (els/build id))}
          [layout/cell [layout/link {:page :run :params {:run-id id} :el-id (els/build-run id)} (format/run-title run)]]
          [layout/cell [layout/row [build/build-chip state] (when number (str "#" number))]]
          [layout/cell {:sx (:nowrap theme/sx)} (format/relative-time (:created-at run))]])])))

(defn- builds-tab
  [team-id project-id]
  [project-runs team-id project-id build-table])

(defn- header
  [{:keys [team project]}]
  (let [ci
        (project-card/ci-label project)

        me
        @(rf/subscribe [::session/me])]
    [layout/page-header {:crumbs [layout/entity-crumbs me {:team-id (:id team)
                                                           :label (:name project)
                                                           :els {:crumbs els/crumbs :tenant els/crumb-tenant
                                                                 :team els/crumb-team}}]
                         :title (:name project)
                         :title-el-id els/title
                         :el-id els/page
                         :actions [[layout/external-button {:href (:repository-url project) :variant "outlined"
                                                            :el-id els/repository-link}
                                    "Open repository"]]
                         :meta [[chips/team-chip (:id team) (el/id els/team-chip)]
                                [:> Chip {:icon (r/as-element [:> ProjectIcon]) :data-testid (el/id els/repository)
                                          :label (r/as-element [layout/mono (project-card/repository-label project)])}]
                                [:> Chip {:label (str "branch " (:branch project)) :data-testid (el/id els/branch)}]
                                (when ci
                                  [:> Chip {:label ci :data-testid (el/id els/ci)}])]}]))

(defn- project-view
  [{:keys [team project] :as found}]
  (let [tab
        (data/tab-of events/tabs (:tab @(rf/subscribe [::edd-subs/url-params])))

        catalog
        @(rf/subscribe [::data/data :catalog])]
    [layout/page-stack
     [data/poll {:key :catalog :query {:query-id :list-activities}}]
     [header found]
     [layout/tabs {:value tab
                   :tabs events/tabs
                   :on-change #(layout/navigate! :project {:team-id (:id team) :project-id (:id project)} {:tab (name %)})
                   :tab-el-id els/tab}]
     (case tab
       :workflows [workflows/workflow-list {:workflows (events/workflow-items found) :catalog (or catalog [])}]
       :runs [runs-tab (:id team) (:id project)]
       :sessions [sessions-tab (:id team) (:id project)]
       :changes [changes-tab found]
       :builds [builds-tab (:id team) (:id project)]
       :webhooks [webhooks/webhooks-section {:team-id (:id team) :project-id (:id project)}]
       nil)]))

(defn main-panel
  []
  (when-let [{:keys [team-id project-id]}
             @(rf/subscribe [::data/ui :project :ids])]
    [data/loaded {:key [:project team-id project-id]
                  :query {:query-id :get-project :team-id team-id :project-id project-id}
                  :error-el-id els/error
                  :failed [layout/way-back {:page :projects :label "All projects" :el-id els/back-to-list}]}
     project-view]))
