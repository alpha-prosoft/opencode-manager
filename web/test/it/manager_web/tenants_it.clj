(ns manager-web.tenants-it
  "Operators see every tenant's sync state, sync one and register another;
   tenant admins can't."
  (:require [clojure.test :refer [deftest is]]
            [manager-web.helpers :as h]
            [manager-web.shell.elements :as shell-els]
            [manager-web.tenants.elements :as els]
            [testing.pw :refer [with-ui step]]))

(def new-tenant
  "One name for every pass: tenants can't be removed, so later passes find
   it registered and only check that a second registration is refused."
  "uitest")

(defn- wait-synced
  [tenant-name timeout-ms]
  (h/wait-until (str tenant-name " to be synced") timeout-ms 1000
                #(= "synced" (.getAttribute (h/raw-locator (els/sync-status tenant-name)) "data-state"))))

(deftest tenants-it
  (with-ui [_page]
    (step "Operators see every tenant with its sync state"
          (fn []
            (h/login (:operator h/fixture))
            (is
             (= "Platform"
                (h/text shell-els/brand-tenant)))
            (h/click (shell-els/nav-item :tenants))
            (h/wait-visible els/page)
            (is
             (true?
              (.isVisible (h/wait-visible (els/row (:tenant h/fixture))))))
            (is
             (true?
              (h/visible? (els/row (:other-tenant h/fixture)))))
            (is
             (true?
              (wait-synced (:tenant h/fixture) 60000)))))

    (step "An operator syncs one tenant"
          (fn []
            (h/click (els/sync (:other-tenant h/fixture)))
            (h/wait-until "the sync request" 60000 200
                          #(nil? (.getAttribute (h/locator (els/sync (:other-tenant h/fixture))) "disabled")))
            (is
             (false?
              (h/visible? els/action-error)))
            (is
             (true?
              (wait-synced (:other-tenant h/fixture) 60000)))))

    (step "An operator registers a tenant (unless an earlier pass did); it is listed right away and syncs on its own"
          (fn []
            (when-not (h/visible? (els/row new-tenant))
              (h/fill els/register-name new-tenant)
              (h/fill els/register-onboarding (:register-onboarding h/fixture))
              (h/click els/register-submit)
              (h/wait-visible els/registered 30000))
            (is
             (true?
              (.isVisible (h/wait-visible (els/row new-tenant) 10000))))
            (is
             (true?
              (wait-synced new-tenant 120000)))))

    (step "Registering the same name again is refused"
          (fn []
            (h/fill els/register-name new-tenant)
            (h/fill els/register-onboarding (:register-onboarding h/fixture))
            (h/click els/register-submit)
            (is
             (true?
              (.isVisible (h/wait-visible els/register-error 30000))))))

    (step "An operator reloads the configuration and sees how each part went"
          (fn []
            (h/click els/reload)
            (h/wait-visible els/reload-result 60000)
            (is
             (false?
              (h/visible? els/reload-error)))
            (doseq [part (cond-> [:claims-parser :tenants :new-tenants]
                           (h/oidc-mode?) (into [:discovery :keys]))]
              (is
               (= "ok"
                  (h/attribute (els/reload-part part) "data-state"))
               (name part)))))

    (step "Tenant admins don't manage tenants"
          (fn []
            (h/logout)
            (h/login (:tenant-admin h/fixture))
            (is
             (false?
              (h/visible? (shell-els/nav-item :tenants))))
            (h/goto "/tenants")
            (is
             (true?
              (.isVisible (h/wait-visible els/forbidden))))))))
