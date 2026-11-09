(ns manager-web.webhooks-it
  "Webhooks: a team admin adds a header-auth webhook that starts the demo
   team's analysis workflow, previews what a sample request maps to, sends
   a real test call that starts a run, sees a caller with a wrong secret
   refused in the delivery log; a member sees the webhook without secrets;
   people of another team or tenant don't see it at all."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.run.elements :as run-els]
            [manager-web.webhooks.elements :as els]
            [testing.pw :refer [with-ui step]])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def webhook-name
  (str "IT intake " (System/currentTimeMillis)))

(def secret
  "it-webhook-secret-1")

(def hook (atom nil))

(def tenant-hook (atom nil))

(def refused-body
  "A body the analysis workflow refuses (422): it passes auth and starts nothing."
  "{\"issue\": {\"summary\": \"short\"}}")

(defn- delete-hook!
  [id]
  (h/clean-up! (:tenant-admin h/fixture) [[:delete-webhook id]]))

(defn- open-team-webhooks
  []
  (h/goto (str "/" (:tenant h/fixture) "/" (:team h/fixture) "?tab=webhooks"))
  (h/wait-visible els/section))

(defn- webhook-id
  []
  (last (string/split (string/trim (h/text els/endpoint)) #"/")))

(defn- call-hook
  [id token body]
  (.statusCode (.send (HttpClient/newHttpClient)
                      (-> (HttpRequest/newBuilder (URI/create (str h/base-url "/hooks/" (:tenant h/fixture) "/" id)))
                          (.header "Content-Type" "application/json")
                          (.header "X-Webhook-Token" token)
                          (.POST (HttpRequest$BodyPublishers/ofString body))
                          (.build))
                      (HttpResponse$BodyHandlers/ofString))))

(defn- preview-state
  []
  (h/wait-visible els/preview-result)
  (.getAttribute (h/raw-locator (els/preview-input "brief")) "data-state"))

(deftest webhooks-it
  (with-ui [_page]
    (try
      (step "A team admin adds a header-auth webhook targeting the analysis workflow"
            (fn []
              (h/login (:tenant-admin h/fixture))
              (open-team-webhooks)
              (h/click els/create)
              (h/wait-visible els/dialog)
              (h/fill els/dialog-name webhook-name)
              (h/select-option els/dialog-workflow (els/option :workflow (:analysis-workflow h/fixture)))
              (h/select-option (els/mapping-source "brief") (els/option [:source "brief"] :body))
              (h/fill (els/mapping-value "brief") "$.issue.summary")
              (h/fill els/dialog-secret secret)
              (h/click els/dialog-save)
              (h/wait-hidden els/dialog 30000)
              (h/wait-visible els/detail)
              (reset! hook (webhook-id))
              (is
               (string/includes? (h/text els/auth-summary) "X-Webhook-Token"))
              (is
               (string/includes? (h/text els/curl) "$WEBHOOK_SECRET"))))

      (step "The dry run maps a sample body to inputs and shows schema errors, starting nothing"
            (fn []
              (h/fill els/test-body "{\"issue\": {\"summary\": \"Customers can't pay with stored cards\"}}")
              (h/click els/preview)
              (is
               (= "ok"
                  (h/wait-until "the preview" h/default-timeout-ms #(preview-state))))
              (h/fill els/test-body "{\"issue\": {\"summary\": \"short\"}}")
              (h/click els/preview)
              (is
               (= "error"
                  (h/wait-until "the preview error" h/default-timeout-ms
                                #(when (= "error" (preview-state)) "error"))))))

      (step "Send test calls the public endpoint with the stored secret and starts a run"
            (fn []
              (h/fill els/test-body "{\"issue\": {\"summary\": \"Customers can't pay with stored cards\"}}")
              (h/click els/send-test)
              (is
               (= "202"
                  (h/wait-attribute els/test-status "data-status" "202")))
              (h/click els/test-run-link)
              (is
               (true?
                (.isVisible (h/wait-visible run-els/page))))))

      (step "A caller with a wrong secret gets 401, and the delivery log shows it"
            (fn []
              (is
               (= 401
                  (call-hook @hook "not-the-secret" "{\"issue\": {\"summary\": \"Wrong secret from outside\"}}")))
              (open-team-webhooks)
              (h/click (els/open @hook))
              (h/wait-visible els/deliveries)
              (is
               (= "401,202"
                  (h/wait-attribute els/deliveries "data-statuses" "401,202")))))

      (step "A member sees the webhook but no secret and can't change or test it"
            (fn []
              (h/logout)
              (h/login (:member h/fixture))
              (open-team-webhooks)
              (h/wait-visible (els/row @hook))
              (is
               (false?
                (h/visible? (els/edit @hook))))
              (h/click (els/open @hook))
              (h/wait-visible els/detail)
              (is
               (not (string/includes? (h/text els/auth-summary) "secret")))
              (is
               (false?
                (h/visible? els/send-test)))
              (is
               (false?
                (h/visible? els/rotate)))))

      (step "Someone of another team or tenant doesn't see it"
            (fn []
              (h/logout)
              (h/login (:other-team-member h/fixture))
              (h/goto (str "/" (:tenant h/fixture) "/webhooks"))
              (h/wait-any els/empty-list els/table)
              (is
               (false?
                (h/visible? (els/row @hook))))
              (h/logout)
              (h/login (:other-tenant-member h/fixture))
              (h/goto (str "/" (:other-tenant h/fixture) "/webhooks"))
              (h/wait-any els/empty-list els/table)
              (is
               (false?
                (h/visible? (els/row @hook))))))

      (step "Regenerating the secret shows the new one once; the old one stops working and test calls use the new"
            (fn []
              (h/logout)
              (h/login (:tenant-admin h/fixture))
              (open-team-webhooks)
              (h/click (els/open @hook))
              (h/wait-visible els/detail)
              (h/click els/rotate)
              (h/wait-visible component-els/confirm-dialog)
              (h/click component-els/confirm-accept)
              (is
               (true?
                (.isVisible (h/wait-visible els/revealed-secret 30000))))
              (is
               (= 401
                  (call-hook @hook secret refused-body)))
              (h/fill els/test-body refused-body)
              (h/click els/send-test)
              (is
               (= "422"
                  (h/wait-attribute els/test-status "data-status" "422")))
              (h/click els/revealed-dismiss)
              (h/wait-hidden els/revealed-secret)
              (h/reload)
              (h/wait-visible els/section)
              (h/click (els/open @hook))
              (h/wait-visible els/detail)
              (is
               (false?
                (h/visible? els/revealed-secret)))
              (h/click els/detail-close)
              (h/wait-hidden els/detail)))

      (step "It can be renamed and switched off, and then answers 404"
            (fn []
              (h/click (els/edit @hook))
              (h/wait-visible els/dialog)
              (h/fill els/dialog-name (str webhook-name " renamed"))
              (.click (h/raw-locator els/dialog-enabled))
              (h/click els/dialog-save)
              (h/wait-hidden els/dialog 30000)
              (is
               (= "disabled"
                  (h/wait-attribute (els/enabled @hook) "data-state" "disabled")))
              (is
               (= (str webhook-name " renamed")
                  (h/text (els/open @hook))))
              (is
               (= 404
                  (call-hook @hook secret refused-body)))))

      (step "Deleting it asks first and removes it"
            (fn []
              (h/click (els/delete @hook))
              (h/wait-visible component-els/confirm-dialog)
              (h/click component-els/confirm-accept)
              (h/wait-hidden (els/row @hook))
              (is
               (false?
                (h/visible? (els/row @hook))))
              (reset! hook nil)))
      (finally
        (delete-hook! @hook)))))

(deftest tenant-webhook-it
  (with-ui [_page]
    (try
      (step "A tenant admin adds a webhook of the whole tenant on the Webhooks page"
            (fn []
              (h/login (:tenant-admin h/fixture))
              (h/goto (str "/" (:tenant h/fixture) "/webhooks"))
              (h/wait-visible els/page)
              (h/click els/create)
              (h/wait-visible els/dialog)
              (h/fill els/dialog-name (str webhook-name " tenant"))
              (h/select-option els/dialog-scope (els/option :scope :tenant))
              (h/select-option els/dialog-team (els/option :target-team (:team h/fixture)))
              (h/select-option els/dialog-workflow (els/option :workflow (:analysis-workflow h/fixture)))
              (h/select-option (els/mapping-source "brief") (els/option [:source "brief"] :body))
              (h/fill (els/mapping-value "brief") "$.issue.summary")
              (h/fill els/dialog-secret secret)
              (h/click els/dialog-save)
              (h/wait-hidden els/dialog 30000)
              (h/wait-visible els/detail)
              (reset! tenant-hook (webhook-id))
              (h/click els/detail-close)
              (h/wait-hidden els/detail)
              (is
               (true?
                (.isVisible (h/wait-visible (els/row @tenant-hook)))))))

      (step "Its endpoint lets the right secret in"
            (fn []
              (is
               (= [422 401]
                  [(call-hook @tenant-hook secret refused-body)
                   (call-hook @tenant-hook "not-the-secret" refused-body)]))))

      (step "A team admin who isn't tenant admin can't change it"
            (fn []
              (h/logout)
              (h/login (:two-teams-admin h/fixture))
              (h/goto (str "/" (:tenant h/fixture) "/webhooks"))
              (h/wait-any els/empty-list els/table)
              (is
               (false?
                (h/visible? (els/edit @tenant-hook))))))
      (finally
        (delete-hook! @tenant-hook)))))
