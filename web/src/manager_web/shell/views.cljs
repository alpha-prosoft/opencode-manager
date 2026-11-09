(ns manager-web.shell.views
  "The application frame: sidebar navigation, mobile app bar, user footer."
  (:require ["@mui/icons-material/AdminPanelSettingsOutlined" :default AdminIcon]
            ["@mui/icons-material/ApartmentOutlined" :default TenantsIcon]
            ["@mui/icons-material/GroupsOutlined" :default GroupsIcon]
            ["@mui/icons-material/HandymanOutlined" :default HandymanIcon]
            ["@mui/icons-material/History" :default HistoryIcon]
            ["@mui/icons-material/HomeOutlined" :default HomeIcon]
            ["@mui/icons-material/HubOutlined" :default HubIcon]
            ["@mui/icons-material/KeyOutlined" :default KeyIcon]
            ["@mui/icons-material/Logout" :default LogoutIcon]
            ["@mui/icons-material/Menu" :default MenuIcon]
            ["@mui/icons-material/SourceOutlined" :default ProjectIcon]
            ["@mui/icons-material/Terminal" :default TerminalIcon]
            ["@mui/icons-material/WebhookOutlined" :default WebhookIcon]
            ["@mui/icons-material/WidgetsOutlined" :default WidgetsIcon]
            ["@mui/material/AppBar" :default AppBar]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Typography" :default Typography]
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
            [manager-web.components.team-context :as team-context]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.routes :as routes]
            [manager-web.search.views :as search]
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
            {:page :projects :label "Projects" :icon ProjectIcon :section #{:projects :project}}
            {:page :runs :label "Runs" :icon HistoryIcon :section #{:runs :run}}
            {:page :sessions :label "Sessions" :icon TerminalIcon :section #{:sessions :session}}
            {:page :webhooks :label "Webhooks" :icon WebhookIcon}]}
   {:group "Library"
    :items [{:page :teams :label "Teams" :icon GroupsIcon :section #{:teams :team}}
            {:page :harnesses :label "Harnesses" :icon HandymanIcon}
            {:page :activities :label "Activities" :icon WidgetsIcon}]}
   {:group "You"
    :items [{:page :accounts :label "Accounts" :icon KeyIcon}]}])

(def admin-nav
  {:group "Tenant"
   :items [{:page :admin :label "Admin" :icon AdminIcon}]})

(def operator-nav
  {:group "Platform"
   :items [{:page :tenants :label "Tenants" :icon TenantsIcon}]})

(defn not-your-tenant
  "In place of a page of another tenant: nothing of it is loaded."
  []
  (let [me
        @(rf/subscribe [::session/me])

        tenant
        (:tenant @(rf/subscribe [::routes/path-params]))]
    [layout/page-stack
     [layout/page-header {:title "Not your tenant" :el-id els/foreign-tenant :title-el-id els/foreign-tenant-title}]
     [layout/empty-state {:title (str "This page belongs to " tenant) :el-id els/foreign-tenant-message}
      (if (:tenant me)
        (str "You work in " (:tenant me) ", so you can't open pages of another tenant. ")
        "You operate the platform and belong to no tenant, so tenants' pages are closed to you. ")
      (if (:tenant me)
        [layout/link {:page :home :el-id els/foreign-tenant-home} (str "Go to " (:tenant me))]
        [layout/link {:page :tenants :el-id els/foreign-tenant-home} "Go to Tenants"])]]))

(defn- home-link
  [me]
  (if (:tenant me)
    [layout/link {:page :home :el-id els/not-found-home} (str "Go to " (:tenant me))]
    [layout/link {:page :tenants :el-id els/not-found-home} "Go to Tenants"]))

(defn- nowhere
  [title text]
  (let [me
        @(rf/subscribe [::session/me])]
    [layout/page-stack
     [layout/empty-state {:title title :el-id els/not-found}
      text " "
      [home-link me]]]))

(defn not-found
  "In place of a URL no page answers."
  [_error]
  [nowhere "Page not found" "Nothing lives at this address. It may have been mistyped, or the page has moved."])

(defn bad-request
  "In place of a page whose URL parameters can't be read."
  [_error]
  [nowhere "This link can't be opened" "The address carries parameters this page doesn't understand."])

(defn nav-groups
  [me]
  (cond-> (if (:tenant me) nav [])
    (roles/tenant-admin? me) (conj admin-nav)
    (roles/operator? me) (conj operator-nav)))

(defn- active?
  [active-panel {:keys [page section]}]
  (if section
    (contains? section active-panel)
    (= page active-panel)))

(defn tenant-chip
  [me sx]
  [:> Chip {:icon (r/as-element [:> (if (roles/platform-only? me) HubIcon TenantsIcon)])
            :label (roles/tenant-label me)
            :sx sx
            :title (if (roles/platform-only? me) "You operate the platform" (str "Tenant " (roles/tenant-label me)))
            :data-testid (el/id els/brand-tenant)
            :data-scope (if (roles/platform-only? me) "platform" "tenant")}])

(defn brand
  [desktop?]
  (let [me
        @(rf/subscribe [::session/me])

        {:keys [href on-click]}
        (if (:tenant me)
          (layout/nav-props :home nil (team-context/query (team-context/selected)))
          (layout/nav-props :tenants))]
    [RawStack {:direction "row" :spacing 1.25 :sx (:brand theme/sx) :el-id els/brand
               :component "a" :href href :on-click on-click}
     [RawAvatar {:variant "rounded" :sx (:brand-mark theme/sx)}
      [:> HubIcon {:fontSize "small"}]]
     [RawBox {:sx (:min-width-0 theme/sx)}
      [RawTypography {:variant "h3"} "Agent Platform"]
      (when desktop?
        [tenant-chip me (:brand-tenant theme/sx)])]
     (when-not desktop?
       [tenant-chip me (:brand-tenant-mobile theme/sx)])]))

(defn- role-summary
  [me]
  (let [lines
        (roles/team-role-lines me)]
    [:> Tooltip {:title (if (seq lines)
                          (r/as-element (into [RawBox {:component "ul" :sx (:role-lines theme/sx)}]
                                              (for [line lines] ^{:key line} [:li line])))
                          "")}
     [:> Typography {:variant "caption" :color "text.secondary" :component "div"
                     :data-testid (el/id els/user-role)}
      (roles/role-label me)]]))

(defn- nav-item
  [active-panel on-navigate team-id {:keys [page label icon] :as item}]
  (let [{:keys [href on-click]}
        (layout/nav-props page nil (when (contains? team-context/scoped-pages page) (team-context/query team-id)))]
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
        (nav-groups me)

        display-name
        (session/display-name me)

        logging-out?
        @(rf/subscribe [::data/busy? ::session/logout])

        team-id
        (team-context/selected)]
    [RawStack {:sx (:sidebar theme/sx)}
     [:> Toolbar [brand true]]
     (when (:tenant me)
       [search/search-box])
     [team-context/switcher]
     (into [RawBox {:sx (:sidebar-nav theme/sx)}]
           (for [{:keys [group items]} groups]
             ^{:key group}
             [:> List {:dense true
                       :subheader (r/as-element [:> ListSubheader {:disableSticky true} group])}
              (for [item items]
                ^{:key (:page item)}
                [nav-item active-panel on-navigate team-id item])]))
     [RawStack {:direction "row" :spacing 1.5 :sx (:sidebar-footer theme/sx)}
      [RawAvatar {:sx (:avatar-small theme/sx)}
       (.toUpperCase (format/truncate display-name 1))]
      [RawBox {:sx (:grow theme/sx)}
       [RawTypography {:variant "body2" :noWrap true :sx (:strong theme/sx) :el-id els/user-name} display-name]
       [role-summary me]
       [RawTypography {:variant "caption" :color "text.secondary" :noWrap true :component "div"
                       :el-id els/tenant-name}
        (roles/tenant-label me)]]
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
          [brand false]
          (when (:tenant @(rf/subscribe [::session/me]))
            [search/search-icon])]]
        [:> Drawer {:open open? :on-close close :sx (:drawer-temporary theme/sx)}
         [sidebar close]]])
     [RawBox {:component "main" :sx (:main theme/sx)}
      (when-not desktop? [:> Toolbar])
      [:> Container {:maxWidth "lg" :sx (:container theme/sx)}
       content]]
     [error-snackbar]
     [confirm/dialog]
     (when (:tenant @(rf/subscribe [::session/me]))
       [:<>
        [search/shortcut-listener]
        [search/dialog]])]))

(defn shell
  [content]
  [:f> frame content])
