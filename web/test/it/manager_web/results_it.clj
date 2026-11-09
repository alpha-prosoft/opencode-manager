(ns manager-web.results-it
  "What a finished review run shows: its verdict and findings on the run page."
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.run.elements :as run-els]
            [testing.pw :refer [with-ui step]]))

(def review-workflow
  "The demo team's workflow reviewing a branch of demo-service; e2e-review holds a deliberate bug."
  "review-branch")

(deftest review-result-it
  (with-ui [_page]
    (step "A branch review started from the team page runs to its end"
          (fn []
            (h/login (:member h/fixture))
            (h/goto (str "/teams/" (:team h/fixture)))
            (h/start-workflow (:team h/fixture) review-workflow {:branch "e2e-review"})
            (is
             (= :completed
                (h/wait-run-finished run-els/status h/run-timeout-ms)))))

    (step "The run page shows the review's verdict in its result"
          (fn []
            (is
             (true?
              (.isVisible (h/wait-visible run-els/result))))
            (is
             (true?
              (.isVisible (h/wait-visible component-els/review-verdict))))
            (is
             (false?
              (h/visible? run-els/run-error)))))))
