(ns manager-web.components.layout
  "Page building blocks shared by every page."
  (:require ["@mui/icons-material/ArrowBack" :default ArrowBackIcon]
            ["@mui/icons-material/OpenInNew" :default OpenInNewIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Link" :default Link]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Tab" :default Tab]
            ["@mui/material/Table" :default Table]
            ["@mui/material/TableBody" :default TableBody]
            ["@mui/material/TableCell" :default TableCell]
            ["@mui/material/TableHead" :default TableHead]
            ["@mui/material/TableRow" :default TableRow]
            ["@mui/material/Tabs" :default Tabs]
            ["@mui/material/Typography" :default Typography]
            [manager-web.el :as el]
            [manager-web.theme :as theme]
            [manager-web.urls :as urls]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawAvatar RawBox RawCircularProgress RawStack
                                               RawTable RawTableBody RawTableCell RawTableRow
                                               RawTypography]]))

(defn navigate!
  ([page]
   (rf/dispatch [:edd.events/navigate page]))
  ([page params]
   (rf/dispatch [:edd.events/navigate page (update-vals params str)])))

(defn path
  "The URL of an in-app page."
  [page params]
  (case page
    :home "/"
    :team (str "/teams/" (:team-id params))
    :run (str "/runs/" (:run-id params))
    :session (str "/sessions/" (:session-id params))
    (str "/" (name page))))

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
   {:href (path page params)
    :on-click (fn [e]
                (when (plain-click? e)
                  (.preventDefault e)
                  (navigate! page params)))}))

(defn link
  "An in-app link to page with params."
  [{:keys [page params el-id sx variant color]} & children]
  (into [:> Link (merge (nav-props page params)
                        {:data-testid (el/id el-id)
                         :variant variant
                         :color color
                         :sx sx})]
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

(defn page-header
  [{:keys [title description back meta actions el-id title-el-id]}]
  [RawStack {:spacing 1 :el-id el-id}
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

(defn definition-errors
  "The problems of a definition; with source, named as the file that has them."
  [{:keys [source errors]}]
  (when (seq errors)
    [RawAlert {:severity "warning"}
     (when source
       [:<> [:strong source] " can't be used:"])
     (into [RawBox {:component "ul" :sx (:error-list theme/sx)}]
           (for [error errors]
             ^{:key error} [:li error]))]))

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
  "(render data) under any error of the last refresh; the error alone while
   there is no data; else a loading indicator."
  [{:keys [data error]} error-el-id render]
  (cond
    (some? data) [:<> [error-alert error error-el-id] (render data)]
    error [error-alert error error-el-id]
    :else [loading]))
