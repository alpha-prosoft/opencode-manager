(ns manager-web.home-it
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.home.elements :as els]
            [manager-web.run.elements :as run-els]
            [manager-web.runs.elements :as runs-els]
            [manager-web.shell.elements :as shell-els]
            [testing.pw :refer [with-ui step]]))

(deftest home-it
  (with-ui [_page]
    (step "Home greets the person"
          (fn []
            (h/login (:member h/fixture))
            (is
             (string/includes? (h/text els/title) (:member h/fixture)))))

    (step "Home shows what can be started and recent runs"
          (fn []
            (is
             (= els/start-section
                (h/wait-any els/no-teams els/start-section)))
            (is
             (contains? #{component-els/workflows-empty (component-els/workflow-team-link (:team h/fixture))}
                        (h/wait-any els/workflows-error component-els/workflows-empty
                                    (component-els/workflow-team-link (:team h/fixture)))))
            (is
             (true?
              (h/visible? els/recent-runs)))
            (is
             (contains? #{component-els/runs-empty component-els/runs-table}
                        (h/wait-any els/runs-error component-els/runs-empty component-els/runs-table)))))

    (step "The introduction can be hidden and stays hidden"
          (fn []
            (h/click els/hide-intro)
            (h/wait-hidden els/intro)
            (h/reload)
            (h/wait-visible els/title)
            (is
             (false?
              (h/visible? els/intro)))))

    (step "All runs opens the runs page"
          (fn []
            (h/click els/all-runs)
            (is
             (true?
              (.isVisible (h/wait-visible runs-els/page))))))

    (step "A workflow started from Home opens its run, which follows the agent to its result"
          (fn []
            (h/goto "/")
            (let [run-id
                  (h/start-workflow (:team h/fixture) (:analysis-workflow h/fixture)
                                    {:brief "Members get 5% off orders over 100 EUR. Keep the analysis to one short page."})]
              (is
               (= (str "/runs/" run-id)
                  (h/current-path)))
              (is
               (= (:member h/fixture)
                  (h/text run-els/started-by)))
              (is
               (= :completed
                  (h/wait-run-finished run-els/status h/run-timeout-ms)))
              (is
               (true?
                (.isVisible (h/wait-visible run-els/result))))
              (is
               (false?
                (h/visible? run-els/run-error))))))

    (step "The finished run is listed among the recent runs on Home"
          (fn []
            (let [run-id
                  (h/path-id)]
              (h/click (shell-els/nav-item :home))
              (is
               (true?
                (.isVisible (h/wait-visible (component-els/run-link run-id))))))))))
