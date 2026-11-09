(ns manager-web.core
  (:require [edd.core :as edd]
            [edd.subs :as edd-subs]
            [edd.util :as edd-util]
            [manager-web.accounts.core :as accounts]
            [manager-web.activities.core :as activities]
            [manager-web.admin.core :as admin]
            [manager-web.components.layout :as layout]
            [manager-web.components.required-credentials :as required-credentials]
            [manager-web.data :as data]
            [manager-web.harnesses.core :as harnesses]
            [manager-web.home.core :as home]
            [manager-web.login.core :as login]
            [manager-web.project.core :as project]
            [manager-web.projects.core :as projects]
            [manager-web.routes :as routes]
            [manager-web.run.core :as run]
            [manager-web.runs.core :as runs]
            [manager-web.session :as session]
            [manager-web.session.core :as session-page]
            [manager-web.sessions.core :as sessions]
            [manager-web.setup.core :as setup]
            [manager-web.shell.views :as shell]
            [manager-web.team.core :as team]
            [manager-web.tenants.core :as tenants]
            [manager-web.teams.core :as teams]
            [manager-web.theme :as theme]
            [manager-web.urls :as urls]
            [manager-web.webhooks.core :as webhooks]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [manager-web.shell.elements :as shell-els]
            [web.primitives.components :refer [RawBox RawButton RawStack RawThemeProvider]]
            ["@mui/material/CssBaseline" :default CssBaseline]))

(defn- redirecting
  [_classes]
  [layout/loading])

(defn- redirect-page
  [_ctx]
  {:init ::routes/entered
   :panel redirecting})

(def pages
  (merge {:home home/main
          :login login/main
          :setup setup/main
          :activities activities/main
          :harnesses harnesses/main
          :accounts accounts/main
          :teams teams/main
          :team team/main
          :project project/main
          :projects projects/main
          :runs runs/main
          :run run/main
          :sessions sessions/main
          :session session-page/main
          :admin admin/main
          :tenants tenants/main
          :webhooks webhooks/main}
         (zipmap routes/redirect-pages (repeat redirect-page))))

(def standalone-pages
  "Pages rendered without the navigation frame."
  #{:login :setup})

(defn- load-error
  [message]
  [RawBox {:sx (:centered-screen theme/sx)}
   [RawStack {:spacing 2 :sx (:start-aligned theme/sx)}
    [layout/error-alert (str "Could not load your account: " message) shell-els/load-error]
    [RawButton {:variant "contained" :el-id shell-els/retry
                :on-click #(rf/dispatch [::session/load-me])}
     "Try again"]]])

(defn- redirect
  [url]
  (r/with-let [_ (rf/dispatch [:edd.events/navigate {:url url :replace? true}])]
    [layout/loading]))

(defn- app
  [ctx me active-panel]
  (let [[kind url]
        (routes/destination me
                            active-panel
                            @(rf/subscribe [::routes/path-params])
                            @(rf/subscribe [::edd-subs/url-params]))]
    (case kind
      :redirect ^{:key url} [redirect url]
      :foreign [shell/shell [shell/not-your-tenant]]
      [shell/shell [edd-util/placeholder ctx]])))

(defn- current-location
  []
  (str (.-pathname js/location) (.-search js/location)))

(defn- credentials-error
  [message]
  [RawBox {:sx (:centered-screen theme/sx)}
   [RawStack {:spacing 2 :sx (:start-aligned theme/sx)}
    [layout/error-alert (str "Could not check the credentials your teams require: " message) shell-els/credentials-error]
    [RawStack {:direction "row" :spacing 1}
     [RawButton {:variant "contained" :el-id shell-els/credentials-retry
                 :on-click #(rf/dispatch [::data/refetch [:my-credentials]])}
      "Try again"]
     [RawButton {:el-id shell-els/credentials-logout
                 :loading @(rf/subscribe [::data/busy? ::session/logout])
                 :on-click #(rf/dispatch [::session/logout])}
      "Sign out"]]]])

(defn- gated
  "The app, or the way to setup while the person lacks a required credential."
  [ctx me active-panel]
  (let [load
        @(rf/subscribe [::data/load :my-credentials])]
    [:<>
     (when (:tenant me)
       [data/poll {:key :my-credentials :query required-credentials/query :interval 300000}])
     (case (required-credentials/gate me active-panel load)
       :open [app ctx me active-panel]
       :setup [redirect (urls/setup-url (current-location))]
       :failed [credentials-error (:error load)]
       [layout/loading])]))

(defn- content
  [ctx]
  (let [active-panel
        @(rf/subscribe [::edd-subs/active-panel])

        status
        @(rf/subscribe [::session/status])

        error
        @(rf/subscribe [::session/error])]
    (cond
      (contains? standalone-pages active-panel) [edd-util/placeholder ctx]
      (= :ready status) [gated ctx @(rf/subscribe [::session/me]) active-panel]
      (= :error status) [load-error error]
      :else [layout/loading])))

(defn body
  [ctx]
  [RawThemeProvider {:theme theme/theme}
   [:> CssBaseline]
   [content ctx]])

(defn ^:export init
  []
  (edd/init {:pages pages
             :routes routes/routes
             :languages [:en]
             :translations {}
             :name "manager-web"
             :error-pages {:not-found shell/not-found
                           :bad-request shell/bad-request}
             :body body})
  (rf/dispatch [::session/load-me]))

(defn reload
  []
  (rf/clear-subscription-cache!)
  (init))
