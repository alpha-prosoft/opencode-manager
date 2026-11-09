(ns manager-web.admin-it
  (:require [clojure.test :refer [deftest is]]
            [manager-web.admin.elements :as els]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.team.elements :as team-els]
            [testing.pw :refer [with-ui step]]))

(def tabs
  [[:people [els/users-table]]
   [:usage [els/usage-table]]
   [:teams [els/teams-table]]
   [:connections [els/connection-list]]
   [:credentials [els/credentials-table]]
   [:mcp [els/mcp-list els/mcp-empty]]
   [:jobs [els/schedules-table]]
   [:overview [els/overview-stats]]])

(deftest admin-it
  (with-ui [_page]
    (step "The admin console opens on the overview"
          (fn []
            (h/login (:platform-admin h/fixture))
            (h/goto "/admin")
            (h/wait-visible els/page)
            (is
             (= "true"
                (h/wait-attribute (els/tab :overview) "aria-selected" "true")))
            (is
             (= els/overview-stats
                (h/wait-any (els/panel-error :overview) els/overview-stats)))))

    (doseq [[tab contents] tabs]
      (step (str "The " (name tab) " tab shows its own panel")
            (fn []
              (h/click (els/tab tab))
              (is
               (= "true"
                  (h/wait-attribute (els/tab tab) "aria-selected" "true")))
              (is
               (contains? (set contents)
                          (apply h/wait-any (els/panel-error tab) contents))))))

    (step "Sync now syncs the repositories and reports it"
          (fn []
            (h/click (els/tab :overview))
            (h/click els/sync)
            (h/wait-until "the sync report" 120000
                          #(and (h/visible? els/report) (= "sync" (h/attribute els/report "data-action"))))
            (is
             (= "true"
                (h/attribute els/report "data-ok")))))

    (step "Stop idle sessions asks the reaper and reports it"
          (fn []
            (h/click els/stop-idle)
            (h/wait-until "the stop-idle report" 30000
                          #(and (h/visible? els/report) (= "idle" (h/attribute els/report "data-action"))))
            (is
             (= "true"
                (h/attribute els/report "data-ok")))))

    (step "An LLM connection can be tested"
          (fn []
            (h/click (els/tab :connections))
            (h/click (els/test-connection (:connection h/fixture)))
            (is
             (= "ok"
                (h/wait-until "the test result" 120000
                              #(when (h/visible? (els/test-result (:connection h/fixture)))
                                 (h/attribute (els/test-result (:connection h/fixture)) "data-state")))))))

    (step "Usage can be shown for another period"
          (fn []
            (h/click (els/tab :usage))
            (h/select-option els/usage-days (els/usage-days-option 1))
            (is
             (= els/usage-table
                (h/wait-any (els/panel-error :usage) els/usage-table)))
            (h/click els/usage-days)
            (is
             (= "true"
                (h/wait-attribute (els/usage-days-option 1) "aria-selected" "true")))
            (.press (h/locator (els/usage-days-option 1)) "Escape")))

    (step "MCP servers show their state"
          (fn []
            (h/click (els/tab :mcp))
            (is
             (true?
              (.isVisible (h/wait-visible (els/mcp-server (:mcp-server h/fixture))))))))

    (step "A platform credential that fails its check is not saved"
          (fn []
            (h/click (els/tab :credentials))
            (h/click (els/set-credential (:checked-credential h/fixture)))
            (h/wait-visible component-els/credential-dialog)
            (h/fill component-els/credential-secret "definitely-not-a-valid-token")
            (h/click component-els/credential-submit)
            (is
             (true?
              (.isVisible (h/wait-visible component-els/credential-error 60000))))
            (h/click component-els/credential-cancel)
            (h/wait-hidden component-els/credential-dialog)))

    (step "A valid platform credential is saved and can be cleared again"
          (fn []
            (let [credential-id
                  (:checked-credential h/fixture)

                  secret
                  (h/read-secret credential-id)]
              (is
               (some? secret))
              (when secret
                (h/click (els/set-credential credential-id))
                (h/wait-visible component-els/credential-dialog)
                (h/fill component-els/credential-secret secret)
                (h/click component-els/credential-submit)
                (h/wait-hidden component-els/credential-dialog 60000)
                (h/click (els/clear-credential credential-id))
                (h/wait-visible component-els/confirm-dialog)
                (h/click component-els/confirm-accept)
                (h/wait-hidden (els/clear-credential credential-id))
                (is
                 (false?
                  (h/visible? (els/clear-credential credential-id))))))))

    (step "A team links to its page"
          (fn []
            (h/click (els/tab :teams))
            (h/click (els/team-link (:team h/fixture)))
            (is
             (true?
              (.isVisible (h/wait-visible team-els/page))))))))
