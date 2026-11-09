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

(defn- persona-option
  [props option]
  (let [{:keys [username roles]}
        (js->clj option :keywordize-keys true)

        li-props
        (dissoc (js->clj props) "key")]
    (r/as-element
     [:li (merge li-props {:key username :data-testid (el/id (els/persona username))})
      [RawStack {:spacing 0.5}
       [RawTypography {:sx (:strong theme/sx)} username]
       (into [layout/row]
             (for [role roles]
               ^{:key role}
               [RawChip {:label role :color (if (= role "Platform admin") "primary" "default")}]))]])))

(defn main-panel
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
    [RawBox {:sx (:centered-screen theme/sx) :el-id els/page}
     [:> Paper {:sx (:login-card theme/sx)}
      [:form {:on-submit (fn [e]
                           (.preventDefault e)
                           (rf/dispatch [::events/submit]))}
       [RawStack {:spacing 3}
        [RawStack {:spacing 1.5}
         [RawAvatar {:variant "rounded" :sx (:brand-mark-large theme/sx)}
          [:> HubIcon]]
         [RawTypography {:variant "h1"} "Log in to Agent Platform"]
         [RawTypography {:variant "body2" :color "text.secondary"}
          "Test mode: no password needed. Pick someone to see the platform with their role, or type any name."]]
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
         "Log in"]]]]]))
