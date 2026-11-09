(ns manager-web.library-it
  "Teams, harnesses and activities."
  (:require [clojure.test :refer [deftest is]]
            [manager-web.accounts.elements :as accounts-els]
            [manager-web.activities.elements :as activities-els]
            [manager-web.components.elements :as component-els]
            [manager-web.harnesses.elements :as harnesses-els]
            [manager-web.helpers :as h]
            [manager-web.team.elements :as team-els]
            [manager-web.teams.elements :as teams-els]
            [testing.pw :refer [with-ui step]]))

(def team-tabs
  "Each tab with what it shows: every element of a vector, one of a set."
  [[:workflows [(component-els/workflow-start (:team h/fixture) (:workflow h/fixture))]]
   [:runs #{component-els/runs-table component-els/runs-empty}]
   [:setup [(team-els/agent-card "analyst") (team-els/harness-card "sandboxed")]]
   [:people [team-els/members team-els/budget]]])

(deftest library-it
  (with-ui [_page]
    (step "Teams lists the person's teams, or says why not"
          (fn []
            (h/login (:member h/fixture))
            (h/goto "/teams")
            (h/wait-visible teams-els/page)
            (is
             (= teams-els/grid
                (h/wait-any teams-els/error teams-els/empty-state teams-els/grid)))))

    (step "A team card opens the team page"
          (fn []
            (h/click (teams-els/team-card (:team h/fixture)))
            (is
             (= team-els/page
                (h/wait-any team-els/error team-els/page)))))

    (doseq [[tab contents] team-tabs]
      (step (str "The team's " (name tab) " tab shows its contents")
            (fn []
              (h/click (team-els/tab tab))
              (is
               (= "true"
                  (h/wait-attribute (team-els/tab tab) "aria-selected" "true")))
              (if (set? contents)
                (is
                 (contains? contents
                            (apply h/wait-any contents)))
                (doseq [element contents]
                  (is
                   (true?
                    (.isVisible (h/wait-visible element)))))))))

    (step "Running a workflow opens its dialog, which can be cancelled"
          (fn []
            (h/click (team-els/tab :workflows))
            (h/click (component-els/workflow-start (:team h/fixture) (:workflow h/fixture)))
            (h/wait-visible component-els/run-dialog)
            (h/click component-els/run-dialog-cancel)
            (h/wait-hidden component-els/run-dialog)
            (is
             (false?
              (h/visible? component-els/run-dialog)))))

    (step "Harnesses lists sandboxes and which are ready for the person"
          (fn []
            (h/goto "/harnesses")
            (h/wait-visible harnesses-els/page)
            (is
             (= harnesses-els/grid
                (h/wait-any harnesses-els/error harnesses-els/empty-state harnesses-els/grid)))
            (is
             (true?
              (.isVisible (h/wait-visible (apply harnesses-els/ready (:ready-harness h/fixture))))))))

    (step "Activities lists the catalog, or says why not"
          (fn []
            (h/goto "/activities")
            (h/wait-visible activities-els/page)
            (is
             (= activities-els/catalog
                (h/wait-any activities-els/error activities-els/catalog)))))

    (step "An activity expands to its example workflow file"
          (fn []
            (let [activity
                  (:activity h/fixture)]
              (h/click (activities-els/activity activity))
              (is
               (re-find (re-pattern (str "\"activity\": \"" activity "\""))
                        (h/text (activities-els/example activity)))))))))

(deftest harness-readiness-it
  (with-ui [_page]
    (step "A harness whose tools need a missing credential says so"
          (fn []
            (h/login (:outsider h/fixture))
            (h/goto "/harnesses")
            (is
             (true?
              (.isVisible (h/wait-visible (apply harnesses-els/needs-setup (:unready-harness h/fixture))))))))

    (step "Its Add button opens Accounts"
          (fn []
            (h/click (apply harnesses-els/add-credential (:unready-harness h/fixture)))
            (is
             (true?
              (.isVisible (h/wait-visible accounts-els/page))))))))
