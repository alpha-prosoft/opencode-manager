(ns manager-web.components.layout
  "Page building blocks shared by every page."
  (:require ["@mui/icons-material/ApartmentOutlined" :default ApartmentIcon]
            ["@mui/icons-material/ArrowBack" :default ArrowBackIcon]
            ["@mui/icons-material/GroupsOutlined" :default GroupsIcon]
            ["@mui/icons-material/HubOutlined" :default HubIcon]
            ["@mui/icons-material/OpenInNew" :default OpenInNewIcon]
            ["@mui/material/Breadcrumbs" :default Breadcrumbs]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Link" :default Link]
            ["@mui/material/MenuItem" :default MenuItem]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Tab" :default Tab]
            ["@mui/material/Table" :default Table]
            ["@mui/material/TableBody" :default TableBody]
            ["@mui/material/TableCell" :default TableCell]
            ["@mui/material/TableHead" :default TableHead]
            ["@mui/material/TableRow" :default TableRow]
            ["@mui/material/Tabs" :default Tabs]
            ["@mui/material/TextField" :default TextField]
            ["@mui/material/Typography" :default Typography]
            [clojure.string :as string]
            [manager-web.el :as el]
            [manager-web.roles :as roles]
            [manager-web.routes :as routes]
            [manager-web.theme :as theme]
            [manager-web.urls :as urls]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawAvatar RawBox RawCircularProgress RawStack
                                               RawTable RawTableBody RawTableCell RawTableRow
                                               RawTypography]]))

(defn navigate!
  "Opens page (under the person's tenant) with params and query."
  ([page]
   (navigate! page nil nil))
  ([page params]
   (navigate! page params nil))
  ([page params query]
   (rf/dispatch [::routes/navigate page params query])))

(defn path
  "The URL of an in-app page under the person's tenant, with query
   parameters. Call it while rendering."
  ([page params]
   (path page params nil))
  ([page params query]
   (routes/path @(rf/subscribe [::routes/tenant]) page params query)))

(defn- plain-click?
  "A primary click without modifiers; others open the link in a new tab or
   window as the browser would."
  [^js e]
  (and (zero? (.-button e))
       (not (or (.-metaKey e) (.-ctrlKey e) (.-shiftKey e) (.-altKey e)))))

(defn nav-props
  "href and on-click for an anchor-like element that opens an in-app page
   without reloading."
  ([page]
   (nav-props page nil))
  ([page params]
   (nav-props page params nil))
  ([page params query]
   (let [query
         (into {} (remove (comp nil? val)) query)]
     {:href (path page params query)
      :on-click (fn [e]
                  (when (plain-click? e)
                    (.preventDefault e)
                    (navigate! page params query)))})))

(defn link
  "An in-app link to page with params and query."
  [{:keys [page params query el-id sx variant color attrs]} & children]
  (into [:> Link (merge (nav-props page params query)
                        {:data-testid (el/id el-id)
                         :variant variant
                         :color color
                         :sx sx}
                        attrs)]
        children))

(defn external-link
  "A link to another site; renders only its text when href is not an
   http(s) URL or local path."
  [{:keys [href el-id]} & children]
  (if-let [url
           (urls/safe-url href)]
    (into [:> Link {:href url :target "_blank" :rel "noopener noreferrer" :data-testid (el/id el-id)}]
          children)
    (into [:span] children)))

(defn external-button
  "A button opening href in a new tab, or nothing when href is not an
   http(s) URL or local path."
  [{:keys [href el-id variant size]} label]
  (when-let [url
             (urls/safe-url href)]
    [:> Button (cond-> {:href url :target "_blank" :rel "noopener noreferrer"
                        :startIcon (r/as-element [:> OpenInNewIcon])
                        :data-testid (el/id el-id)}
                 variant (assoc :variant variant)
                 size (assoc :size size))
     label]))

