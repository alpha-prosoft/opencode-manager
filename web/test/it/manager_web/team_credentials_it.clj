(ns manager-web.team-credentials-it
  "Team settings and team credentials: a team admin sets the team's value, a
   member sees that it is set (never the value), adds their own where the
   credential allows it, and falls back to the team's when removing it."
  (:require [clojure.test :refer [deftest is]]
            [manager-web.accounts.elements :as accounts-els]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.team.elements :as els]
            [testing.pw :refer [with-ui step]]))

(def credential-id
  (:checked-credential h/fixture))

(def personal-only-id
  (:personal-credential h/fixture))

(defn- open-settings
  []
  (h/goto (str "/teams/" (:team h/fixture)))
  (h/wait-visible els/page)
  (h/click (els/tab :settings))
  (h/wait-attribute (els/tab :settings) "aria-selected" "true"))

(defn- state-of
  [element]
  (.getAttribute (h/raw-locator element) "data-state"))

(defn- wait-state
  [element state]
  (h/wait-until (str element " to be " state) 60000 250
                #(when (= state (state-of element)) state)))

(defn- save-secret
  [secret]
  (h/wait-visible component-els/credential-dialog)
  (h/fill component-els/credential-secret secret)
  (h/click component-els/credential-submit)
  (h/wait-hidden component-els/credential-dialog 60000))

(defn- confirm
  []
  (h/wait-visible component-els/confirm-dialog)
  (h/click component-els/confirm-accept)
  (h/wait-hidden component-els/confirm-dialog))

(deftest team-credentials-it
  (let [secret
        (h/read-secret credential-id)]
    (with-ui [_page]
      (try
        (step "Team settings show the team's budget, models, MCP servers and projects"
              (fn []
                (is
                 (some? secret))
                (h/login (:tenant-admin h/fixture))
                (open-settings)
                (is
                 (true?
                  (.isVisible (h/wait-visible (els/limit :daily-tokens)))))
                (is
                 (true?
                  (h/visible? (els/limit :running-sessions))))
                (is
                 (true?
                  (h/visible? els/budget)))
                (is
                 (true?
                  (h/visible? els/connections)))
                (is
                 (true?
                  (h/visible? els/mcp-servers)))
                (is
                 (true?
                  (h/visible? (els/project "demo-service"))))))

        (step "A team admin sets the team's value"
              (fn []
                (when (= "team" (state-of (els/credential-state credential-id)))
                  (h/click (els/clear-credential credential-id))
                  (confirm))
                (h/click (els/set-credential credential-id))
                (save-secret secret)
                (is
                 (= "team"
                    (wait-state (els/credential-state credential-id) "team")))))

        (step "A member sees that the team's value is used, and can't change it"
              (fn []
                (h/logout)
                (h/login (:member h/fixture))
                (open-settings)
                (is
                 (= "team"
                    (wait-state (els/credential-state credential-id) "team")))
                (is
                 (false?
                  (h/visible? (els/set-credential credential-id))))
                (is
                 (false?
                  (h/visible? (els/credential personal-only-id))))))

        (step "Accounts shows the team's value is used and offers a personal override"
              (fn []
                (h/click els/own-value-link)
                (h/wait-visible accounts-els/page)
                (when (= "own" (state-of (accounts-els/resolution credential-id)))
                  (h/click (accounts-els/remove-credential credential-id))
                  (confirm))
                (is
                 (= "team"
                    (wait-state (accounts-els/resolution credential-id) "team")))
                (is
                 (true?
                  (h/visible? (accounts-els/edit credential-id))))))

        (step "The member adds their own value, which then wins"
              (fn []
                (h/click (accounts-els/edit credential-id))
                (save-secret secret)
                (is
                 (= "own"
                    (wait-state (accounts-els/resolution credential-id) "own")))))

        (step "Removing it falls back to the team's value"
              (fn []
                (h/click (accounts-els/remove-credential credential-id))
                (confirm)
                (is
                 (= "team"
                    (wait-state (accounts-els/resolution credential-id) "team")))))

        (step "A credential only people provide has no team or tenant to fall back to"
              (fn []
                (is
                 (contains? #{"own" "missing"}
                            (state-of (accounts-els/resolution personal-only-id))))))

        (step "Clearing the team's value falls back to the tenant's"
              (fn []
                (h/logout)
                (h/login (:tenant-admin h/fixture))
                (open-settings)
                (h/click (els/clear-credential credential-id))
                (confirm)
                (is
                 (contains? #{"tenant" "missing"}
                            (h/wait-until "the team value to be gone" 60000 250
                                          #(let [state (state-of (els/credential-state credential-id))]
                                             (when (not= "team" state) state)))))))
        (finally
          (h/clean-up! (:member h/fixture)
                       [[:delete-my-credential nil (str "{\"credential-id\":\"" credential-id "\"}")]])
          (h/clean-up! (:tenant-admin h/fixture)
                       [[:delete-team-credential nil (str "{\"team-id\":\"" (:team h/fixture) "\",\"credential-id\":\"" credential-id "\"}")]]))))))
