(ns manager-web.required-credentials-it
  "Credentials a person must add themselves: acme requires PagerDuty of
   everyone in ops (tenant.json) and ops requires Jira of its members (its
   team.json). While henry lacks one, every page sends him to setup, which
   won't let him leave it for later."
  (:require [clojure.test :refer [deftest is]]
            [manager-web.accounts.elements :as accounts-els]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.login.elements :as login-els]
            [manager-web.setup.elements :as setup-els]
            [manager-web.shell.elements :as shell-els]
            [manager-web.team.elements :as team-els]
            [testing.pw :refer [with-ui step]]))

(def person
  (:required-member h/fixture))

(def team-id
  (:required-team h/fixture))

(def workflow-id
  (:required-workflow h/fixture))

(def tenant-required
  (:tenant-required-credential h/fixture))

(def team-required
  (:team-required-credential h/fixture))

(defn- remove-own!
  [credential-id]
  (h/wait-visible (accounts-els/credential credential-id))
  (when (h/visible? (accounts-els/remove-credential credential-id))
    (h/click (accounts-els/remove-credential credential-id))
    (h/wait-visible component-els/confirm-dialog)
    (h/click component-els/confirm-accept)
    (h/wait-hidden component-els/confirm-dialog)
    (h/wait-hidden (accounts-els/remove-credential credential-id))))

(defn- save-step!
  [credential-id username secret]
  (h/wait-visible (setup-els/step credential-id))
  (when username
    (h/fill component-els/credential-username username))
  (h/fill component-els/credential-secret secret)
  (h/click component-els/credential-submit))

(deftest required-credentials-it
  (with-ui [_page]
    (step "With his own values the app is open to henry"
          (fn []
            (h/login person)
            (is
             (true? (h/visible? shell-els/user-name)))))

    (step "Accounts and team settings say who requires them"
          (fn []
            (h/goto "/accounts")
            (is
             (true? (.isVisible (h/wait-visible (accounts-els/required tenant-required)))))
            (is
             (true? (h/visible? (accounts-els/required-by team-required))))
            (h/goto (str "/teams/" team-id "?tab=settings"))
            (is
             (true? (.isVisible (h/wait-visible (team-els/required-source tenant-required :tenant)))))
            (is
             (true? (h/visible? (team-els/required-source team-required :team))))
            (is
             (= "own"
                (h/attribute (team-els/required-own-state team-required) "data-state")))))

    (step "Removing one closes the app: setup asks for it and won't leave it for later"
          (fn []
            (h/goto "/accounts")
            (remove-own! team-required)
            (h/wait-visible setup-els/page)
            (is
             (= "true"
                (h/attribute (setup-els/step team-required) "data-required")))
            (is
             (false? (h/enabled? setup-els/later)))
            (is
             (true? (h/visible? setup-els/required-note)))
            (is
             (false? (h/visible? (setup-els/skip team-required))))))

    (step "Every page sends the person back to setup"
          (fn []
            (doseq [path ["/" (str "/teams/" team-id) "/runs" "/sessions" "/accounts"]]
              (h/goto path)
              (h/wait-visible setup-els/page)
              (is
               (false? (h/visible? shell-els/user-name))
               (str path " stays closed")))))

    (step "Setup says who is signed in and lets them sign out; logging in again leads back to it"
          (fn []
            (is
             (true? (.isVisible (h/wait-visible setup-els/signed-in))))
            (h/click setup-els/logout)
            (h/wait-visible login-els/page 30000)
            (h/submit-login person)
            (h/wait-visible setup-els/page)))

    (step "Once added, the page they asked for opens and work can start"
          (fn []
            (h/goto (str "/teams/" team-id))
            (save-step! team-required "henry@example.com" "it-henry-jira")
            (when (= setup-els/later (h/wait-until "the team page or optional steps" h/default-timeout-ms 100
                                                   #(cond
                                                      (h/visible? team-els/page) team-els/page
                                                      (h/enabled? setup-els/later) setup-els/later)))
              (h/click setup-els/later))
            (h/wait-visible team-els/page)
            (h/click (component-els/workflow-start team-id workflow-id))
            (h/wait-visible component-els/run-dialog)
            (is
             (true? (h/enabled? component-els/run-dialog-submit)))
            (h/click component-els/run-dialog-cancel)
            (h/wait-hidden component-els/run-dialog)))))
