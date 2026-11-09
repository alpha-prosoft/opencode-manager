(ns manager-web.urls-it
  "The tenant in the URL: where people land, deep links through the login,
   URLs of earlier versions, and URLs of another tenant."
  (:require [clojure.test :refer [deftest is]]
            [manager-web.helpers :as h]
            [manager-web.home.elements :as home-els]
            [manager-web.login.elements :as login-els]
            [manager-web.run.elements :as run-els]
            [manager-web.runs.elements :as runs-els]
            [manager-web.session.elements :as session-els]
            [manager-web.setup.elements :as setup-els]
            [manager-web.shell.elements :as shell-els]
            [manager-web.team.elements :as team-els]
            [manager-web.tenants.elements :as tenants-els]
            [testing.pw :refer [with-ui step]]))

(def tenant
  (:tenant h/fixture))

(defn- wait-path
  [path]
  (h/wait-until (str "the path " path) h/default-timeout-ms 100 #(= path (h/current-path))))

(deftest tenant-in-the-url-it
  (with-ui [_page]
    (step "After logging in, Home is the tenant's own URL"
          (fn []
            (h/login (:member h/fixture))
            (h/wait-visible home-els/page)
            (is
             (wait-path (str "/" tenant)))))

    (step "A deep link survives the login"
          (fn []
            (h/logout)
            (h/goto (str "/" tenant "/runs"))
            (h/wait-visible login-els/page)
            (if (h/oidc-mode?)
              (h/sign-in-at-keycloak (:member h/fixture))
              (do (h/fill login-els/username (:member h/fixture))
                  (.press (h/locator login-els/username) "Escape")
                  (h/click login-els/submit)))
            (when (= setup-els/page (h/wait-any runs-els/page setup-els/page))
              (h/click setup-els/later))
            (h/wait-visible runs-els/page)
            (is
             (wait-path (str "/" tenant "/runs")))))

    (step "URLs of earlier versions lead to the same page under the tenant"
          (fn []
            (h/goto "/runs")
            (h/wait-visible runs-els/page)
            (is
             (wait-path (str "/" tenant "/runs")))
            (h/goto (str "/teams/" (:team h/fixture)))
            (h/wait-visible team-els/page)
            (is
             (wait-path (str "/" tenant "/" (:team h/fixture))))
            (h/goto (str "/" tenant "/teams/" (:team h/fixture)))
            (h/wait-visible team-els/page)
            (is
             (wait-path (str "/" tenant "/" (:team h/fixture))))))

    (step "Old URLs of one run or session keep their id, and a query stays with the page"
          (fn []
            (let [run-id
                  (str (random-uuid))

                  session-id
                  (str (random-uuid))]
              (h/goto (str "/runs/" run-id))
              (h/wait-any run-els/page run-els/error)
              (is
               (wait-path (str "/" tenant "/runs/" run-id)))
              (h/goto (str "/sessions/" session-id))
              (h/wait-any session-els/page session-els/error)
              (is
               (wait-path (str "/" tenant "/sessions/" session-id)))
              (h/goto (str "/runs?team=" (:team h/fixture)))
              (h/wait-visible runs-els/page)
              (is
               (wait-path (str "/" tenant "/runs")))
              (is
               (= (str "?team=" (:team h/fixture))
                  (h/search))))))

    (step "A deep link to one run survives the login too"
          (fn []
            (let [run-path
                  (str "/" tenant "/runs/" (random-uuid))]
              (h/logout)
              (h/goto run-path)
              (h/wait-visible login-els/page)
              (if (h/oidc-mode?)
                (h/sign-in-at-keycloak (:member h/fixture))
                (do (h/fill login-els/username (:member h/fixture))
                    (.press (h/locator login-els/username) "Escape")
                    (h/click login-els/submit)))
              (when (= setup-els/page (h/wait-any run-els/page run-els/error setup-els/page))
                (h/click setup-els/later))
              (h/wait-any run-els/page run-els/error)
              (is
               (wait-path run-path)))))

    (step "A URL that names no page says so inside the app and leads back Home"
          (fn []
            (h/goto (str "/" tenant "/" (:team h/fixture) "/demo-service/no-such-page"))
            (h/wait-visible shell-els/not-found)
            (is
             (true? (h/visible? shell-els/user-name)))
            (h/click shell-els/not-found-home)
            (h/wait-visible home-els/page)
            (is
             (wait-path (str "/" tenant)))))

    (step "A run or session URL whose id is no id says that it wasn't found"
          (fn []
            (h/goto (str "/" tenant "/runs/not-a-run"))
            (is
             (true? (.isVisible (h/wait-visible run-els/not-found))))
            (h/goto (str "/" tenant "/sessions/not-a-session"))
            (is
             (true? (.isVisible (h/wait-visible session-els/not-found))))))

    (step "Another tenant's URL shows that it isn't theirs, and nothing of it"
          (fn []
            (h/goto (str "/" (:other-tenant h/fixture) "/runs"))
            (h/wait-visible shell-els/foreign-tenant)
            (is
             (false? (h/visible? runs-els/page)))
            (h/goto (str "/" (:other-tenant h/fixture) "/" (:other-team h/fixture)))
            (h/wait-visible shell-els/foreign-tenant)
            (is
             (false? (h/visible? team-els/page)))
            (h/click shell-els/foreign-tenant-home)
            (h/wait-visible home-els/page)
            (is
             (wait-path (str "/" tenant)))))

    (step "The operator pages' old and short URLs lead to the platform's tenants"
          (fn []
            (h/logout)
            (h/login (:operator h/fixture))
            (doseq [path ["/tenants" "/platform"]]
              (h/goto path)
              (h/wait-visible tenants-els/page)
              (is
               (wait-path "/platform/tenants")
               path))))))
