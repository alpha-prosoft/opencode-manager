(ns manager-web.components.credential-scope
  "Where the value of a credential comes from for the person (their own, the
   team's, the tenant's, or nowhere) and whether they may provide their own."
  (:require ["@mui/material/Button" :default Button]
            ["@mui/material/Chip" :default Chip]
            [clojure.string :as string]
            [manager-web.components.layout :as layout]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert]]))

(defn resolution
  ":own, :team, :tenant or :missing, from a credential status's :state."
  [{:keys [state]}]
  (case (format/kw state)
    :own :own
    :team :team
    (:tenant :platform) :tenant
    :missing))

(defn may-override?
  "True when the credential lets each person provide their own value: what
   the service says (:may-provide), else what :provided-by allows."
  [{:keys [provided-by may-provide]}]
  (if (some? may-provide)
    (true? may-provide)
    (contains? (format/scopes provided-by) :user)))

(defn shared-scopes
  "The shared scopes (:team, :tenant) a credential falls back to."
  [{:keys [provided-by]}]
  (filterv (format/scopes provided-by) [:team :tenant]))

(def ^:private resolution-labels
  {:own "Your own"
   :team "Team value"
   :tenant "Tenant value"
   :missing "Missing"})

(def ^:private resolution-colors
  {:own "success"
   :team "info"
   :tenant "info"
   :missing "warning"})

(defn- scope-names
  [scopes {:keys [team tenant]}]
  (let [team
        (if team (str (format/possessive team) "") "your team's")

        tenant
        (if tenant (str (format/possessive tenant) "") "the tenant's")]
    (case scopes
      [:team :tenant] (str team " or " tenant)
      [:team] team
      [:tenant] tenant
      nil)))

(defn applies-to
  "The person's teams a credential applies to, with where its value comes from in each."
  [{:keys [teams]}]
  (mapv (fn [{:keys [team-id state]}] [team-id (resolution {:state state})]) teams))

(def ^:private source-words
  {:own "your own"
   :team "team value"
   :tenant "tenant value"
   :missing "missing"})

(defn team-breakdown
  "Where the value comes from per team, when the person is in several teams it applies to."
  [team-name credential]
  (let [items
        (applies-to credential)]
    (when (< 1 (count items))
      (for [[team-id source] items]
        [team-id (str (team-name team-id) ": " (source-words source)) source]))))

(defn admin-links
  "Where an admin among the viewers sets a shared value: their teams' settings
   and the tenant's credentials. me is :get-me."
  [me {:keys [teams] :as credential} {:keys [team-admin? tenant-admin?]}]
  (let [scopes
        (format/scopes (:provided-by credential))]
    (concat
     (when (contains? scopes :team)
       (for [{:keys [team-id]} teams
             :when (team-admin? team-id)]
         {:kind :team :team-id team-id :page :team :params {:team-id team-id} :query {:tab "settings"}}))
     (when (and (contains? scopes :tenant) tenant-admin?)
       [{:kind :tenant :page :admin :query {:tab "credentials"} :tenant (:tenant me)}]))))

(defn- explain
  [credential shared {:keys [team tenant]}]
  (let [team
        (if team (str (format/possessive team) "") "Your team's")

        tenant
        (if tenant (str (format/possessive tenant) "") "The tenant's")

        override?
        (may-override? credential)]
    (case [(resolution credential) override? (some? shared)]
      [:own true true] (str "Your own value is used instead of " shared " value. Removing it falls back to " shared ".")
      [:own true false] "Your own value is used. Each person provides their own."
      [:team true true] (str team " value is used. You may add your own to act as yourself.")
      [:tenant true true] (str tenant " value is used. You may add your own to act as yourself.")
      [:missing true true] (str "Nobody has set " shared " value yet. You may add your own.")
      [:missing true false] "Each person provides their own; you haven't yet."
      [:team false true] (str team " value is used. Only your team admins can change it.")
      [:tenant false true] (str tenant " value is used. Only admins can change it.")
      [:missing false true] (str "Not set. Only admins can set " shared " value.")
      "")))

(defn explanation
  "One sentence on where the value comes from and what the person may do.
   names: {:team :tenant} display names, when known."
  ([credential]
   (explanation credential {}))
  ([credential names]
   (explain credential (scope-names (shared-scopes credential) names) names)))

(defn admin-explanation
  "Where the value comes from, for an admin who can set it."
  [credential {:keys [team tenant]}]
  (case (resolution credential)
    :own "Your own value is used."
    :team (str (if team (format/possessive team) "Your team's") " value is used.")
    :tenant (str (if tenant (format/possessive tenant) "The tenant's") " value is used.")
    "Not set yet."))

(defn resolution-chip
  [credential el-id]
  (let [value
        (resolution credential)]
    [:> Chip {:color (resolution-colors value) :label (resolution-labels value)
              :data-testid (el/id el-id) :data-state (name value)}]))

(defn missing-text
  "What to add under Accounts before purpose (\"these tools\") works."
  [names purpose]
  (str "Add your " (string/join " and " names) " credential to use " purpose "."))

(defn missing-alert
  "Says which of the person's own credentials are missing for purpose and
   leads to Accounts. on-leave runs before going there."
  [{:keys [names purpose el-id add-el-id on-leave]}]
  (when (seq names)
    (let [{:keys [href on-click]}
          (layout/nav-props :accounts)]
      [RawAlert {:severity "warning" :el-id el-id
                 :action (r/as-element
                          [:> Button {:color "inherit" :size "small" :href href
                                      :data-testid (el/id add-el-id)
                                      :on-click #(do (when on-leave (on-leave)) (on-click %))}
                           "Add"])}
       (missing-text names purpose)])))
