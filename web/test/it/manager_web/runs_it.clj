(ns manager-web.runs-it
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.run.elements :as run-els]
            [manager-web.runs.elements :as els]
            [testing.pw :refer [with-ui step]]))

(deftest runs-it
  (with-ui [_page]
    (step "The runs page lists runs, or says why not"
          (fn []
            (h/login (:member h/fixture))
            (h/goto "/runs")
            (h/wait-visible els/page)
            (is
             (contains? #{component-els/runs-empty component-els/runs-table}
                        (h/wait-any els/error component-els/runs-empty component-els/runs-table)))))

    (step "Runs can be filtered"
          (fn []
            (h/click els/filter-select)
            (h/click (els/filter-option :active))
            (h/wait-hidden (els/filter-option :completed))
            (is
             (= "active"
                (h/input-value els/filter-value)))))

    (step "An unknown run shows an error"
          (fn []
            (h/goto (str "/runs/" (random-uuid)))
            (is
             (true?
              (.isVisible (h/wait-visible run-els/error))))))

    (step "An active run can be cancelled after confirming"
          (fn []
            (h/goto (str "/teams/" (:team h/fixture)))
            (h/start-workflow (:team h/fixture) (:analysis-workflow h/fixture)
                              {:brief "This run is cancelled straight away by the UI integration test."})
            (h/click run-els/cancel)
            (h/wait-visible component-els/confirm-dialog)
            (h/click component-els/confirm-accept)
            (is
             (= :cancelled
                (h/wait-status run-els/status :cancelled 60000)))
            (is
             (true?
              (.isVisible (h/wait-visible run-els/run-error))))
            (is
             (false?
              (h/visible? run-els/cancel)))))

    (step "A finished run can be run again with the same inputs, as a new run"
          (fn []
            (let [source-id
                  (h/path-id)]
              (h/click run-els/retry)
              (h/wait-path-change (str "/runs/" source-id))
              (h/wait-visible run-els/page)
              (is
               (not= source-id
                     (h/path-id)))
              (is
               (true?
                (.isVisible (h/wait-visible run-els/cancel)))))))

    (step "The re-run is cancelled too and listed on the runs page"
          (fn []
            (let [run-id
                  (h/path-id)]
              (h/click run-els/cancel)
              (h/click component-els/confirm-accept)
              (h/wait-status run-els/status :cancelled 60000)
              (h/click run-els/back)
              (h/wait-visible els/page)
              (is
               (true?
                (.isVisible (h/wait-visible (component-els/run-link run-id))))))))))
