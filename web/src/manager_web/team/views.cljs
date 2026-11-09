(ns manager-web.team.views
  (:require ["@mui/material/Chip" :default Chip]
            ["@mui/material/LinearProgress" :default LinearProgress]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Typography" :default Typography]
            [clojure.string :as string]
            [manager-web.components.chips :as chips]
            [manager-web.components.layout :as layout]
            [manager-web.components.runs-table :as runs-table]
            [manager-web.components.usage :as usage]
            [manager-web.components.workflows :as workflows]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.team.elements :as els]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(def permissions
  {"allow" "allowed"
   "ask" "asks first"
   "deny" "blocked"})

(def tabs
  [[:workflows "Workflows"]
   [:runs "Runs"]
   [:setup "Agents & harnesses"]
   [:people "People & usage"]])

(defn tools-text
  [permission-map]
  (if (seq permission-map)
    (string/join ", " (for [[tool value] permission-map
                            :let [value (some-> value name)]]
                        (str (name tool) " " (get permissions value value))))
    "OpenCode defaults"))

(defn- term
  [label & children]
  [:<>
   [RawTypography {:component "dt" :variant "label"} label]
   (into [RawBox {:component "dd" :sx (:definition-value theme/sx)}] children)])

(defn- harness-card
  [{:keys [id spec] :as harness}]
  (let [policy
        (concat (get-in spec [:policy :deny-commands]) (get-in spec [:policy :deny-paths]))]
    [:> Paper {:sx (:panel theme/sx) :data-testid (el/id (els/harness-card id))}
     [RawStack {:spacing 1.25}
      [layout/row
       [RawTypography {:variant "h3"} id]
       (when spec
         [chips/network-chip (:network spec)])]
      (when-not (string/blank? (:description spec))
        [RawTypography {:variant "body2" :color "text.secondary"} (:description spec)])
      [layout/definition-errors harness]
      (when spec
        [RawBox {:component "dl" :sx (:definitions theme/sx)}
         [term "Models" (string/join ", then " (get-in spec [:llm :models]))]
         [term "Tools" (tools-text (:permissions spec))]
         [term "Resources" (str (get-in spec [:resources :cpus]) " CPU, " (get-in spec [:resources :memory-mb])
                                " MB memory, stops after " (:idle-minutes spec) " idle min")]
         (when (seq policy)
           [term "Always blocked"
            (into [layout/row]
                  (for [rule policy]
                    ^{:key rule}
                    [:> Chip {:color "warning" :label (r/as-element [layout/mono rule])}]))])])]]))

(defn- agent-card
  [{:keys [id spec source] :as agent}]
  [:> Paper {:sx (:panel theme/sx) :data-testid (el/id (els/agent-card id))}
   [RawStack {:spacing 0.75}
    [RawTypography {:variant "h3"} id]
    (when-not (string/blank? (:description spec))
      [RawTypography {:variant "body2" :color "text.secondary"} (:description spec)])
    [:> Typography {:variant "caption" :color "text.secondary"}
     "Instructions in " [layout/mono (or (:prompt-file spec) source)]]
    [layout/definition-errors agent]]])

(defn- of-kind
  [team kind]
  (filter #(= kind (format/kw (:kind %))) (:definitions team)))

(defn- setup
  [team]
  [RawStack {:spacing 4}
   [layout/section {:title "Agents" :description "Who does the work: the instructions given to the model."}
    (into [RawBox {:sx (:definition-grid theme/sx)}]
          (for [agent (of-kind team :agents)]
            ^{:key (:id agent)}
            [agent-card agent]))]
   [layout/section {:title "Harnesses"
                    :description "Where the work happens: model, tools, network and limits of the sandbox. The agent cannot change these."}
    (into [RawBox {:sx (:definition-grid theme/sx)}]
          (for [harness (of-kind team :harnesses)]
            ^{:key (:id harness)}
            [harness-card harness]))]])

(defn budget-percent
  [{:keys [daily-tokens used-today]}]
  (if (and daily-tokens (pos? daily-tokens))
    (min 100 (/ (* 100 (or used-today 0)) daily-tokens))
    0))

(defn- people
  [{:keys [budget usage usage-by-model admins members secrets]}]
  (let [used
        (budget-percent budget)

        admin-set
        (set admins)]
    [RawStack {:spacing 4}
     (when (:daily-tokens budget)
       [layout/section {:title "Daily token budget"
                        :el-id els/budget
                        :description "When the team reaches it, agents can't call the LLM until midnight UTC."}
        [:> Paper {:sx (:panel theme/sx)}
         [:> LinearProgress {:variant "determinate" :value used :sx (:budget-bar theme/sx)
                             :color (cond (>= used 100) "error" (> used 80) "warning" :else "primary")}]
         [RawTypography {:variant "body2" :color "text.secondary" :sx (:spaced-top theme/sx)}
          (str (format/tokens (:used-today budget)) " of " (format/tokens (:daily-tokens budget)) " tokens used today")]]])
     [layout/section {:title "LLM usage" :description "Last 7 days, measured by the platform's gateway."}
      [usage/usage-stats usage]
      (when (seq usage-by-model)
        (let [right
              {:align "right"}]
          [layout/table {:el-id els/usage-by-model}
           (layout/head "Model" ["Calls" right] ["Prompt tokens" right] ["Completion tokens" right])
           (for [row usage-by-model]
             ^{:key (:model row)}
             [layout/table-row {}
              [layout/cell (:model row)]
              [layout/cell right (:calls row)]
              [layout/cell right (format/tokens (:prompt-tokens row))]
              [layout/cell right (format/tokens (:completion-tokens row))]])]))]
     [layout/section {:title "Members"
                      :el-id els/members
                      :description "Team admins can also stop, delete and re-share any team session. Membership is managed in the onboarding repository."}
      [layout/row
       (for [user admins]
         ^{:key (str "admin-" user)}
         [:> Chip {:color "primary" :label (str user " · admin")}])
       (for [user (remove admin-set members)]
         ^{:key user}
         [:> Chip {:label user}])]
      [RawTypography {:variant "body2" :color "text.secondary"}
       (str "Secrets this team's workflows may use: " (if (seq secrets) (string/join ", " secrets) "none"))]]]))

(defn- team-runs
  [team-id]
  [data/loaded {:key [:team-runs team-id]
                :query {:query-id :list-runs :team-id team-id}
                :interval 10000
                :error-el-id els/runs-error}
   #(vector runs-table/runs-table {:runs % :show-team? false :empty "Nobody has run this team's workflows yet."})])

(defn- team-view
  [team]
  (let [tab
        @(rf/subscribe [::data/ui :team :tab :workflows])

        catalog
        @(rf/subscribe [::data/data :catalog])]
    [layout/page-stack
     [data/poll {:key :catalog :query {:query-id :list-activities}}]
     [layout/page-header {:back {:page :teams :label "Teams" :el-id els/back}
                          :title (:name team)
                          :title-el-id els/title
                          :el-id els/page
                          :description (:description team)
                          :actions (when (:role team) [[chips/role-chip (:role team)]])
                          :meta [[:> Typography {:variant "caption"}
                                  (str (format/source-label team) " · version ")
                                  [layout/mono (format/short-commit (:commit team))]
                                  (str " · synced " (format/relative-time (:synced-at team)))]]}]
     (when (:error-message team)
       [RawAlert {:severity "error" :el-id els/sync-error} (str "Last sync failed: " (:error-message team))])
     [layout/tabs {:value tab
                   :tabs tabs
                   :on-change #(rf/dispatch [::data/set-ui :team :tab %])
                   :tab-el-id els/tab}]
     (case tab
       :workflows [workflows/workflow-list {:workflows (workflows/team-workflows team) :catalog (or catalog [])}]
       :runs [team-runs (:id team)]
       :setup [setup team]
       :people [people team]
       nil)]))

(defn main-panel
  []
  (when-let [team-id
             @(rf/subscribe [::data/ui :team :team-id])]
    [data/loaded {:key [:team team-id]
                  :query {:query-id :get-team :team-id team-id}
                  :error-el-id els/error}
     team-view]))
