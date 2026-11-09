(ns manager-web.sessions-it
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.session.elements :as session-els]
            [manager-web.sessions.elements :as els]
            [testing.pw :refer [with-ui step]]))

(deftest sessions-it
  (with-ui [_page]
    (step "The sessions page lists sessions, or says why not"
          (fn []
            (h/login (:member h/fixture))
            (h/goto "/sessions")
            (h/wait-visible els/page)
            (is
             (contains? #{component-els/sessions-empty component-els/sessions-table}
                        (h/wait-any els/error component-els/sessions-empty component-els/sessions-table)))))

    (step "Sessions can be filtered to my own"
          (fn []
            (h/click (els/filter-option :mine))
            (is
             (= "true"
                (h/wait-attribute (els/filter-option :mine) "aria-pressed" "true")))
            (is
             (= "false"
                (h/wait-attribute (els/filter-option :all) "aria-pressed" "false")))))

    (step "An unknown session shows an error"
          (fn []
            (h/goto (str "/sessions/" (random-uuid)))
            (is
             (true?
              (.isVisible (h/wait-visible session-els/error))))))))
