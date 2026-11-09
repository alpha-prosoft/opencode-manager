(ns manager-web.tenancy-it
  "Roles in the frame, the team context, the tenant admin's pages and
   tenant isolation."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-web.admin.elements :as admin-els]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.home.elements :as home-els]
            [manager-web.runs.elements :as runs-els]
            [manager-web.shell.elements :as shell-els]
            [manager-web.team.elements :as team-els]
            [manager-web.teams.elements :as teams-els]
            [testing.pw :refer [with-ui step]]))

(defn- open-admin-tab
  [tab]
  (h/click (admin-els/tab tab))
  (h/wait-attribute (admin-els/tab tab) "aria-selected" "true"))

(deftest roles-and-team-context-it
  (with-ui [_page]
    (step "A member sees their tenant and role, and no admin pages"
          (fn []
            (h/login (:member h/fixture))
            (is
             (= (:tenant h/fixture)
                (h/text shell-els/brand-tenant)))
            (is
             (string/starts-with? (h/text shell-els/user-role) "Member of "))
            (is
             (false?
              (h/visible? (shell-els/nav-item :admin))))
            (is
             (false?
              (h/visible? (shell-els/nav-item :tenants))))))

    (step "Someone in several teams narrows Home to one team"
          (fn []
            (h/logout)
            (h/login (:tenant-admin h/fixture))
            (is
             (= (str "Tenant admin of " (:tenant h/fixture))
                (h/text shell-els/user-role)))
            (h/wait-visible home-els/page)
            (h/wait-visible (component-els/workflow-team-link (:team h/fixture)))
            (h/select-option component-els/team-context (component-els/team-context-option "ops"))
            (h/wait-hidden (component-els/workflow-team-link (:team h/fixture)))
            (is
             (true?
              (.isVisible (h/wait-visible (component-els/workflow-team-link "ops")))))))

    (step "The team stays chosen on Runs"
          (fn []
            (h/click (shell-els/nav-item :runs))
            (h/wait-visible runs-els/page)
            (is
             (= "ops"
                (h/input-value component-els/team-context-value)))
            (h/select-option component-els/team-context (component-els/team-context-option "all"))
            (is
             (= "all"
                (h/input-value component-els/team-context-value)))))))

(deftest tenant-admin-it
  (with-ui [_page]
    (step "The admin pages are of the tenant and show its sync state"
          (fn []
            (h/login (:tenant-admin h/fixture))
            (h/click (shell-els/nav-item :admin))
            (h/wait-visible admin-els/page)
            (is
             (= "synced"
                (h/wait-until "the tenant to be synced" 120000 1000
                              #(when (= "synced" (h/attribute admin-els/sync-status "data-state")) "synced"))))))

    (step "Sync now asks for a sync of the tenant"
          (fn []
            (h/click admin-els/sync)
            (h/wait-until "the sync report" 60000
                          #(and (h/visible? admin-els/report) (= "sync" (h/attribute admin-els/report "data-action"))))
            (is
             (= "true"
                (h/attribute admin-els/report "data-ok")))))

    (step "People lists who was active in the last 30 days"
          (fn []
            (open-admin-tab :people)
            (is
             (true?
              (.isVisible (h/wait-visible admin-els/users-table))))))

    (step "Usage is summed per team of the tenant"
          (fn []
            (open-admin-tab :usage)
            (is
             (true?
              (.isVisible (h/wait-visible admin-els/team-usage-table))))))

    (step "Credentials, MCP servers and teams are the tenant's"
          (fn []
            (open-admin-tab :credentials)
            (is
             (true?
              (.isVisible (h/wait-visible (admin-els/set-credential (:checked-credential h/fixture))))))
            (open-admin-tab :mcp)
            (is
             (true?
              (.isVisible (h/wait-visible (admin-els/mcp-server (:mcp-server h/fixture))))))
            (open-admin-tab :teams)
            (is
             (true?
              (.isVisible (h/wait-visible (admin-els/team-link (:team h/fixture))))))
            (is
             (false?
              (h/visible? (admin-els/team-link (:other-team h/fixture)))))))

    (step "Team admins who aren't tenant admins get no admin pages"
          (fn []
            (h/logout)
            (h/login "dave")
            (is
             (string/starts-with? (h/text shell-els/user-role) "Admin of "))
            (h/goto "/admin")
            (is
             (true?
              (.isVisible (h/wait-visible admin-els/forbidden))))))))

(deftest tenant-isolation-it
  (with-ui [_page]
    (step "Someone of another tenant sees their own tenant only"
          (fn []
            (h/login (:other-tenant-admin h/fixture))
            (is
             (= (:other-tenant h/fixture)
                (h/text shell-els/brand-tenant)))
            (h/click (shell-els/nav-item :teams))
            (h/wait-visible (teams-els/team-card (:other-team h/fixture)))
            (is
             (false?
              (h/visible? (teams-els/team-card (:team h/fixture)))))))

    (step "Another tenant's team doesn't exist for them"
          (fn []
            (h/goto (str "/teams/" (:team h/fixture)))
            (is
             (true?
              (.isVisible (h/wait-visible team-els/error))))))

    (step "Their admin pages show none of the other tenant's teams or servers"
          (fn []
            (h/goto "/admin")
            (h/wait-visible admin-els/page)
            (open-admin-tab :teams)
            (h/wait-visible (admin-els/team-link (:other-team h/fixture)))
            (is
             (false?
              (h/visible? (admin-els/team-link (:team h/fixture)))))
            (open-admin-tab :mcp)
            (is
             (true?
              (.isVisible (h/wait-visible admin-els/mcp-empty))))
            (open-admin-tab :usage)
            (h/wait-any admin-els/team-usage-table admin-els/usage-empty)
            (is
             (false?
              (h/visible? (admin-els/team-usage (:team h/fixture)))))))))
