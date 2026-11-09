(ns manager-web.helpers
  "Shared integration-test steps. Elements are found by the el-ids the views
   use (manager-web.*.elements), never by text or structure."
  (:require [clojure.string :as string]
            [manager-web.components.elements :as component-els]
            [manager-web.el :as el]
            [manager-web.login.elements :as login-els]
            [manager-web.run.elements :as run-els]
            [manager-web.setup.elements :as setup-els]
            [manager-web.shell.elements :as shell-els]
            [testing.pw :refer [*current-page*]])
  (:import [com.microsoft.playwright Download Locator Locator$FilterOptions Locator$WaitForOptions Page]
           [com.microsoft.playwright.options WaitForSelectorState]
           [java.nio.file Files]))

(def base-url
  (or (System/getenv "MANAGER_URL")
      (System/getProperty "manager.url")
      "http://localhost:18000"))

(def fixture
  "What the tests expect of the onboarding repository the server runs with
   (MANAGER_ONBOARDING_URL, local/sources/onboarding as published by
   scripts/seed-local-repos.sh), in test login mode: team admin and platform
   admin alice, member bob of team demo with the valid workflows
   bitbucket-workspace, requirements (analysis) and workspace (session), the
   personal credential JIRA_TOKEN (kind basic, provided by each user, no test
   request), BITBUCKET_TOKEN (checked against its service, platform value from
   a secret file), the LLM connection copilot, the MCP server bitbucket, and
   carol, who is not in demo and whose devops harness exporter needs a
   credential nobody added."
  {:platform-admin "alice"
   :member "bob"
   :outsider "carol"
   :team "demo"
   :workflow "bitbucket-workspace"
   :analysis-workflow "requirements"
   :session-workflow "workspace"
   :personal-credential "JIRA_TOKEN"
   :checked-credential "BITBUCKET_TOKEN"
   :connection "copilot"
   :mcp-server "bitbucket"
   :ready-harness ["demo" "bitbucket-tools"]
   :unready-harness ["devops" "exporter"]
   :activity "implement"})

(def run-timeout-ms
  (* 15 60 1000))

(def session-timeout-ms
  (* 5 60 1000))

(defn read-secret
  "A value from local/secrets, nil when the file is missing."
  [secret-name]
  (let [file
        (java.io.File. (str "../local/secrets/" secret-name))]
    (when (.isFile file)
      (string/trim (slurp file)))))

(def default-timeout-ms
  15000)

(defn raw-locator
  "The first element with the el-id, visible or not (hidden inputs)."
  ^Locator [element]
  (.first (.locator ^Page *current-page* (el/sel element))))

(defn locator
  "The first visible element with the el-id; forms in collapsed steps share ids."
  ^Locator [element]
  (.first (.filter (.locator ^Page *current-page* (el/sel element))
                   (.setVisible (Locator$FilterOptions.) true))))

(defn wait-visible
  (^Locator [element]
   (wait-visible element default-timeout-ms))
  (^Locator [element timeout-ms]
   (let [loc
         (locator element)]
     (.waitFor loc (doto (Locator$WaitForOptions.)
                     (.setState WaitForSelectorState/VISIBLE)
                     (.setTimeout (double timeout-ms))))
     loc)))

(defn click
  [element]
  (.click (wait-visible element)))

(defn fill
  [element value]
  (.fill (wait-visible element) value))

(defn text
  [element]
  (.textContent (wait-visible element)))

(defn visible?
  [element]
  (.isVisible (locator element)))

(defn input-value
  [element]
  (.inputValue (raw-locator element)))

