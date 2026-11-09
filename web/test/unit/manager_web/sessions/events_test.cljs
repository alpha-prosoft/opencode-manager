(ns manager-web.sessions.events-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.sessions.events :as events]))

(deftest filters
  (is
   (true?
    (events/matches? :mine {:is-owner true})))
  (is
   (false?
    (events/matches? :mine {})))
  (is
   (true?
    (events/matches? :running {:status "starting"})))
  (is
   (true?
    (events/matches? :all {}))))
