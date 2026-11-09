(ns manager-web.login-it
  "Login and logout in either mode; picking a test persona and the username
   form are login mode only and skip themselves in oidc mode (oidc_it covers
   Keycloak's side)."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-web.harnesses.elements :as harnesses-els]
            [manager-web.helpers :as h]
            [manager-web.login.elements :as login-els]
            [manager-web.setup.elements :as setup-els]
            [manager-web.shell.elements :as shell-els]
            [testing.pw :refer [with-ui step]]))

(defn- login-mode-steps
  []
  (step "Picking a test persona from the list logs in as them"
        (fn []
          (h/click login-els/username)
          (is
           (true?
            (.isVisible (h/wait-visible (login-els/persona-tenant (:member h/fixture))))))
          (h/click (login-els/persona (:member h/fixture)))
          (is
           (= (:member h/fixture)
              (h/wait-until "the picked username" 5000
                            #(let [value (h/input-value login-els/username)]
                               (when (= (:member h/fixture) value) value)))))
          (h/click login-els/submit)
          (when (= setup-els/page (h/wait-any shell-els/user-name setup-els/page))
            (h/click setup-els/later))
          (is
           (= (:member h/fixture)
              (string/lower-case (h/text shell-els/user-name))))
          (is
           (false?
            (h/visible? (shell-els/nav-item :admin))))))

  (step "After logging in, people return to the page they asked for"
        (fn []
          (h/logout)
          (h/goto "/harnesses")
          (h/wait-visible login-els/page)
          (h/fill login-els/username (:member h/fixture))
          (.press (h/locator login-els/username) "Escape")
          (h/click login-els/submit)
          (when (= setup-els/page (h/wait-any harnesses-els/page setup-els/page))
            (h/click setup-els/later))
          (is
           (true?
            (.isVisible (h/wait-visible harnesses-els/page)))))))

(deftest login-and-logout-it
  (with-ui [_page]
    (step "Anonymous visitors are sent to the login page"
          (fn []
            (h/goto "/runs")
            (h/wait-visible login-els/page)))

    (step "Logging in shows the frame with the user, their role and tenant"
          (fn []
            (h/login (:tenant-admin h/fixture))
            (is
             (= (:tenant-admin h/fixture)
                (string/lower-case (h/text shell-els/user-name))))
            (is
             (= (str "Tenant admin of " (:tenant h/fixture))
                (h/text shell-els/user-role)))
            (is
             (= (:tenant h/fixture)
                (h/text shell-els/tenant-name)))))

    (step "Someone of another tenant sees their own tenant"
          (fn []
            (h/logout)
            (h/login (:other-tenant-admin h/fixture))
            (is
             (= (:other-tenant h/fixture)
                (h/text shell-els/tenant-name)))))

    (step "Logging out returns to the login page"
          (fn []
            (h/click shell-els/logout)
            (is
             (true?
              (.isVisible (h/wait-visible (if (h/oidc-mode?) login-els/sign-in login-els/submit) 30000))))))

    (if (h/oidc-mode?)
      (step "The persona list and the username form are off in oidc mode"
            (fn []
              (is
               (false?
                (h/visible? login-els/username)))))
      (login-mode-steps))))

