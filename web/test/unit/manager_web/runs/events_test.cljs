(ns manager-web.runs.events-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.runs.events :as events]))

(deftest filters
  (is
   (true?
    (events/matches? :all {:status :failed})))
  (is
   (true?
    (events/matches? :active {:status "running"})))
  (is
   (false?
    (events/matches? :active {:status :completed})))
  (is
   (true?
    (events/matches? :failed {:status "failed"}))))
