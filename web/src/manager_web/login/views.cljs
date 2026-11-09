(ns manager-web.login.views
  (:require ["@mui/icons-material/HubOutlined" :default HubIcon]
            ["@mui/material/Autocomplete" :default Autocomplete]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/TextField" :default TextField]
            [clojure.string :as string]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.login.elements :as els]
            [manager-web.login.events :as events]
            [manager-web.login.subs :as subs]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawAvatar RawBox RawButton RawChip RawStack
                                               RawTypography]]))

(defn admin-label?
  [role]
  (boolean (some #(string/starts-with? role %) ["Operator" "Tenant admin"])))

(defn- persona-option
  [props option]
  (let [{:keys [username tenant roles]}
        (js->clj option :keywordize-keys true)

        li-props
        (dissoc (js->clj props) "key")]
    (r/as-element
     [:li (merge li-props {:key username :data-testid (el/id (els/persona username))})
      [RawStack {:spacing 0.5}
       [RawTypography {:sx (:strong theme/sx)} username]
       (when tenant
         [RawTypography {:variant "caption" :color "text.secondary" :el-id (els/persona-tenant username)}
          (str "Tenant " tenant)])
       (into [layout/row]
             (for [role roles]
               ^{:key role}
               [RawChip {:label role :color (if (admin-label? role) "primary" "default")}]))]])))

(defn- persona-login
  []
  (let [personas
        @(rf/subscribe [::subs/personas])

        username
        @(rf/subscribe [::subs/username])

        personas-error
        @(rf/subscribe [::subs/personas-error])

        error
        @(rf/subscribe [::data/error ::events/login])

        submitting?
        @(rf/subscribe [::data/busy? ::events/login])]
    [:form {:on-submit (fn [e]
                         (.preventDefault e)
                         (rf/dispatch [::events/submit]))}
     [RawStack {:spacing 3}
      [RawTypography {:variant "body2" :color "text.secondary"}
       "Test mode: no password needed. Pick a test user to see the platform with their tenant and roles."]
      [:> Autocomplete
       {:freeSolo true
        :id (el/id els/username)
        :options (clj->js personas)
        :getOptionLabel (fn [option] (if (string? option) option (.-username option)))
        :inputValue username
        :onInputChange (fn [_ value] (rf/dispatch [::events/set-username value]))
        :renderOption persona-option
        :renderInput (fn [params]
                       (r/create-element TextField
                                         (js/Object.assign params #js {:label "Username"
                                                                       :autoFocus true
                                                                       :required true})))}]
      (when personas-error
        [RawAlert {:severity "warning" :el-id els/personas-error}
         (str "Could not list test users; type a name instead. " personas-error)])
      [layout/error-alert error els/error]
      [RawButton {:type "submit" :variant "contained" :size "large" :el-id els/submit
                  :loading submitting? :disabled (string/blank? username)}
       "Log in"]]]))

(defn- provider-login
  []
  (let [next-path
        @(rf/subscribe [::subs/next])

        finishing?
        @(rf/subscribe [::data/busy? ::events/login])

        error
        @(rf/subscribe [::data/error ::events/login])]
    [RawStack {:spacing 3}
     [RawTypography {:variant "body2" :color "text.secondary"}
      "Sign in with your organisation's account. Your tenant, teams and roles come from it."]
     [layout/error-alert error els/error]
     [RawButton {:variant "contained" :size "large" :el-id els/sign-in
                 :href (events/sign-in-url next-path) :loading finishing?}
      "Sign in"]]))

(defn- provider-managed
  []
  [RawAlert {:severity "info" :el-id els/provider-managed}
   "Your identity provider signs you in. Open the platform through your organisation's portal."])

(defn main-panel
  []
  (let [mode
        @(rf/subscribe [::subs/mode])

        refused
        @(rf/subscribe [::subs/refused])]
    [RawBox {:sx (:centered-screen theme/sx) :el-id els/page}
     [:> Paper {:sx (:login-card theme/sx)}
      [RawStack {:spacing 3}
       [RawStack {:spacing 1.5}
        [RawAvatar {:variant "rounded" :sx (:brand-mark-large theme/sx)}
         [:> HubIcon]]
        [RawTypography {:variant "h1"} "Log in to Agent Platform"]]
       [layout/error-alert refused els/refused]
       (case mode
         :oidc [provider-login]
         :jwt [provider-managed]
         :login [persona-login]
         [layout/loading])]]]))
