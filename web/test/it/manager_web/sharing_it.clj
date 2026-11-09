(ns manager-web.sharing-it
  "A private session shared with the team, then with the whole tenant."
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.run.elements :as run-els]
            [manager-web.session.elements :as els]
            [manager-web.sessions.elements :as sessions-els]
            [testing.pw :refer [with-ui step]]))

(def session-id
  (atom nil))

(defn- goto-session
  [id]
  (h/goto (str "/sessions/" id))
  (h/wait-any els/page els/error))

(defn- as
  [username]
  (h/logout)
  (h/login username))

(defn- share
  [visibility]
  (as (:member h/fixture))
  (goto-session @session-id)
  (h/select-option els/sharing (els/sharing-option visibility))
  (when (= :tenant visibility)
    (h/wait-visible component-els/confirm-dialog)
    (h/click component-els/confirm-accept)
    (h/wait-hidden component-els/confirm-dialog))
  (h/wait-hidden els/sharing-saving))

(deftest sharing-it
  (with-ui [_page]
    (try
      (step "A member starts a workspace, private to them"
            (fn []
              (h/login (:member h/fixture))
              (h/goto (str "/teams/" (:team h/fixture)))
              (h/start-workflow (:team h/fixture) (:session-workflow h/fixture) {:title "Sharing UI test"})
              (h/wait-visible run-els/session-link h/session-timeout-ms)
              (h/click run-els/session-link)
              (h/wait-visible els/page)
              (reset! session-id (h/path-id))
              (h/click els/sharing)
              (is
               (= "true"
                  (h/wait-attribute (els/sharing-option :private) "aria-selected" "true")))
              (is
               (= (str "Everyone in " (:tenant h/fixture))
                  (h/text (els/sharing-option :tenant))))
              (.press (h/locator (els/sharing-option :private)) "Escape")))

      (step "Someone outside the team can't see a private session"
            (fn []
              (as (:teamless h/fixture))
              (is
               (= els/error
                  (goto-session @session-id)))))

      (step "Shared with the team, it is still hidden from people outside it"
            (fn []
              (share :team)
              (as (:teamless h/fixture))
              (is
               (= els/error
                  (goto-session @session-id)))))

      (step "Shared with the tenant, everyone in the tenant sees it"
            (fn []
              (share :tenant)
              (as (:teamless h/fixture))
              (is
               (= els/page
                  (goto-session @session-id)))
              (h/goto "/sessions")
              (is
               (true?
                (.isVisible (h/wait-visible (component-els/session-link @session-id)))))))

      (step "Nobody of another tenant sees it"
            (fn []
              (as (:other-tenant-member h/fixture))
              (is
               (= els/error
                  (goto-session @session-id)))))

      (step "The owner deletes it"
            (fn []
              (as (:member h/fixture))
              (goto-session @session-id)
              (h/click (component-els/session-action :delete @session-id))
              (h/click component-els/confirm-accept)
              (h/wait-visible sessions-els/page)
              (h/wait-hidden (component-els/session-link @session-id) h/session-timeout-ms)
              (reset! session-id nil)))
      (finally
        (h/delete-sessions! (:member h/fixture) [@session-id])))))

(def run-sessions
  (atom []))

(defn- start-shared-with
  "Starts the team's workspace with who sees it chosen in the dialog; on its run page."
  [visibility title]
  (h/goto (str "/teams/" (:team h/fixture)))
  (h/click (component-els/workflow-start (:team h/fixture) (:session-workflow h/fixture)))
  (h/wait-visible component-els/run-dialog)
  (h/fill (component-els/schema-field "title") title)
  (h/select-option component-els/run-dialog-visibility (component-els/run-dialog-visibility-option visibility))
  (let [before
        (h/current-path)]
    (h/click component-els/run-dialog-submit)
    (h/wait-path-change before))
  (h/wait-visible run-els/page))

(defn- open-run-session
  "Opens the session of the run on the page; its id."
  []
  (h/wait-visible run-els/session-link h/session-timeout-ms)
  (h/click run-els/session-link)
  (h/wait-visible els/page)
  (swap! run-sessions conj (h/path-id))
  (h/path-id))

(defn- shared-with-team?
  []
  (h/click els/sharing)
  (let [selected
        (h/wait-attribute (els/sharing-option :team) "aria-selected" "true")]
    (.press (h/locator (els/sharing-option :team)) "Escape")
    (= "true" selected)))

(deftest run-sharing-it
  (with-ui [_page]
    (try
      (step "A member starts a workspace and chooses that the team sees it"
            (fn []
              (h/login (:member h/fixture))
              (start-shared-with :team "Run sharing UI test")
              (open-run-session)
              (is
               (true?
                (shared-with-team?)))))

      (step "A team admin may work in it; someone outside the team doesn't see it"
            (fn []
              (as (:tenant-admin h/fixture))
              (is
               (= els/page
                  (goto-session (first @run-sessions))))
              (is
               (true?
                (.isVisible (h/wait-visible (component-els/session-action :open (first @run-sessions))))))
              (as (:teamless h/fixture))
              (is
               (= els/error
                  (goto-session (first @run-sessions))))))
      (finally
        (h/delete-sessions! (:member h/fixture) @run-sessions)))))
