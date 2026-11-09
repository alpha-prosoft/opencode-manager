(ns manager-web.setup.events-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.setup.events :as events]))

(deftest pending-credentials
  (is
   (= [{:id "bitbucket"}]
      (events/pending-credentials {:credentials [{:id "bitbucket"} {:id "github"}]
                                   :pending ["bitbucket"]}))))

(deftest finishing-leaves-setup-either-way
  (let [spec
        (events/finish-spec ["bitbucket"])]
    (is
     (= {:cmd-id :mark-credentials-seen :attrs {:credentials ["bitbucket"]}}
        (:command spec)))
    (is
     (= (:on-success spec)
        (:on-failure spec)))))
