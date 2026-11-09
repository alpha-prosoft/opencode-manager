(ns manager-web.oidc-it
  "Single sign-on through Keycloak against a stack started with
   compose.keycloak.yml (MANAGER_AUTH_MODE=oidc); skipped in other modes.

   Keycloak's login page is a third-party page without el-ids, so its inputs
   are found by the ids its login theme keeps stable (#username, #password,
   #kc-login): the one exception to finding elements by el-id."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-web.helpers :as h]
            [manager-web.login.elements :as login-els]
            [manager-web.shell.elements :as shell-els]
            [manager-web.team.elements :as team-els]
            [manager-web.teams.elements :as teams-els]
            [testing.pw :refer [with-ui step]])
  (:import [com.microsoft.playwright Locator$WaitForOptions]))

(defn- provider-logout
  "Logs out of the app and of Keycloak, back on the login page."
  []
  (h/click shell-els/logout)
  (h/wait-visible login-els/sign-in 30000))

(deftest single-sign-on-through-keycloak-it
  (if-not (h/oidc-mode?)
    (is
     (contains? #{"login" "jwt"} @h/auth-mode)
     "Skipped: the server is not in oidc mode (compose.keycloak.yml)")
    (with-ui [_page]
      (step "Anonymous visitors get the login page with a Sign in button, not the test personas"
            (fn []
              (h/goto "/runs")
              (h/wait-visible login-els/sign-in)
              (is
               (false?
                (h/visible? login-els/username)))))

      (step "alice signs in at Keycloak and is tenant admin of acme"
            (fn []
              (h/login "alice")
              (is
               (= ["Alice" "acme"]
                  [(h/text shell-els/user-name) (h/text shell-els/tenant-name)]))
              (is
               (true?
                (h/visible? (shell-els/nav-item :admin))))))

      (step "Logging out ends the Keycloak session too"
            (fn []
              (provider-logout)
              (h/click login-els/sign-in)
              (.waitFor (h/keycloak-locator :username) (doto (Locator$WaitForOptions.) (.setTimeout 30000.0)))
              (is
               (true?
                (.isVisible (h/keycloak-locator :password))))))

      (step "bob is a member of demo in acme, no admin, and sees no globex team"
            (fn []
              (h/login "bob")
              (is
               (= ["Bob" "acme"]
                  [(h/text shell-els/user-name) (h/text shell-els/tenant-name)]))
              (is
               (false?
                (h/visible? (shell-els/nav-item :admin))))
              (h/goto "/teams")
              (h/wait-visible (teams-els/team-card "demo"))
              (is
               (false?
                (h/visible? (teams-els/team-card "research"))))
              (h/goto "/teams/research")
              (is
               (true?
                (.isVisible (h/wait-visible team-els/error))))
              (provider-logout)))

      (step "gina is tenant admin of globex and sees its team"
            (fn []
              (h/login "gina")
              (is
               (= ["Gina" "globex"]
                  [(h/text shell-els/user-name) (h/text shell-els/tenant-name)]))
              (is
               (true?
                (h/visible? (shell-els/nav-item :admin))))
              (h/goto "/teams")
              (is
               (true?
                (.isVisible (h/wait-visible (teams-els/team-card "research")))))
              (provider-logout)))

      (step "An account without a tenant is refused and the login page says why"
            (fn []
              (h/submit-login "nobody")
              (is
               (string/includes? (h/text login-els/refused) "belongs to no tenant"))
              (is
               (true?
                (h/visible? login-els/sign-in))))))))
