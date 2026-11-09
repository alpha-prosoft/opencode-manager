(ns manager-web.search.views
  "The search box of the frame and its command-palette dialog."
  (:require ["@mui/icons-material/GroupsOutlined" :default GroupsIcon]
            ["@mui/icons-material/Search" :default SearchIcon]
            ["@mui/icons-material/SourceOutlined" :default ProjectIcon]
            ["@mui/icons-material/Terminal" :default TerminalIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/InputAdornment" :default InputAdornment]
            ["@mui/material/LinearProgress" :default LinearProgress]
            ["@mui/material/List" :default List]
            ["@mui/material/ListItemButton" :default ListItemButton]
            ["@mui/material/ListItemIcon" :default ListItemIcon]
            ["@mui/material/ListItemText" :default ListItemText]
            ["@mui/material/ListSubheader" :default ListSubheader]
            ["@mui/material/TextField" :default TextField]
            ["@mui/material/Typography" :default Typography]
            ["react" :as react]
            [clojure.string :as string]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.search.elements :as els]
            [manager-web.search.events :as events]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox]]))

(def ^:private icons
  {:project ProjectIcon
   :team GroupsIcon
   :session TerminalIcon})

(defn mac?
  []
  (boolean (re-find #"(?i)mac|iphone|ipad" (str (.-platform js/navigator)))))

(defn shortcut-label
  []
  (if (mac?) "⌘K" "Ctrl K"))

(defn secondary-text
  "The line under a result's name: where it belongs and, for sessions, whose."
  [me {:keys [kind item]}]
  (case kind
    :project (string/join " · " (remove string/blank? [(:team-name item)
                                                       (some-> (get-in item [:scm :provider]) name)
                                                       (get-in item [:scm :repository])]))
    :team (if (:member item)
            (str (roles/role-in-team-label me (:id item)) (when-not (string/blank? (:description item)) (str " · " (:description item))))
            "You're not in this team")
    :session (string/join " · " (remove string/blank? [(:team-name item)
                                                       (cond
                                                         (:team-session item) "Team session"
                                                         (:is-owner item) "yours"
                                                         :else (:owner item))
                                                       (some->> (:project item) (str "project "))
                                                       (some-> (:created-at item) format/relative-time)]))))

(defn- result-row
  [me selected-index index row]
  (let [{:keys [kind item]}
        row]
    [:> ListItemButton {:selected (= selected-index index)
                        :disabled (nil? (events/target row))
                        :data-testid (el/id (els/result kind (:id item)))
                        :data-selected (str (= selected-index index))
                        :on-mouse-move #(when-not (= selected-index index) (rf/dispatch [::events/select index]))
                        :on-click #(rf/dispatch [::events/choose row])}
     [:> ListItemIcon [:> (icons kind) {:fontSize "small"}]]
     [:> ListItemText {:primary (:name item) :secondary (secondary-text me row)
                       :slotProps {:primary {:noWrap true} :secondary {:noWrap true}}}]]))

(defn- result-list
  [me {:keys [results index]}]
  (let [indexed
        (map-indexed vector (events/rows results))]
    (into [:> List {:dense true :sx (:search-results theme/sx) :data-testid (el/id els/results)}]
          (for [[key kind heading] events/groups
                :let [in-group (filter #(= kind (:kind (second %))) indexed)]
                :when (seq in-group)]
            ^{:key key}
            [:li {:data-testid (el/id (els/group kind))}
             (into [RawBox {:component "ul" :sx (:search-group theme/sx)}
                    [:> ListSubheader heading]]
                   (for [[i row] in-group]
                     ^{:key (str (name kind) (:id (:item row)))}
                     [result-row me index i row]))]))))

(defn- key-down
  [^js e]
  (case (.-key e)
    "ArrowDown" (do (.preventDefault e) (rf/dispatch [::events/move 1]))
    "ArrowUp" (do (.preventDefault e) (rf/dispatch [::events/move -1]))
    "Enter" (do (.preventDefault e) (rf/dispatch [::events/choose-selected]))
    nil))

(defn- body
  [{:keys [text results loading? error] :as state}]
  (let [me
        @(rf/subscribe [::session/me])

        found?
        (seq (events/rows results))]
    [:<>
     [:> TextField {:autoFocus true :fullWidth true :value (or text "") :placeholder "Search projects, teams and sessions"
                    :id (el/id els/input)
                    :sx (:search-input theme/sx)
                    :on-change #(rf/dispatch [::events/set-text (.. % -target -value)])
                    :on-key-down key-down
                    :slotProps {:htmlInput {:aria-label "Search" :autoComplete "off"}
                                :input {:startAdornment (r/as-element [:> InputAdornment {:position "start"} [:> SearchIcon]])}}}]
     (if loading?
       [:> LinearProgress {:sx (:search-progress theme/sx)}]
       [RawBox {:sx (:search-progress theme/sx)}])
     (cond
       error [RawAlert {:severity "error" :el-id els/error} error]
       found? [result-list me state]
       (and (some? results) (not loading?)) [:> Typography {:variant "body2" :color "text.secondary" :sx (:search-note theme/sx)
                                                            :data-testid (el/id els/empty-state)}
                                             (str "Nothing matches \"" text "\".")]
       :else [:> Typography {:variant "body2" :color "text.secondary" :sx (:search-note theme/sx)
                             :data-testid (el/id els/hint)}
              "Type a name. ↑ ↓ to move, Enter to open, Esc to close."])]))

(defn dialog
  []
  (let [{:keys [open?] :as state}
        @(rf/subscribe [::events/state])]
    [:> Dialog {:open (boolean open?) :on-close #(rf/dispatch [::events/close]) :fullWidth true :maxWidth "sm"
                :sx (:search-dialog theme/sx)
                :data-testid (el/id els/dialog)}
     (when open?
       [body state])]))

(defn- on-key
  [^js e]
  (when (events/shortcut? {:key (.-key e) :ctrl? (.-ctrlKey e) :meta? (.-metaKey e)})
    (.preventDefault e)
    (rf/dispatch [::events/open])))

(defn- listen-for-shortcut
  []
  (react/useEffect (fn []
                     (.addEventListener js/window "keydown" on-key)
                     #(.removeEventListener js/window "keydown" on-key))
                   #js [])
  nil)

(defn shortcut-listener
  "Opens the dialog on Ctrl+K or Cmd+K anywhere in the app."
  []
  [:f> listen-for-shortcut])

(defn search-box
  "The search field of the sidebar; it opens the dialog."
  []
  [:<>
   [:> Button {:variant "outlined" :color "inherit" :fullWidth true :sx (:search-trigger theme/sx)
               :startIcon (r/as-element [:> SearchIcon])
               :endIcon (r/as-element [:> Typography {:variant "caption" :component "kbd" :sx (:search-shortcut theme/sx)}
                                       (shortcut-label)])
               :aria-label "Search"
               :data-testid (el/id els/open)
               :on-click #(rf/dispatch [::events/open])}
    "Search"]])

(defn search-icon
  "The search button of the mobile app bar."
  []
  [:> IconButton {:aria-label "Search" :data-testid (el/id els/open-mobile) :sx (:push-right theme/sx)
                  :on-click #(rf/dispatch [::events/open])}
   [:> SearchIcon]])
