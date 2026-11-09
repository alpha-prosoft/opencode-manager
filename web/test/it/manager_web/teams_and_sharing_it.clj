(ns manager-web.teams-and-sharing-it
  "People in several teams (through Keycloak in oidc mode, the dev users in
   login mode): their role in each team and the team switcher; sessions in
   both teams; a session shared private, then with its team, then with the
   tenant, seen or not by people of another team and of another tenant; a
   fork across teams that stays in the source's team."
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.home.elements :as home-els]
            [manager-web.run.elements :as run-els]
            [manager-web.session.elements :as els]
            [manager-web.team.elements :as team-els]
            [manager-web.teams.elements :as teams-els]
            [testing.pw :refer [with-ui step]]))

(def sessions
  (atom {}))

(defn- as
  [username]
  (h/logout)
  (h/login username))

(defn- goto-session
  [id]
  (h/goto (str "/sessions/" id))
  (h/wait-any els/page els/error))

(defn- start-session!
  "Starts the team's workspace from its team page and opens its session; returns the session id."
  [team-id title]
  (h/goto (str "/teams/" team-id))
  (h/start-workflow team-id (:session-workflow h/fixture) {:title title})
  (h/wait-visible run-els/session-link h/session-timeout-ms)
  (h/click run-els/session-link)
  (h/wait-visible els/page)
  (h/path-id))

(defn- share!
  [id visibility]
  (goto-session id)
  (h/select-option els/sharing (els/sharing-option visibility))
  (when (= :tenant visibility)
    (h/wait-visible component-els/confirm-dialog)
    (h/click component-els/confirm-accept)
    (h/wait-hidden component-els/confirm-dialog))
  (h/wait-hidden els/sharing-saving))

(defn- role-on-card
  [team-id]
  (h/attribute (teams-els/team-card team-id) "data-role"))

(deftest teams-and-sharing-it
  (with-ui [_page]
    (try
      (step "Someone in two teams sees exactly both, admin in one and member in the other"
            (fn []
              (h/login (:two-teams-admin h/fixture))
              (h/goto "/teams")
              (is
               (true?
                (.isVisible (h/wait-visible (teams-els/team-card "payments")))))
              (is
               (= ["admin" "member"]
                  [(role-on-card "payments") (role-on-card "ops")]))
              (is
               (false?
                (h/visible? (teams-els/team-card (:team h/fixture)))))))

      (step "The team switcher narrows Home to one of their teams"
            (fn []
              (h/goto "/")
              (h/wait-visible home-els/page)
              (h/wait-visible (component-els/workflow-team-link "payments"))
              (h/select-option component-els/team-context (component-els/team-context-option "ops"))
              (h/wait-hidden (component-els/workflow-team-link "payments"))
              (is
               (true?
                (.isVisible (h/wait-visible (component-els/workflow-team-link "ops")))))
              (is
               (false?
                (h/visible? (component-els/workflow-team-link (:team h/fixture)))))))

      (step "A member of two teams starts and works in sessions of both"
            (fn []
              (as (:two-teams-member h/fixture))
              (doseq [team-id [(:team h/fixture) "payments"]]
                (let [id
                      (start-session! team-id (str "Two teams UI test: " team-id))]
                  (swap! sessions assoc team-id id)
                  (is
                   (= team-id
                      (h/attribute els/team-link "data-team")))
                  (is
                   (true?
                    (h/visible? (component-els/session-action :open id))))))))

      (step "A private session is hidden from someone only in another team of the tenant"
            (fn []
              (as (:other-team-member h/fixture))
              (is
               (= els/error
                  (goto-session (get @sessions (:team h/fixture)))))))

      (step "Shared with its team, it stays hidden from other teams, with the team's page and credentials"
            (fn []
              (as (:two-teams-member h/fixture))
              (share! (get @sessions (:team h/fixture)) :team)
              (as (:other-team-member h/fixture))
              (is
               (= els/error
                  (goto-session (get @sessions (:team h/fixture)))))
              (h/goto (str "/teams/" (:team h/fixture)))
              (is
               (true?
                (.isVisible (h/wait-visible team-els/error))))))

      (step "Shared with the tenant, someone of another team sees it and forks it; the fork stays in the source's team"
            (fn []
              (as (:two-teams-member h/fixture))
              (share! (get @sessions (:team h/fixture)) :tenant)
              (as (:other-team-member h/fixture))
              (is
               (= els/page
                  (goto-session (get @sessions (:team h/fixture)))))
              (h/fork! (get @sessions (:team h/fixture)))
              (h/wait-visible els/forked-from)
              (swap! sessions assoc :fork (h/path-id))
              (is
               (= (:team h/fixture)
                  (h/attribute els/team-link "data-team")))
              (is
               (= "SPAN"
                  (.evaluate (h/locator els/team-link) "e => e.tagName"))
               "the team's page is closed to them, so its name is no link")))

      (step "Nobody of another tenant sees the tenant-shared session, even someone in two teams there"
            (fn []
              (as (:other-tenant-two-teams h/fixture))
              (is
               (= els/error
                  (goto-session (get @sessions (:team h/fixture)))))
              (h/goto "/teams")
              (is
               (= ["member" "admin"]
                  [(role-on-card "research") (role-on-card "lab")]))
              (is
               (false?
                (h/visible? (teams-els/team-card (:team h/fixture)))))))

      (step "The sessions are cleaned up"
            (fn []
              (as (:other-team-member h/fixture))
              (goto-session (:fork @sessions))
              (h/click (component-els/session-action :delete (:fork @sessions)))
              (h/click component-els/confirm-accept)
              (h/wait-hidden (component-els/session-link (:fork @sessions)) h/session-timeout-ms)
              (as (:two-teams-member h/fixture))
              (doseq [team-id [(:team h/fixture) "payments"]]
                (goto-session (get @sessions team-id))
                (h/click (component-els/session-action :delete (get @sessions team-id)))
                (h/click component-els/confirm-accept)
                (h/wait-hidden (component-els/session-link (get @sessions team-id)) h/session-timeout-ms))
              (is
               (false?
                (h/visible? (component-els/session-link (get @sessions "payments")))))
              (reset! sessions {})))
      (finally
        (h/delete-sessions! (:other-team-member h/fixture) [(:fork @sessions)])
        (h/delete-sessions! (:two-teams-member h/fixture) [(get @sessions (:team h/fixture)) (get @sessions "payments")])))))
