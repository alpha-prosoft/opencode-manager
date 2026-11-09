(ns manager-web.login-it
  (:require [clojure.test :refer [deftest is]]
            [manager-web.harnesses.elements :as harnesses-els]
            [manager-web.helpers :as h]
            [manager-web.login.elements :as login-els]
            [manager-web.setup.elements :as setup-els]
            [manager-web.shell.elements :as shell-els]
            [testing.pw :refer [with-ui step]]))

(deftest login-and-logout-it
  (with-ui [_page]
    (step "Anonymous visitors are sent to the login page"
          (fn []
            (h/goto "/runs")
            (h/wait-visible login-els/page)))

    (step "Logging in shows the frame with the user"
          (fn []
            (h/login (:platform-admin h/fixture))
            (is
             (= (:platform-admin h/fixture)
                (h/text shell-els/user-name)))))

    (step "Logging out returns to the login page"
          (fn []
            (h/click shell-els/logout)
            (h/wait-visible login-els/page)
            (is
             (true?
              (h/visible? login-els/submit)))))

    (step "Picking a test persona from the list logs in as them"
          (fn []
            (h/click login-els/username)
            (h/click (login-els/persona (:member h/fixture)))
            (is
             (= (:member h/fixture)
                (h/wait-until "the picked username" 5000
                              #(let [value (h/input-value login-els/username)]
                                 (when (= (:member h/fixture) value) value)))))
            (h/click login-els/submit)
            (when (= setup-els/page (h/wait-any shell-els/user-name setup-els/page))
              (h/click setup-els/later))
            (is
             (= (:member h/fixture)
                (h/text shell-els/user-name)))
            (is
             (false?
              (h/visible? (shell-els/nav-item :admin))))))

    (step "After logging in, people return to the page they asked for"
          (fn []
            (h/logout)
            (h/goto "/harnesses")
            (h/wait-visible login-els/page)
            (h/fill login-els/username (:member h/fixture))
            (.press (h/locator login-els/username) "Escape")
            (h/click login-els/submit)
            (when (= setup-els/page (h/wait-any harnesses-els/page setup-els/page))
              (h/click setup-els/later))
            (is
             (true?
              (.isVisible (h/wait-visible harnesses-els/page))))))))
