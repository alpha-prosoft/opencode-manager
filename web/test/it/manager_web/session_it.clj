(ns manager-web.session-it
  "An interactive session from start to delete: open, rename, share, the
   conversations, live activity and audit tabs, stop, fork, lock and delete."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.run.elements :as run-els]
            [manager-web.session.elements :as els]
            [manager-web.sessions.elements :as sessions-els]
            [testing.pw :refer [*current-page* with-ui step]])
  (:import [com.microsoft.playwright Page]))

(def session-id
  (atom nil))

(def fork-id
  (atom nil))

(defn- goto-session
  [id]
  (h/goto (str "/sessions/" id))
  (h/wait-visible els/page))

(defn- open-tab
  [tab]
  (h/click (els/tab tab))
  (h/wait-attribute (els/tab tab) "aria-selected" "true"))

(defn- audit-records-shown?
  "Re-opens the audit tab until it shows records; it loads once when opened."
  []
  (h/wait-until "audit records" h/session-timeout-ms 5000
                #(do (open-tab :overview)
                     (open-tab :audit)
                     (= component-els/audit-verification
                        (h/wait-any component-els/audit-verification component-els/audit-empty)))))

(deftest session-it
  (with-ui [_page]
    (step "Starting a workspace opens its run, which links to the new session"
          (fn []
            (h/login (:member h/fixture))
            (h/goto (str "/teams/" (:team h/fixture)))
            (h/start-workflow (:team h/fixture) (:session-workflow h/fixture)
                              {:title "UI test workspace"
                               :prompt "Use the bash tool to run: ls repo   Then reply with one short sentence."})
            (h/click run-els/session-link)
            (h/wait-visible els/page)
            (reset! session-id (h/path-id))
            (is
             (= :running
                (h/wait-status els/status :running h/session-timeout-ms)))))

    (step "The session opens in OpenCode on its own host"
          (fn []
            (let [^Page popup
                  (.waitForPopup ^Page *current-page* ^Runnable #(h/click (component-els/session-action :open @session-id)))]
              (.waitForLoadState popup)
              (is
               (string/starts-with? (.url popup) (str "http://" @session-id ".")))
              (.close popup))))

    (step "Team admins see a private session but are told why they can't work in it"
          (fn []
            (h/logout)
            (h/login (:platform-admin h/fixture))
            (goto-session @session-id)
            (is
             (true?
              (.isVisible (h/wait-visible els/blocked))))
            (is
             (true?
              (h/visible? (component-els/session-action :fork @session-id))))
            (is
             (false?
              (h/visible? (component-els/session-action :open @session-id))))
            (h/logout)
            (h/login (:member h/fixture))
            (goto-session @session-id)))

    (step "The owner can rename it"
          (fn []
            (h/click els/rename)
            (h/wait-visible els/rename-dialog)
            (h/fill els/rename-name "Renamed by the UI test")
            (h/fill els/rename-description "Checks renaming")
            (h/click els/rename-save)
            (h/wait-hidden els/rename-dialog)
            (is
             (= "Renamed by the UI test"
                (h/wait-until "the new title" 15000 #(let [title (h/text els/title)]
                                                        (when (= "Renamed by the UI test" title) title)))))))

    (step "The owner can share it with the team"
          (fn []
            (h/select-option els/sharing (els/sharing-option :team))
            (h/wait-hidden els/sharing-saving)
            (h/reload)
            (h/click els/sharing)
            (is
             (= "true"
                (h/wait-attribute (els/sharing-option :team) "aria-selected" "true")))
            (.press (h/locator (els/sharing-option :team)) "Escape")))

    (step "Conversations lists the chat the prompt started"
          (fn []
            (open-tab :conversations)
            (is
             (true?
              (.isVisible (h/wait-visible component-els/conversations-list h/session-timeout-ms))))))

    (step "Live activity shows the tools the agent used"
          (fn []
            (open-tab :activity)
            (h/wait-visible component-els/activity-timeline h/session-timeout-ms)
            (is
             (true?
              (h/visible? component-els/activity-tools)))
            (is
             (true?
              (h/visible? component-els/activity-state)))))

    (step "The audit trail is verified and downloads as JSON lines"
          (fn []
            (is
             (true?
              (audit-records-shown?)))
            (let [{:keys [file-name text]}
                  (h/download component-els/audit-download)]
              (is
               (= (str "audit-" @session-id ".jsonl")
                  file-name))
              (is
               (every? #(string/starts-with? % "{")
                       (string/split-lines (string/trim text)))))))

    (step "The owner can stop it, and start it again later"
          (fn []
            (open-tab :overview)
            (h/click (component-els/session-action :stop @session-id))
            (is
             (= :stopped
                (h/wait-status els/status :stopped h/session-timeout-ms)))
            (is
             (true?
              (.isVisible (h/wait-visible (component-els/session-action :start @session-id)))))))

    (step "Forking makes a private copy and opens it"
          (fn []
            (h/click (component-els/session-action :fork @session-id))
            (h/wait-path-change (str "/sessions/" @session-id))
            (h/wait-visible els/page)
            (reset! fork-id (h/path-id))
            (is
             (not= @session-id
                   @fork-id))
            (is
             (true?
              (.isVisible (h/wait-visible els/forked-from))))))

    (step "The fork can be deleted after confirming"
          (fn []
            (h/click (component-els/session-action :delete @fork-id))
            (h/wait-visible component-els/confirm-dialog)
            (h/click component-els/confirm-accept)
            (h/wait-visible sessions-els/page)
            (h/wait-any component-els/sessions-table component-els/sessions-empty)
            (h/wait-hidden (component-els/session-link @fork-id) h/session-timeout-ms)
            (is
             (false?
              (h/visible? (component-els/session-link @fork-id))))))

    (step "Locking freezes the session: nobody can work in it, it can still be forked"
          (fn []
            (goto-session @session-id)
            (h/click (component-els/session-action :lock @session-id))
            (h/wait-visible component-els/confirm-dialog)
            (h/click component-els/confirm-accept)
            (h/wait-visible els/locked)
            (is
             (true?
              (.isVisible (h/wait-visible els/blocked))))
            (is
             (false?
              (h/visible? (component-els/session-action :lock @session-id))))
            (is
             (true?
              (h/visible? (component-els/session-action :fork @session-id))))))

    (step "Deleting the session returns to the list without it"
          (fn []
            (h/click (component-els/session-action :delete @session-id))
            (h/click component-els/confirm-accept)
            (h/wait-visible sessions-els/page)
            (h/wait-any component-els/sessions-table component-els/sessions-empty)
            (h/wait-hidden (component-els/session-link @session-id) h/session-timeout-ms)
            (is
             (false?
              (h/visible? (component-els/session-link @session-id))))))))
