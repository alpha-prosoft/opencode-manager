(ns manager-web.session.views-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.session.events :as events]
            [manager-web.session.views :as views]))

(deftest every-reason-the-service-blocks-a-session-is-explained
  (is
   (= #{:locked :personal :private :team-only :hidden}
      (set (keys views/blocked-reasons)))))

(deftest renaming-sends-the-name-and-description
  (is
   (= {:id [::events/rename 1]
       :error ::events/rename
       :command {:cmd-id :update-session :id 1 :attrs {:name "New name" :description "Why"}}
       :refetch [[:session 1]]
       :on-success [::events/close-rename]}
      (events/rename-spec 1 {:name "  New name " :description "Why"}))))