(defn wait-until
  "(f) once it is truthy, polling every interval-ms; throws after timeout-ms."
  ([what timeout-ms f]
   (wait-until what timeout-ms 500 f))
  ([what timeout-ms interval-ms f]
   (let [deadline
         (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (or (f)
           (if (< (System/currentTimeMillis) deadline)
             (do (Thread/sleep (long interval-ms))
                 (recur))
             (throw (ex-info (str "Timed out waiting for " what) {:timeout-ms timeout-ms}))))))))

(defn wait-any
  "Waits until one of elements is visible; returns it."
  [& elements]
  (wait-until (str "one of " (pr-str elements)) default-timeout-ms 100
              #(some (fn [element] (when (visible? element) element)) elements)))

(defn attribute
  [element attribute-name]
  (.getAttribute (wait-visible element) attribute-name))

(defn wait-attribute
  "The attribute once it has value, or its last value after 15 s."
  [element attribute-name value]
  (loop [tries 0]
    (let [current
          (attribute element attribute-name)]
      (if (or (= value current) (>= tries 150))
        current
        (do (Thread/sleep 100)
            (recur (inc tries)))))))

(defn wait-status
  "Waits until the status chip element shows status (a keyword); returns it."
  [element status timeout-ms]
  (wait-until (str element " to be " (name status)) timeout-ms 1000
              #(when (= (name status) (.getAttribute (raw-locator element) "data-status"))
                 status)))

(defn wait-run-finished
  "Waits until the run status chip element shows a final status; returns it."
  [element timeout-ms]
  (wait-until (str element " to finish") timeout-ms 2000
              #(#{:completed :failed :cancelled}
                (some-> (.getAttribute (raw-locator element) "data-status") keyword))))

(defn wait-hidden
  ([element]
   (wait-hidden element default-timeout-ms))
  ([element timeout-ms]
   (.waitFor (locator element) (doto (Locator$WaitForOptions.)
                                 (.setState WaitForSelectorState/HIDDEN)
                                 (.setTimeout (double timeout-ms))))))

(defn current-path
  "The app's path; read from the page, since Page.url can miss history.pushState."
  []
  (str (.evaluate ^Page *current-page* "() => location.pathname")))

(defn path-id
  "The last segment of the current path, e.g. the id of /runs/<id>."
  []
  (last (string/split (current-path) #"/")))

(defn wait-path-change
  "Waits until the path differs from previous; returns it."
  [previous]
  (wait-until (str "leaving " previous) default-timeout-ms 100
              #(let [path (current-path)]
                 (when (not= previous path) path))))

(defn download
  "Clicks element and returns {:file-name :text} of the download it starts."
  [element]
  (let [^Download file
        (.waitForDownload ^Page *current-page* ^Runnable #(click element))]
    {:file-name (.suggestedFilename file)
     :text (String. (Files/readAllBytes (.path file)) "UTF-8")}))

(defn select-option
  "Opens the MUI select element and picks option."
  [element option]
  (click element)
  (click option)
  (wait-hidden option))

(defn reload
  []
  (.reload ^Page *current-page*))

(defn goto
  [path]
  (.navigate ^Page *current-page* (str base-url path)))

(defn login
  "Logs in; people with credentials to connect land on the setup page first
   and leave it with \"Do this later\"."
  [username]
  (goto "/login")
  (fill login-els/username username)
  (.press (locator login-els/username) "Escape")
  (click login-els/submit)
  (when (= setup-els/page (wait-any shell-els/user-name setup-els/page))
    (click setup-els/later))
  (wait-visible shell-els/user-name))

(defn submit-login
  "Logs in as username without leaving the setup page."
  [username]
  (goto "/login")
  (fill login-els/username username)
  (.press (locator login-els/username) "Escape")
  (click login-els/submit))

(defn logout
  []
  (click shell-els/logout)
  (wait-visible login-els/page))

(defn start-workflow
  "Starts the workflow from its start button on the current page with inputs
   ({field value}) and returns the id of the run page it opens."
  [team-id workflow-id inputs]
  (let [before
        (current-path)]
    (click (component-els/workflow-start team-id workflow-id))
    (wait-visible component-els/run-dialog)
    (doseq [[field value] inputs]
      (fill (component-els/schema-field (name field)) value))
    (click component-els/run-dialog-submit)
    (wait-until "the run page" 60000 200
                #(cond
                   (visible? component-els/run-dialog-error)
                   (throw (ex-info (str "The run did not start: " (text component-els/run-dialog-error)) {}))

                   (not= before (current-path))
                   true))
    (wait-visible run-els/page)
    (path-id)))
