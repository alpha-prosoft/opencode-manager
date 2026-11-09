(ns manager-web.context-it
  "Where am I: the tenant chip and the sidebar team switcher with the role in
   each team, the team kept in the URL, the start dialog's team, credentials
   and sharing, scope markers, narrow screens and people without a team."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-web.admin.elements :as admin-els]
            [manager-web.components.elements :as component-els]
            [manager-web.harnesses.elements :as harnesses-els]
            [manager-web.helpers :as h]
            [manager-web.home.elements :as home-els]
            [manager-web.runs.elements :as runs-els]
            [manager-web.sessions.elements :as sessions-els]
            [manager-web.shell.elements :as shell-els]
            [manager-web.team.elements :as team-els]
            [manager-web.tenants.elements :as tenants-els]
            [testing.pw :refer [*current-page* with-ui step]])
  (:import [com.microsoft.playwright Page]))

(defn- url-team
  []
  (str (.evaluate ^Page *current-page* "() => new URLSearchParams(location.search).get('team') || ''")))

(defn- option-role
  [team-id]
  (h/click component-els/team-context)
  (let [role
        (h/attribute (component-els/team-context-option team-id) "data-role")]
    (.press (h/locator (component-els/team-context-option team-id)) "Escape")
    role))

(deftest context-it
  (with-ui [_page]
    (step "The frame names the tenant, and the switcher every team with the person's role in it"
          (fn []
            (h/login (:two-teams-admin h/fixture))
            (is
             (= (:tenant h/fixture)
                (h/text shell-els/brand-tenant)))
            (is
             (= "tenant"
                (h/attribute shell-els/brand-tenant "data-scope")))
            (is
             (= ["admin" "member"]
                [(option-role "payments") (option-role "ops")]))))

    (step "Choosing a team puts it in the URL; Home, Runs, Sessions and Harnesses keep it, also after a reload"
          (fn []
            (h/wait-visible home-els/page)
            (h/select-option component-els/team-context (component-els/team-context-option "ops"))
            (h/wait-until "the team in the URL" h/default-timeout-ms #(= "ops" (url-team)))
            (is
             (= "ops"
                (h/attribute home-els/context "data-team")))
            (h/click (shell-els/nav-item :runs))
            (h/wait-visible runs-els/page)
            (is
             (= ["ops" "ops"]
                [(url-team) (h/input-value component-els/team-context-value)]))
            (h/reload)
            (h/wait-visible runs-els/page)
            (is
             (= "ops"
                (h/input-value component-els/team-context-value)))
            (h/click (shell-els/nav-item :harnesses))
            (h/wait-visible harnesses-els/page)
            (h/wait-any harnesses-els/grid harnesses-els/empty-state)
            (is
             (false?
              (h/visible? (harnesses-els/team-group "payments"))))
            (h/click (shell-els/nav-item :sessions))
            (h/wait-visible sessions-els/page)
            (is
             (= "ops"
                (url-team)))))

    (step "On a team's page the context is that team, and the page says it acts on it"
          (fn []
            (h/goto (str "/" (:tenant h/fixture) "/payments"))
            (h/wait-visible team-els/page)
            (is
             (= "payments"
                (h/input-value component-els/team-context-value)))
            (is
             (= ["team" "admin"]
                [(h/attribute team-els/scope "data-scope") (h/attribute team-els/role "data-role")]))
            (h/select-option component-els/team-context (component-els/team-context-option "all"))
            (h/wait-hidden team-els/page)
            (is
             (= (str "/" (:tenant h/fixture) "/teams")
                (h/current-path)))))

    (step "The start dialog says the team, whose credentials run, and lets the person choose who sees it"
          (fn []
            (h/logout)
            (h/login (:tenant-admin h/fixture))
            (h/goto (str "/?team=" (:team h/fixture)))
            (h/click (component-els/workflow-start (:team h/fixture) (:session-workflow h/fixture)))
            (h/wait-visible component-els/run-dialog)
            (is
             (= [(:team h/fixture) "person"]
                [(h/attribute component-els/run-dialog-team "data-team")
                 (h/attribute component-els/run-dialog-runs-as "data-scope")]))
            (h/click component-els/run-dialog-visibility)
            (is
             (= ["true" true true]
                [(h/attribute (component-els/run-dialog-visibility-option :private) "aria-selected")
                 (h/visible? (component-els/run-dialog-visibility-option :team))
                 (h/visible? (component-els/run-dialog-visibility-option :tenant))]))
            (.press (h/locator (component-els/run-dialog-visibility-option :private)) "Escape")
            (h/click component-els/run-dialog-cancel)
            (h/wait-hidden component-els/run-dialog)))

    (step "A team session runs as the team and can't be private"
          (fn []
            (h/click (component-els/workflow-team-session (:team h/fixture) (:session-workflow h/fixture)))
            (h/wait-visible component-els/run-dialog)
            (is
             (= "team"
                (h/attribute component-els/run-dialog-runs-as "data-scope")))
            (h/click component-els/run-dialog-visibility)
            (is
             (= [false "true"]
                [(h/visible? (component-els/run-dialog-visibility-option :private))
                 (h/attribute (component-els/run-dialog-visibility-option :team) "aria-selected")]))
            (.press (h/locator (component-els/run-dialog-visibility-option :team)) "Escape")
            (h/click component-els/run-dialog-cancel)
            (h/wait-hidden component-els/run-dialog)))

    (step "Admin pages say they act on the tenant"
          (fn []
            (h/click (shell-els/nav-item :admin))
            (h/wait-visible admin-els/page)
            (is
             (= "tenant"
                (h/attribute admin-els/scope "data-scope")))))

    (step "Someone without a team learns why and sees what the tenant shares"
          (fn []
            (h/logout)
            (h/login (:teamless h/fixture))
            (is
             (true?
              (.isVisible (h/wait-visible component-els/team-context-none))))
            (is
             (true?
              (.isVisible (h/wait-visible home-els/no-teams))))
            (is
             (true?
              (.isVisible (h/wait-visible home-els/shared-section))))
            (is
             (false?
              (h/visible? home-els/start-section)))))

    (step "An operator outside any tenant lands on Tenants, marked as platform-wide"
          (fn []
            (h/logout)
            (h/login (:operator h/fixture))
            (h/goto "/runs")
            (h/wait-visible tenants-els/page)
            (is
             (= ["/platform/tenants" "platform" "platform"]
                [(h/current-path)
                 (h/attribute tenants-els/scope "data-scope")
                 (h/attribute shell-els/brand-tenant "data-scope")]))
            (is
             (string/starts-with? (h/text shell-els/user-role) "Platform operator"))))))
