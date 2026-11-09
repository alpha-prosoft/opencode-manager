(ns manager-web.search-it
  "The global search: Ctrl+K opens it; it finds projects, teams and the
   person's own sessions and opens them; it never shows another tenant's
   things or someone else's private session."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-web.helpers :as h]
            [manager-web.project.elements :as project-els]
            [manager-web.run.elements :as run-els]
            [manager-web.search.elements :as els]
            [manager-web.session.elements :as session-els]
            [manager-web.team.elements :as team-els]
            [testing.pw :refer [*current-page* with-ui step]])
  (:import [com.microsoft.playwright Page]))

(def session-title
  (str "Search target " (subs (str (random-uuid)) 0 8)))

(def session-id
  (atom nil))

(defn- open-search
  []
  (.press (.keyboard ^Page *current-page*) "Control+K")
  (h/wait-visible els/dialog))

(defn- search
  [text]
  (open-search)
  (h/fill els/input text))

(defn- found?
  [element]
  (= element (h/wait-any element els/empty-state)))

(defn- nothing-found
  "True once the search says that nothing matches text (its answer to this
   text, not to the one typed before)."
  [text]
  (h/wait-until (str "no results for " text) h/default-timeout-ms 100
                #(and (h/visible? els/empty-state)
                      (string/includes? (h/text els/empty-state) (str "\"" text "\"")))))

(deftest search-it
  (with-ui [_page]
    (try
      (step "A person's own private session"
            (fn []
              (h/login (:member h/fixture))
              (h/goto (str "/" (:tenant h/fixture) "/" (:team h/fixture)))
              (h/start-workflow (:team h/fixture) (:session-workflow h/fixture) {:title session-title})
              (h/wait-visible run-els/session-link h/session-timeout-ms)
              (h/click run-els/session-link)
              (h/wait-visible session-els/page)
              (reset! session-id (h/path-id))))

      (step "Ctrl+K finds a project; arrows and Enter open it"
            (fn []
              (search "demo-serv")
              (is
               (found? (els/result :project "demo-service")))
              (is
               (= "true"
                  (h/wait-attribute (els/result :project "demo-service") "data-selected" "true")))
              (.press (h/locator els/input) "Enter")
              (h/wait-visible project-els/page)
              (is
               (= (str "/" (:tenant h/fixture) "/" (:team h/fixture) "/demo-service")
                  (h/current-path)))))

      (step "It finds a team by its name"
            (fn []
              (search "payments")
              (h/click (els/result :team "payments"))
              (h/wait-visible team-els/page)
              (is
               (= (str "/" (:tenant h/fixture) "/payments")
                  (h/current-path)))))

      (step "It finds the person's own session and opens it"
            (fn []
              (search (subs session-title 7))
              (h/click (els/result :session @session-id))
              (h/wait-visible session-els/page)
              (is
               (= @session-id
                  (h/path-id)))))

      (step "The search box of the sidebar opens it; the arrows move through the results and Enter opens one"
            (fn []
              (h/click els/open)
              (h/wait-visible els/dialog)
              (h/fill els/input "demo")
              (is
               (= "true"
                  (h/wait-attribute (els/result :project "demo-service") "data-selected" "true")))
              (.press (h/locator els/input) "ArrowDown")
              (is
               (= ["true" "false"]
                  [(h/wait-attribute (els/result :team (:team h/fixture)) "data-selected" "true")
                   (h/wait-attribute (els/result :project "demo-service") "data-selected" "false")]))
              (.press (h/locator els/input) "Enter")
              (h/wait-visible team-els/page)
              (is
               (= (str "/" (:tenant h/fixture) "/" (:team h/fixture))
                  (h/current-path)))))

      (step "Esc closes it"
            (fn []
              (open-search)
              (.press (h/locator els/input) "Escape")
              (h/wait-hidden els/dialog)
              (is
               (false? (h/visible? els/dialog)))))

      (step "Someone else of the tenant doesn't find the private session"
            (fn []
              (h/logout)
              (h/login (:teamless h/fixture))
              (search session-title)
              (is
               (nothing-found session-title))))

      (step "Another tenant finds neither the session nor the tenant's teams and projects"
            (fn []
              (.press (h/locator els/input) "Escape")
              (h/logout)
              (h/login (:other-tenant-member h/fixture))
              (search session-title)
              (is
               (nothing-found session-title))
              (h/fill els/input "demo")
              (is
               (nothing-found "demo"))))
      (finally
        (h/delete-sessions! (:member h/fixture) [@session-id])))))
