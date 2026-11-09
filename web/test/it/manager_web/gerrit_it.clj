(ns manager-web.gerrit-it
  "Gerrit results in the UI against compose.ci.yml: the Teams page lists the
   team's projects, and an implement-gerrit run shows its change as
   \"Change N\" with the patch set it pushed. Skipped when Gerrit is not
   reachable (GERRIT_URL, default http://gerrit.localhost:8080)."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.project.elements :as project-els]
            [manager-web.run.elements :as run-els]
            [manager-web.teams.elements :as teams-els]
            [testing.pw :refer [with-ui step]])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util Base64]))

(def gerrit-url
  (or (System/getenv "GERRIT_URL") "http://gerrit.localhost:8080"))

(defn- gerrit-reachable?
  []
  (try
    (= 200 (.statusCode (.send (HttpClient/newHttpClient)
                               (.build (.timeout (HttpRequest/newBuilder (URI/create (str gerrit-url "/config/server/version")))
                                                 (Duration/ofSeconds 10)))
                               (HttpResponse$BodyHandlers/discarding))))
    (catch Exception _
      false)))

(def gerrit-admin-password
  (or (System/getenv "GERRIT_ADMIN_PASSWORD") "gerrit-admin-secret"))

(def change-number
  (atom nil))

(def run-id
  (atom nil))

(defn- abandon-change!
  "Abandons the change the test pushed, so it isn't reviewed again and doesn't pile up in Gerrit."
  [number]
  (when number
    (try
      (.send (HttpClient/newHttpClient)
             (-> (HttpRequest/newBuilder (URI/create (str gerrit-url "/a/changes/" number "/abandon")))
                 (.timeout (Duration/ofSeconds 30))
                 (.header "Authorization"
                          (str "Basic " (.encodeToString (Base64/getEncoder)
                                                         (.getBytes (str "admin:" gerrit-admin-password) StandardCharsets/UTF_8))))
                 (.header "Content-Type" "application/json")
                 (.POST (HttpRequest$BodyPublishers/ofString "{\"message\":\"Abandoned by the UI integration test\"}"))
                 (.build))
             (HttpResponse$BodyHandlers/discarding))
      (catch Exception e
        (println "Could not abandon change" number (ex-message e))))))

(defn- open-project-tab
  [tab]
  (h/click (project-els/tab tab))
  (h/wait-attribute (project-els/tab tab) "aria-selected" "true"))

(deftest gerrit-results-it
  (with-ui [_page]
    (try
      (step "The Teams page lists each team's projects with their repository and builds"
            (fn []
              (h/login (:tenant-admin h/fixture))
              (h/goto "/teams")
              (let [line
                    (h/text (teams-els/team-project (:team h/fixture) "demo-service"))]
                (is
                 (string/includes? line "gerrit demo-service"))
                (is
                 (string/includes? line "builds demo-service-verify")))))

      (if-not (gerrit-reachable?)
        (step "Gerrit is not reachable: the change flow is skipped"
              (fn []
                (is
                 (false? (gerrit-reachable?))
                 (str "Skipped: " gerrit-url " does not answer (docker compose -f compose.yml -f compose.ci.yml up -d)"))))
        (do
          (step "An implement-gerrit run shows its change, patch set and project"
                (fn []
                  (h/goto (str "/teams/" (:team h/fixture)))
                  (h/start-workflow (:team h/fixture) "implement-gerrit"
                                    {:task "Add a function subtotal(prices: list[float]) -> float to demo/pricing.py that returns the rounded sum of the prices, with a unit test in tests/test_pricing.py. Run the tests with python3 -m unittest."})
                  (reset! run-id (h/path-id))
                  (is
                   (= :completed
                      (h/wait-run-finished run-els/status h/run-timeout-ms)))
                  (is
                   (string/starts-with? (h/text run-els/pull-request) "Change "))
                  (is
                   (re-matches #"\d+" (h/text run-els/pull-request-link)))
                  (reset! change-number (re-find #"\d+" (h/text run-els/pull-request-link)))
                  (is
                   (true?
                    (h/visible? component-els/patch-view))
                   "the patch it delivered is shown")
                  (is
                   (true?
                    (.isVisible (h/wait-visible component-els/build-panel)))
                   "with the build that follows the change")
                  (is
                   (string/starts-with? (h/text run-els/pull-request-ref) "refs/changes/"))
                  (is
                   (string/includes? (h/text run-els/pull-request) "Patch set"))
                  (is
                   (= "demo-service"
                      (h/text run-els/project)))))

          (step "The runs list names the change the same way"
                (fn []
                  (h/goto "/runs")
                  (is
                   (string/starts-with? (h/text (component-els/run-pull-request @run-id)) "Change "))))

          (step "The project's page lists the run, its build and the open change"
                (fn []
                  (h/goto (str "/" (:tenant h/fixture) "/" (:team h/fixture) "/demo-service"))
                  (h/wait-visible project-els/page)
                  (open-project-tab :runs)
                  (is
                   (true?
                    (.isVisible (h/wait-visible (component-els/run-link @run-id)))))
                  (open-project-tab :builds)
                  (is
                   (true?
                    (.isVisible (h/wait-visible (project-els/build @run-id)))))
                  (open-project-tab :changes)
                  (is
                   (true?
                    (.isVisible (h/wait-visible (project-els/change @change-number) 30000))))))))
      (finally
        (abandon-change! @change-number)))))
