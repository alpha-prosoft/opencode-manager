(ns manager-web.session-it
  "An interactive session from start to delete: open, rename, share, the
   conversations, live activity and audit tabs, stop, fork, lock and delete."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-web.accounts.elements :as accounts-els]
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

(def personal-session-id
  (atom nil))

(def checked-credential
  (:checked-credential h/fixture))

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
    (try
      (step "Starting a workspace opens its run, which links to the new session"
            (fn []
              (h/login (:member h/fixture))
              (h/goto (str "/teams/" (:team h/fixture)))
              (h/start-workflow (:team h/fixture) (:session-workflow h/fixture)
                                {:title "UI test workspace"
                                 :prompt "Use the bash tool to run: ls repo   Then reply with one short sentence."})
              (h/wait-visible run-els/session-link h/session-timeout-ms)
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
              (h/login (:tenant-admin h/fixture))
              (goto-session @session-id)
              (is
               (= "private"
                  (h/attribute els/blocked "data-reason")))
              (is
               (true?
                (h/visible? els/admin-access)))
              (is
               (= "private"
                  (h/attribute els/visibility "data-visibility")))
              (is
               (true?
                (h/visible? els/fork-to-continue)))
              (is
               (false?
                (h/visible? (component-els/session-action :fork @session-id)))
               "the banner offers the fork, not the header too")
              (is
               (false?
                (h/visible? (component-els/session-action :open @session-id))))
              (is
               (false?
                (h/visible? els/sharing))
               "only the owner re-shares their session")
              (is
               (true?
                (h/visible? els/sharing-text)))
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

      (step "The owner starts it again"
            (fn []
              (h/click (component-els/session-action :start @session-id))
              (is
               (= :running
                  (h/wait-status els/status :running h/session-timeout-ms)))))

      (step "Forking makes a private copy and opens it"
            (fn []
              (h/click (component-els/session-action :fork @session-id))
              (h/wait-visible component-els/confirm-dialog)
              (h/click component-els/confirm-accept)
              (h/wait-until "the fork's page" 60000 100 #(not= @session-id (h/path-id)))
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
                (h/visible? (component-els/session-link @fork-id))))
              (reset! fork-id nil)))

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
                (h/visible? els/fork-to-continue)))))

      (step "Deleting the session returns to the list without it"
            (fn []
              (h/click (component-els/session-action :delete @session-id))
              (h/click component-els/confirm-accept)
              (h/wait-visible sessions-els/page)
              (h/wait-any component-els/sessions-table component-els/sessions-empty)
              (h/wait-hidden (component-els/session-link @session-id) h/session-timeout-ms)
              (is
               (false?
                (h/visible? (component-els/session-link @session-id))))
              (reset! session-id nil)))
      (finally
        (h/delete-sessions! (:member h/fixture) [@fork-id @session-id])))))

(deftest personal-credential-session-it
  (let [secret
        (h/read-secret checked-credential)]
    (with-ui [_page]
      (try
        (step "A member adds their own token and starts a workspace whose tools use it"
              (fn []
                (is
                 (some? secret))
                (h/login (:member h/fixture))
                (h/goto "/accounts")
                (h/click (accounts-els/edit checked-credential))
                (h/wait-visible component-els/credential-dialog)
                (h/fill component-els/credential-secret (str secret))
                (h/click component-els/credential-submit)
                (h/wait-hidden component-els/credential-dialog 60000)
                (h/goto (str "/teams/" (:team h/fixture)))
                (h/start-workflow (:team h/fixture) (:workflow h/fixture) {:title "Personal credential UI test"})
                (h/wait-visible run-els/session-link h/session-timeout-ms)
                (h/click run-els/session-link)
                (h/wait-visible els/page)
                (reset! personal-session-id (h/path-id))))

        (step "A team admin sees it, learns it works with the owner's credentials and may only fork it"
              (fn []
                (h/logout)
                (h/login (:tenant-admin h/fixture))
                (goto-session @personal-session-id)
                (is
                 (= "personal"
                    (h/attribute els/blocked "data-reason")))
                (is
                 (false?
                  (h/visible? (component-els/session-action :open @personal-session-id))))
                (is
                 (true?
                  (h/visible? els/fork-to-continue)))))
        (finally
          (h/clean-up! (:member h/fixture)
                       [[:delete-session @personal-session-id]
                        [:delete-my-credential nil (str "{\"credential-id\":\"" checked-credential "\"}")]]))))))
