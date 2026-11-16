(ns manager-web.team-sessions-it
  "A team admin starts a session in the team's scope; a member works in
   it; someone of the tenant outside the team sees it, may not work in it and
   forks it into their own team; nobody of another tenant sees it."
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.run.elements :as run-els]
            [manager-web.session.elements :as els]
            [manager-web.sessions.elements :as sessions-els]
            [manager-web.team.elements :as team-els]
            [testing.pw :refer [with-ui step]]))

(def session-id
  (atom nil))

(def fork-id
  (atom nil))

(defn- goto-session
  [id]
  (h/goto (str "/sessions/" id))
  (h/wait-any els/page els/error))

(deftest team-sessions-it
  (with-ui [_page]
    (try
      (step "A team admin starts a team session shared with the tenant"
            (fn []
              (h/login (:tenant-admin h/fixture))
              (h/goto (str "/teams/" (:team h/fixture)))
              (h/click (team-els/tab :sessions))
              (h/click team-els/start-team-session)
              (h/click (team-els/team-session-workflow (:session-workflow h/fixture)))
              (h/wait-visible component-els/run-dialog)
              (h/select-option component-els/run-dialog-visibility (component-els/run-dialog-visibility-option :tenant))
              (let [before
                    (h/current-path)]
                (h/click component-els/run-dialog-submit)
                (h/wait-path-change before))
              (h/wait-visible run-els/page)
              (h/wait-visible run-els/session-link h/session-timeout-ms)
              (h/click run-els/session-link)
              (h/wait-visible els/page)
              (reset! session-id (h/path-id))
              (is
               (true?
                (.isVisible (h/wait-visible els/team-session))))))

      (step "Sessions lists it as the team's, with its team"
            (fn []
              (h/goto "/sessions")
              (h/wait-visible sessions-els/page)
              (h/wait-visible (component-els/session-link @session-id))
              (is
               (= (:team h/fixture)
                  (h/attribute (component-els/session-team @session-id) "data-team")))
              (is
               (= "team"
                  (h/attribute (component-els/session-owner @session-id) "data-owner")))))

      (step "A member of the team may work in it"
            (fn []
              (h/logout)
              (h/login (:member h/fixture))
              (goto-session @session-id)
              (h/wait-visible els/team-session)
              (is
               (false?
                (h/visible? els/blocked)))
              (h/goto (str "/teams/" (:team h/fixture)))
              (h/click (team-els/tab :sessions))
              (is
               (true?
                (.isVisible (h/wait-visible (component-els/session-link @session-id)))))
              (is
               (false?
                (h/visible? team-els/start-team-session)))))

      (step "Someone of the tenant outside the team sees why they can't work in it"
            (fn []
              (h/logout)
              (h/login (:outside-team h/fixture))
              (goto-session @session-id)
              (is
               (= "team-only"
                  (h/attribute els/blocked "data-reason")))
              (is
               (false?
                (h/visible? (component-els/session-action :open @session-id))))
              (is
               (false?
                (h/visible? els/sharing)))))

      (step "They fork it to continue in their own private copy, in their own team"
            (fn []
              (is
               (false?
                (h/visible? (component-els/session-action :fork @session-id)))
               "the banner offers the fork, not the header too")
              (h/click els/fork-to-continue)
              (is
               (true?
                (.isVisible (h/wait-visible component-els/fork-dialog))))
              (h/click component-els/fork-dialog-submit)
              (h/wait-until "the fork's page" 60000 100 #(not= @session-id (h/path-id)))
              (h/wait-visible els/page)
              (reset! fork-id (h/path-id))
              (is
               (not= @session-id
                     (h/path-id)))
              (is
               (true?
                (.isVisible (h/wait-visible els/forked-from))))
              (is
               (= (:outside-team-member-of h/fixture)
                  (h/attribute els/team-link "data-team"))
               "their only team was chosen for them")
              (is
               (false?
                (h/visible? els/team-session)))))

      (step "Nobody of another tenant sees it"
            (fn []
              (h/logout)
              (h/login (:other-tenant-member h/fixture))
              (goto-session @session-id)
              (is
               (true?
                (.isVisible (h/wait-visible els/error))))
              (h/goto "/sessions")
              (h/wait-any component-els/sessions-table component-els/sessions-empty)
              (is
               (false?
                (h/visible? (component-els/session-link @session-id))))))

      (step "The team admin deletes it"
            (fn []
              (h/logout)
              (h/login (:tenant-admin h/fixture))
              (goto-session @session-id)
              (h/click (component-els/session-action :delete @session-id))
              (h/click component-els/confirm-accept)
              (h/wait-visible sessions-els/page)
              (h/wait-hidden (component-els/session-link @session-id) h/session-timeout-ms)
              (reset! session-id nil)))
      (finally
        (h/delete-sessions! (:outside-team h/fixture) [@fork-id])
        (h/delete-sessions! (:tenant-admin h/fixture) [@session-id])))))
