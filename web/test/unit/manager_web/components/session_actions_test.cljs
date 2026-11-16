(ns manager-web.components.session-actions-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.session-actions :as session-actions]))

(deftest a-session-command-refreshes-what-the-page-shows
  (is
   (= {:id [:stop-session 1]
       :error :shown-here
       :command {:cmd-id :stop-session :id 1}
       :refetch [:sessions]}
      (session-actions/command-spec :stop-session 1 {:error-id :shown-here :refetch [:sessions]}))))
