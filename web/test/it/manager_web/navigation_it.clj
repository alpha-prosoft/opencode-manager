(ns manager-web.navigation-it
  (:require [clojure.test :refer [deftest is]]
            [manager-web.admin.elements :as admin-els]
            [manager-web.accounts.elements :as accounts-els]
            [manager-web.activities.elements :as activities-els]
            [manager-web.harnesses.elements :as harnesses-els]
            [manager-web.helpers :as h]
            [manager-web.home.elements :as home-els]
            [manager-web.runs.elements :as runs-els]
            [manager-web.sessions.elements :as sessions-els]
            [manager-web.shell.elements :as shell-els]
            [manager-web.teams.elements :as teams-els]
            [manager-web.tenants.elements :as tenants-els]
            [testing.pw :refer [with-ui step]]))

(def pages
  [[:runs runs-els/page]
   [:sessions sessions-els/page]
   [:teams teams-els/page]
   [:harnesses harnesses-els/page]
   [:activities activities-els/page]
   [:accounts accounts-els/page]
   [:home home-els/page]])

(deftest sidebar-navigation-it
  (with-ui [_page]
    (step "Log in"
          (fn []
            (h/login (:member h/fixture))))

    (doseq [[page header] pages]
      (step (str "The sidebar opens " (name page))
            (fn []
              (h/click (shell-els/nav-item page))
              (h/wait-visible header)
              (is
               (true?
                (h/visible? header))))))))

(deftest admin-navigation-it
  (with-ui [_page]
    (step "Tenant admins also get the Admin page"
          (fn []
            (h/login (:tenant-admin h/fixture))
            (h/click (shell-els/nav-item :admin))
            (is
             (true?
              (.isVisible (h/wait-visible admin-els/page))))))

    (step "Operators get the Tenants page with every tenant"
          (fn []
            (h/logout)
            (h/login (:operator h/fixture))
            (h/click (shell-els/nav-item :tenants))
            (h/wait-visible tenants-els/page)
            (is
             (= "Platform operator"
                (h/text shell-els/user-role)))
            (is
             (true?
              (.isVisible (h/wait-visible (tenants-els/row (:tenant h/fixture))))))
            (is
             (true?
              (h/visible? (tenants-els/row (:other-tenant h/fixture)))))))))
