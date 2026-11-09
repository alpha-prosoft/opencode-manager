(ns manager-web.core
  (:require [edd.core :as edd]
            [edd.subs :as edd-subs]
            [edd.util :as edd-util]
            [manager-web.accounts.core :as accounts]
            [manager-web.activities.core :as activities]
            [manager-web.admin.core :as admin]
            [manager-web.components.layout :as layout]
            [manager-web.harnesses.core :as harnesses]
            [manager-web.home.core :as home]
            [manager-web.login.core :as login]
            [manager-web.run.core :as run]
            [manager-web.runs.core :as runs]
            [manager-web.session :as session]
            [manager-web.session.core :as session-page]
            [manager-web.sessions.core :as sessions]
            [manager-web.setup.core :as setup]
            [manager-web.shell.views :as shell]
            [manager-web.team.core :as team]
            [manager-web.teams.core :as teams]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [manager-web.shell.elements :as shell-els]
            [web.primitives.components :refer [RawBox RawButton RawStack RawThemeProvider]]
            ["@mui/material/CssBaseline" :default CssBaseline]))

(def routes
  [["/" :home]
   ["/login" :login]
   ["/setup" :setup]
   ["/activities" :activities]
   ["/harnesses" :harnesses]
   ["/accounts" :accounts]
   ["/teams" :teams]
   ["/teams/:team-id" :team]
   ["/runs" :runs]
   ["/runs/:run-id" :run]
   ["/sessions" :sessions]
   ["/sessions/:session-id" :session]
   ["/admin" :admin]])

(def pages
  {:home home/main
   :login login/main
   :setup setup/main
   :activities activities/main
   :harnesses harnesses/main
   :accounts accounts/main
   :teams teams/main
   :team team/main
   :runs runs/main
   :run run/main
   :sessions sessions/main
   :session session-page/main
   :admin admin/main})

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
      (= :ready status) [shell/shell [edd-util/placeholder ctx]]
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
             :routes routes
             :languages [:en]
             :translations {}
             :name "manager-web"
             :body body})
  (rf/dispatch [::session/load-me]))

(defn reload
  []
  (rf/clear-subscription-cache!)
  (init))
