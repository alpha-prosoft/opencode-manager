(ns manager-web.projects-it
  "Projects as pages: from the team's project cards to the project, its
   breadcrumbs and tabs, and the projects list."
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.project.elements :as els]
            [manager-web.projects.elements :as projects-els]
            [manager-web.shell.elements :as shell-els]
            [manager-web.team.elements :as team-els]
            [testing.pw :refer [with-ui step]]))

(def tenant
  (:tenant h/fixture))

(def team
  (:team h/fixture))

(def project
  "The project of the demo team's team.json."
  "demo-service")

(def project-workflow
  "A workflow of the demo team naming the project."
  "implement-gerrit")

(defn- open-tab
  [tab]
  (h/click (els/tab tab))
  (h/wait-attribute (els/tab tab) "aria-selected" "true"))

(deftest projects-it
  (with-ui [_page]
    (step "The team page lists its projects as cards that open the project"
          (fn []
            (h/login (:member h/fixture))
            (h/goto (str "/" tenant "/" team))
            (h/wait-visible team-els/page)
            (h/click (team-els/project-card team project))
            (h/wait-visible els/page)
            (is
             (= (str "/" tenant "/" team "/" project)
                (h/current-path)))))

    (step "The project says where it lives: tenant › team › project, repository, branch and CI job"
          (fn []
            (is
             (= [tenant team]
                [(h/text els/crumb-tenant) (h/attribute els/team-chip "data-team")]))
            (is
             (every? h/visible? [els/repository els/branch els/ci]))))

    (step "Its tabs show the workflows that work on it with their start dialog, runs, sessions, changes and builds"
          (fn []
            (h/click (component-els/workflow-start team project-workflow))
            (h/wait-visible component-els/run-dialog)
            (is
             (= team
                (h/attribute component-els/run-dialog-team "data-team")))
            (h/click component-els/run-dialog-cancel)
            (h/wait-hidden component-els/run-dialog)
            (open-tab :runs)
            (is
             (some? (h/wait-any component-els/runs-table component-els/runs-empty)))
            (open-tab :sessions)
            (is
             (some? (h/wait-any component-els/sessions-table component-els/sessions-empty)))
            (open-tab :changes)
            (is
             (some? (h/wait-any els/changes els/changes-empty els/changes-error)))
            (open-tab :builds)
            (is
             (some? (h/wait-any els/builds els/builds-empty)))))

    (step "The team breadcrumb leads back to the team"
          (fn []
            (h/click els/crumb-team)
            (h/wait-visible team-els/page)
            (is
             (= (str "/" tenant "/" team)
                (h/current-path)))))

    (step "The Projects page lists the projects of the person's teams"
          (fn []
            (h/click (shell-els/nav-item :projects))
            (h/wait-visible projects-els/page)
            (h/click (projects-els/card team project))
            (h/wait-visible els/page)
            (is
             (= (str "/" tenant "/" team "/" project)
                (h/current-path)))))))
