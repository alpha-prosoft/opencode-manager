(ns manager-web.shell.views
  "The application frame: sidebar navigation, mobile app bar, user footer."
  (:require ["@mui/icons-material/AdminPanelSettingsOutlined" :default AdminIcon]
            ["@mui/icons-material/GroupsOutlined" :default GroupsIcon]
            ["@mui/icons-material/HandymanOutlined" :default HandymanIcon]
            ["@mui/icons-material/History" :default HistoryIcon]
            ["@mui/icons-material/HomeOutlined" :default HomeIcon]
            ["@mui/icons-material/HubOutlined" :default HubIcon]
            ["@mui/icons-material/KeyOutlined" :default KeyIcon]
            ["@mui/icons-material/Logout" :default LogoutIcon]
            ["@mui/icons-material/Menu" :default MenuIcon]
            ["@mui/icons-material/Terminal" :default TerminalIcon]
            ["@mui/icons-material/WidgetsOutlined" :default WidgetsIcon]
            ["@mui/material/AppBar" :default AppBar]
            ["@mui/material/Container" :default Container]
            ["@mui/material/Drawer" :default Drawer]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/Tooltip" :default Tooltip]
            ["@mui/material/List" :default List]
            ["@mui/material/ListItemButton" :default ListItemButton]
            ["@mui/material/ListItemIcon" :default ListItemIcon]
            ["@mui/material/ListItemText" :default ListItemText]
            ["@mui/material/ListSubheader" :default ListSubheader]
            ["@mui/material/Toolbar" :default Toolbar]
            ["@mui/material/styles" :refer [useTheme]]
            ["@mui/material/useMediaQuery" :default useMediaQuery]
            ["react" :as react]
            [edd.subs :as edd-subs]
            [manager-web.api :as api]
            [manager-web.components.confirm :as confirm]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.session :as session]
            [manager-web.shell.elements :as els]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAvatar RawBox RawIconButton RawSnackbar RawStack
                                               RawTypography]]))

(def nav
  [{:group "Work"
    :items [{:page :home :label "Home" :icon HomeIcon}
            {:page :runs :label "Runs" :icon HistoryIcon :section #{:runs :run}}
            {:page :sessions :label "Sessions" :icon TerminalIcon :section #{:sessions :session}}]}
   {:group "Library"
    :items [{:page :teams :label "Teams" :icon GroupsIcon :section #{:teams :team}}
            {:page :harnesses :label "Harnesses" :icon HandymanIcon}
            {:page :activities :label "Activities" :icon WidgetsIcon}]}
   {:group "You"
    :items [{:page :accounts :label "Accounts" :icon KeyIcon}]}])

(def admin-nav
  {:group "Platform"
   :items [{:page :admin :label "Admin" :icon AdminIcon}]})

(defn- active?
  [active-panel {:keys [page section]}]
  (if section
    (contains? section active-panel)
    (= page active-panel)))

(defn brand
  []
  [RawStack {:direction "row" :spacing 1.25 :sx (:brand theme/sx) :el-id els/brand
             :on-click #(rf/dispatch [:edd.events/navigate :home])}
   [RawAvatar {:variant "rounded" :sx (:brand-mark theme/sx)}
    [:> HubIcon {:fontSize "small"}]]
   [RawTypography {:variant "h3"} "Agent Platform"]])

(defn- nav-item
  [active-panel on-navigate {:keys [page label icon] :as item}]
  (let [{:keys [href on-click]}
        (layout/nav-props page)]
    [:> ListItemButton {:selected (active? active-panel item)
                        :data-testid (el/id (els/nav-item page))
                        :component "a"
                        :href href
                        :on-click (fn [e]
                                    (on-navigate)
                                    (on-click e))}
     [:> ListItemIcon [:> icon]]
     [:> ListItemText {:primary label}]]))

(defn sidebar
  [on-navigate]
  (let [me
        @(rf/subscribe [::session/me])

        active-panel
        @(rf/subscribe [::edd-subs/active-panel])

        groups
        (if (:platform-admin me) (conj nav admin-nav) nav)

        display-name
        (session/display-name me)

        logging-out?
        @(rf/subscribe [::data/busy? ::session/logout])]
    [RawStack {:sx (:full-height theme/sx)}
     [:> Toolbar [brand]]
     (into [RawBox {:sx (:sidebar-nav theme/sx)}]
           (for [{:keys [group items]} groups]
             ^{:key group}
             [:> List {:dense true
                       :subheader (r/as-element [:> ListSubheader {:disableSticky true} group])}
              (for [item items]
                ^{:key (:page item)}
                [nav-item active-panel on-navigate item])]))
     [RawStack {:direction "row" :spacing 1.5 :sx (:sidebar-footer theme/sx)}
      [RawAvatar {:sx (:avatar-small theme/sx)}
       (.toUpperCase (format/truncate display-name 1))]
      [RawBox {:sx (:grow theme/sx)}
       [RawTypography {:variant "body2" :noWrap true :sx (:strong theme/sx) :el-id els/user-name} display-name]
       [RawTypography {:variant "caption" :color "text.secondary" :el-id els/user-role}
        (session/role-label me)]]
      [:> Tooltip {:title "Log out"}
       [:span
        [:> IconButton {:size "small" :aria-label "Log out" :data-testid (el/id els/logout)
                        :loading logging-out?
                        :on-click #(rf/dispatch [::session/logout])}
         [:> LogoutIcon {:fontSize "small"}]]]]]]))

(defn- error-snackbar
  []
  (let [message
        @(rf/subscribe [::api/error])]
    [RawSnackbar {:open (some? message)
                  :el-id els/error-snackbar
                  :message (or message "")
                  :on-close #(rf/dispatch [::api/clear-error])
                  :alert-props {:severity "error" :variant "filled"
                                :on-close #(rf/dispatch [::api/clear-error])}}]))

(defn- frame
  [content]
  (let [mui-theme
        (useTheme)

        desktop?
        (useMediaQuery (.up (.-breakpoints mui-theme) "md"))

        [open? set-open]
        (react/useState false)

        close
        #(set-open false)]
    [RawBox {:sx (:app-frame theme/sx)}
     (if desktop?
       [:> Drawer {:variant "permanent" :sx (:drawer theme/sx)}
        [sidebar close]]
       [:<>
        [:> AppBar {:position "fixed"}
         [:> Toolbar
          [RawIconButton {:edge "start" :aria-label "Open navigation" :el-id els/open-navigation
                          :on-click #(set-open true) :sx (:icon-gap theme/sx)}
           [:> MenuIcon]]
          [brand]]]
        [:> Drawer {:open open? :on-close close :sx (:drawer-temporary theme/sx)}
         [sidebar close]]])
     [RawBox {:component "main" :sx (:main theme/sx)}
      (when-not desktop? [:> Toolbar])
      [:> Container {:maxWidth "lg" :sx (:container theme/sx)}
       content]]
     [error-snackbar]
     [confirm/dialog]]))

(defn shell
  [content]
  [:f> frame content])
