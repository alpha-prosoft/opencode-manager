(ns manager-web.run.views-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.run.events :as events]
            [manager-web.run.views :as views]))

(deftest progress-steps
  (is
   (= [views/automated-steps 2]
      (views/progress-steps {:status "awaiting_result" :activity :review})))
  (is
   (= [views/ci-steps 4]
      (views/progress-steps {:status :following-ci :activity :implement})))
  (is
   (= [views/session-steps 1]
      (views/progress-steps {:status :running :activity :session}))))

(deftest retry-spec
  (is
   (= {:id [::events/retry 1]
       :error ::events/action
       :command {:cmd-id :retry-run :id 2 :attrs {:source-id 1}}
       :navigate [:run {:run-id "2"}]}
      (events/retry-spec 1 2))))

(deftest started-by-names-the-platform-from-its-marker
  (is
   (= "The platform"
      (views/started-by {:platform-started true :created-by "platform:schedule"})))
  (is
   (= "bob"
      (views/started-by {:platform-started false :created-by "bob"}))))
