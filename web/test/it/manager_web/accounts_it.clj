(ns manager-web.accounts-it
  "Personal credentials on Accounts and the setup flow at login."
  (:require [clojure.test :refer [deftest is]]
            [manager-web.accounts.elements :as els]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.home.elements :as home-els]
            [manager-web.setup.elements :as setup-els]
            [testing.pw :refer [with-ui step]]))

(def credential-id
  (:personal-credential h/fixture))

(def checked-credential-id
  (:checked-credential h/fixture))

(def admin-only-id
  (:admin-only-credential h/fixture))

(defn- save-credential
  [username secret]
  (h/wait-visible component-els/credential-dialog)
  (h/fill component-els/credential-username username)
  (h/fill component-els/credential-secret secret)
  (h/click component-els/credential-submit)
  (h/wait-hidden component-els/credential-dialog))

(defn- remove-credential
  []
  (h/click (els/remove-credential credential-id))
  (h/wait-visible component-els/confirm-dialog)
  (h/click component-els/confirm-accept)
  (h/wait-hidden component-els/confirm-dialog)
  (h/wait-hidden (els/remove-credential credential-id)))

(deftest accounts-it
  (with-ui [_page]
    (step "Accounts lists the person's credentials"
          (fn []
            (h/login (:member h/fixture))
            (h/goto "/accounts")
            (h/wait-visible els/page)
            (is
             (= els/credentials
                (h/wait-any els/error els/empty-state els/credentials)))
            (when (h/visible? (els/remove-credential credential-id))
              (remove-credential))))

    (step "A personal credential can be added"
          (fn []
            (h/click (els/edit credential-id))
            (save-credential "bob@example.com" "not-a-real-token")
            (is
             (true?
              (.isVisible (h/wait-visible (els/remove-credential credential-id)))))))

    (step "It can be replaced"
          (fn []
            (h/click (els/edit credential-id))
            (save-credential "bob@example.com" "another-not-real-token")
            (is
             (true?
              (h/visible? (els/remove-credential credential-id))))))

    (step "Removing it asks first, then removes it"
          (fn []
            (remove-credential)
            (is
             (false?
              (h/visible? (els/remove-credential credential-id))))))

    (step "A credential that fails its check is not saved and says why"
          (fn []
            (h/click (els/edit checked-credential-id))
            (h/wait-visible component-els/credential-dialog)
            (h/fill component-els/credential-secret "definitely-not-a-valid-token")
            (h/click component-els/credential-submit)
            (is
             (true?
              (.isVisible (h/wait-visible component-els/credential-error 60000))))
            (h/click component-els/credential-cancel)
            (h/wait-hidden component-els/credential-dialog)
            (is
             (false?
              (h/visible? (els/remove-credential checked-credential-id))))))))

(deftest admin-only-credentials-it
  (with-ui [_page]
    (step "A credential only admins provide is shown read-only, with where its value comes from"
          (fn []
            (h/login (:member h/fixture))
            (h/goto "/accounts")
            (h/wait-visible (els/credential admin-only-id))
            (is
             (true?
              (h/visible? (els/read-only admin-only-id))))
            (is
             (contains? #{"team" "tenant" "missing"}
                        (.getAttribute (h/raw-locator (els/resolution admin-only-id)) "data-state")))
            (is
             (false?
              (h/visible? (els/edit admin-only-id))))
            (is
             (false?
              (h/visible? (els/read-only credential-id))))))))

(deftest setup-it
  (with-ui [_page]
    (step "Their other optional credentials were left for later and Jira has no value of theirs"
          (fn []
            (h/login (:member h/fixture))
            (h/goto "/accounts")
            (h/wait-visible (els/edit credential-id))
            (when (h/visible? (els/remove-credential credential-id))
              (remove-credential))
            (h/logout)))

    (step "Logging in with credentials to connect opens the setup page"
          (fn []
            (h/submit-login (:member h/fixture))
            (h/wait-visible setup-els/page)
            (is
             (true?
              (.isVisible (h/wait-visible (setup-els/step credential-id)))))
            (is
             (false?
              (h/visible? (setup-els/step admin-only-id))))))

    (step "A checked credential that fails is not saved, and the step can be skipped"
          (fn []
            (when (h/visible? (setup-els/step checked-credential-id))
              (h/fill component-els/credential-secret "definitely-not-a-valid-token")
              (h/click component-els/credential-submit)
              (is
               (true?
                (.isVisible (h/wait-visible component-els/credential-error 60000))))
              (h/click (setup-els/skip checked-credential-id)))
            (is
             (true?
              (.isVisible (h/wait-visible (setup-els/skip credential-id)))))))

    (step "Saving the last step finishes setup and goes on to the app"
          (fn []
            (h/fill component-els/credential-username "bob@example.com")
            (h/fill component-els/credential-secret "not-a-real-token")
            (h/click component-els/credential-submit)
            (is
             (true?
              (.isVisible (h/wait-visible home-els/page))))))

    (step "With everything connected the setup page says so and continues"
          (fn []
            (h/goto "/setup")
            (h/wait-visible setup-els/page)
            (is
             (= setup-els/all-set
                (h/wait-any setup-els/error setup-els/all-set setup-els/later)))
            (h/click setup-els/continue)
            (is
             (true?
              (.isVisible (h/wait-visible home-els/page))))))

    (step "Without the credential, setup offers it again and Do this later leaves it"
          (fn []
            (h/goto "/accounts")
            (remove-credential)
            (h/goto "/setup")
            (h/wait-visible (setup-els/step credential-id))
            (h/click setup-els/later)
            (is
             (true?
              (.isVisible (h/wait-visible home-els/page))))))))