(defn breadcrumbs
  "Where a page sits: segments {:label :page :params :query :el-id}; a
   segment without :page (one the viewer can't open) is plain text. The last
   segment is the page itself."
  [{:keys [el-id]} segments]
  (into [:> Breadcrumbs {:sx (:breadcrumbs theme/sx) :data-testid (el/id el-id) :aria-label "You are here"}]
        (for [[index {:keys [label page params query el-id]}] (map-indexed vector segments)
              :let [last? (= index (dec (count segments)))]]
          ^{:key index}
          (cond
            last? [:> Typography {:variant "body2" :sx (:breadcrumb-current theme/sx) :data-testid (el/id el-id)} label]
            page [link {:page page :params params :query query :color "inherit" :el-id el-id} label]
            :else [:> Typography {:variant "body2" :component "span" :data-testid (el/id el-id)} label]))))

(defn entity-segments
  "The breadcrumb segments of something of a team: tenant / team / project
   / list / label. The team and project are links only for those who can
   open the team. list is {:label :page :query}; els names the el-ids
   {:tenant :team :project :list}."
  [me {:keys [team-id project list label els]}]
  (let [open?
        (roles/can-open-team? me team-id)]
    (vec (remove nil?
                 [{:label (roles/tenant-label me) :page :home :el-id (:tenant els)}
                  (when team-id
                    (cond-> {:label (roles/team-name me team-id) :el-id (:team els)}
                      open? (assoc :page :team :params {:team-id team-id})))
                  (when project
                    (cond-> {:label project :el-id (:project els)}
                      open? (assoc :page :project :params {:team-id team-id :project-id project})))
                  (when list
                    (assoc list :el-id (:list els)))
                  {:label label}]))))

(defn entity-crumbs
  "Breadcrumbs of something of a team; see entity-segments."
  [me {:keys [els] :as entity}]
  [breadcrumbs {:el-id (:crumbs els)} (entity-segments me entity)])

(defn way-back
  "Under a page that couldn't load: the way to the list it belongs to."
  [{:keys [page params query label el-id]}]
  [:div
   [:> Button (merge (nav-props page params query)
                     {:startIcon (r/as-element [:> ArrowBackIcon]) :data-testid (el/id el-id)})
    label]])

(def ^:private scope-icons
  {:tenant ApartmentIcon
   :team GroupsIcon
   :platform HubIcon})

(defn scope-chip
  "What a page acts on: [:tenant \"acme\"], [:team \"Demo team\"] or [:platform]."
  [[scope label] el-id]
  [:> Chip {:icon (r/as-element [:> (scope-icons scope)])
            :data-testid (el/id el-id)
            :data-scope (name scope)
            :label (case scope
                     :tenant (str "Tenant: " label)
                     :team (str "Team: " label)
                     :platform "Platform")}])

(defn page-header
  [{:keys [title description back crumbs meta actions el-id title-el-id]}]
  [RawStack {:spacing 1 :el-id el-id}
   (when crumbs
     crumbs)
   (when back
     [link {:page (:page back) :params (:params back) :variant "body2" :color "text.secondary"
            :sx (:back-link theme/sx) :el-id (:el-id back)}
      [:> ArrowBackIcon {:fontSize "inherit"}]
      (:label back)])
   [RawStack {:direction {:xs "column" :sm "row"} :spacing 2 :sx (:responsive-row theme/sx)}
    [RawBox {:sx (:grow theme/sx)}
     [RawTypography {:variant "h1" :el-id title-el-id} title]
     (when description
       [RawTypography {:color "text.secondary" :sx (:header-description theme/sx)} description])]
    (when actions
      (into [RawStack {:direction "row" :spacing 1 :useFlexGap true :sx (:header-actions theme/sx)}]
            actions))]
   (when meta
     (into [RawStack {:direction "row" :spacing 1 :useFlexGap true :sx (:header-meta theme/sx)}]
           meta))])

(defn section
  [{:keys [title description action el-id]} & children]
  (into [RawStack {:spacing 1.5 :component "section" :el-id el-id}
         [RawStack {:direction "row" :spacing 2 :sx (:section-header theme/sx)}
          [RawBox {:sx (:grow theme/sx)}
           [RawTypography {:variant "h2"} title]
           (when description
             [RawTypography {:variant "body2" :color "text.secondary"} description])]
          action]]
        children))

(defn page-stack
  [& children]
  (into [RawStack {:sx (:page-stack theme/sx)}] children))

(defn- option-value
  [option]
  (if (keyword? option) (name option) (str option)))

(defn select
  "A select. options are [value label] pairs, on-change gets the chosen
   value as a string and option-el-id the el-id of an option from its
   value. Other props go to the field."
  [{:keys [options value on-change option-el-id] :as props}]
  (into [:> TextField (merge {:select true
                              :size "small"
                              :value (if (some? value) (option-value value) "")
                              :on-change #(on-change (.. % -target -value))}
                             (dissoc props :options :value :on-change :option-el-id))]
        (for [[option label] options]
          ^{:key (option-value option)}
          [:> MenuItem (cond-> {:value (option-value option)}
                         option-el-id (assoc :data-testid (el/id (option-el-id option))))
           label])))

(defn tabs
  "A tab bar. tabs are [id label] pairs, on-change gets the chosen id and
   tab-el-id the el-id of a tab from its id."
  [{:keys [value tabs on-change tab-el-id]}]
  [RawBox {}
   (into [:> Tabs {:value (name value) :variant "scrollable"
                   :on-change (fn [_ chosen] (on-change (keyword chosen)))}]
         (for [[id label] tabs]
           ^{:key id}
           [:> Tab {:value (name id) :label label :data-testid (el/id (tab-el-id id))}]))])

(defn cell
  "A table cell; the first argument may be its props."
  [& [props & content :as args]]
  (if (map? props)
    (into [:> TableCell props] content)
    (into [:> TableCell] args)))

(defn head
  "A table header; a column is a label or [label cell-props]."
  [& columns]
  [:> TableHead
   (into [:> TableRow]
         (for [[index column] (map-indexed vector columns)
               :let [[label props]
                     (if (vector? column) column [column])]]
           ^{:key index}
           [:> TableCell props label]))])

(defn table
  "A table in a horizontally scrolling paper. rows are TableRow elements."
  [{:keys [el-id]} header rows]
  [:> Paper {:sx (:scroll-x theme/sx) :data-testid (el/id el-id)}
   [:> Table
    header
    (into [:> TableBody] rows)]])

(defn table-row
  [props & cells]
  (into [:> TableRow props] cells))

(defn empty-state
  [{:keys [title el-id]} & children]
  [:> Paper {:sx (:empty-state theme/sx) :data-testid (el/id el-id)}
   [RawTypography {:variant "h3"} title]
   (when (seq children)
     (into [:> Typography {:variant "body2" :color "text.secondary" :sx (:empty-state-text theme/sx)}]
           children))])

(defn not-found
  "In place of something that doesn't exist: what, and the way to the list
   it would be in (a way-back map)."
  [{:keys [title el-id back]} text]
  [:<>
   [empty-state {:title title :el-id el-id} text]
   [way-back back]])

(defn detail-list
  [items]
  [:> Paper
   [RawTable {}
    (into [RawTableBody {}]
          (for [[label value] items
                :when label]
            ^{:key label}
            [RawTableRow {}
             [RawTableCell {:component "th" :scope "row" :sx (:detail-label theme/sx)} label]
             [RawTableCell {:sx (:detail-value theme/sx)} value]]))]])

(defn stat
  [{:keys [label value hint el-id]}]
  [:> Paper {:sx (:stat theme/sx) :data-testid (el/id el-id)}
   [RawTypography {:variant "label"} label]
   [RawTypography {:variant "h5"} value]
   (when hint
     [RawTypography {:variant "caption" :color "text.secondary"} hint])])

(defn stat-grid
  [& children]
  (into [RawBox {:sx (:stat-grid theme/sx)}] children))

(defn code-block
  [{:keys [short?]} content]
  [RawBox {:component "pre" :sx (if short? (:code-block-short theme/sx) (:code-block theme/sx))} content])

(defn two-columns
  [& children]
  (into [RawBox {:sx (:two-columns theme/sx)}] children))

(defn mono
  [content]
  [RawTypography {:variant "mono"} content])

(defn row
  [& children]
  (into [RawStack {:direction "row" :spacing 1 :useFlexGap true :sx (:row theme/sx)}] children))

(defn list-row
  [{:keys [dense el-id]} & children]
  (into [RawStack {:direction (if dense "row" {:xs "column" :sm "row"})
                   :spacing 2
                   :el-id el-id
                   :sx (if dense (:list-row-dense theme/sx) (:list-row theme/sx))}]
        children))

(defn icon-tile
  [icon]
  [RawAvatar {:variant "rounded" :sx (:icon-tile theme/sx)}
   [:> icon]])

(defn error-texts
  "The readable problems of a definition; errors may be strings or maps with :message."
  [errors]
  (vec (distinct (remove string/blank? (map #(if (map? %) (str (or (:message %) (:error %) (pr-str %))) (str %)) errors)))))

(defn definition-errors
  "The problems of a definition; with source, named as the file that has them."
  [{:keys [source errors valid]}]
  (let [texts
        (error-texts errors)]
    (when (or (seq errors) (false? valid))
      [RawAlert {:severity "warning"}
       [RawBox {}
        (when source
          [:<> [:strong source] " can't be used:"])
        (into [RawBox {:component "ul" :sx (:error-list theme/sx)}]
              (if (seq texts)
                (for [error texts]
                  ^{:key error} [:li error])
                [[:li {:key "none"} "No details reported; see Admin › Teams for the last sync."]]))]])))

(defn loading
  []
  [RawBox {:sx (:loading theme/sx)}
   [RawCircularProgress {}]])

(defn error-alert
  ([message]
   (error-alert message nil))
  ([message el-id]
   (when message
     [RawAlert {:severity "error" :el-id el-id} message])))

(defn loadable
  "(render data) under any error of the last refresh; the error (and failed,
   the way on) while there is no data; else a loading indicator."
  ([load error-el-id render]
   (loadable load error-el-id render nil))
  ([{:keys [data error]} error-el-id render failed]
   (cond
     (some? data) [:<> [error-alert error error-el-id] (render data)]
     error [:<> [error-alert error error-el-id] failed]
     :else [loading])))
