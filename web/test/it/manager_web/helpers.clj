(ns manager-web.helpers
  "Shared integration-test steps. Elements are found by the el-ids the views
   use (manager-web.*.elements), never by text or structure."
  (:require [clojure.string :as string]
            [manager-web.components.elements :as component-els]
            [manager-web.el :as el]
            [manager-web.login.elements :as login-els]
            [manager-web.run.elements :as run-els]
            [manager-web.session.elements :as session-els]
            [manager-web.setup.elements :as setup-els]
            [manager-web.shell.elements :as shell-els]
            [testing.pw :refer [*current-page*]])
  (:import [com.microsoft.playwright Download Locator Locator$FilterOptions Locator$WaitForOptions Page]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [com.microsoft.playwright.options RequestOptions WaitForSelectorState]
           [java.nio.file Files]))

(def base-url
  (or (System/getenv "MANAGER_URL")
      (System/getProperty "manager.url")
      "http://localhost:18000"))

(def fixture
  "What the tests expect of the tenants the server runs with (MANAGER_TENANTS,
   the example onboarding repositories published by scripts/seed-local-repos.sh)
   and of its dev users file (MANAGER_DEV_USERS_FILE) in test login mode:
   tenant acme with tenant admin and demo team admin alice, member bob of team
   demo with the valid workflows bitbucket-workspace, requirements (analysis)
   and workspace (session), the personal credential JIRA_TOKEN (kind basic,
   provided by each user, no test request), BITBUCKET_TOKEN (checked against
   its service, tenant value from a secret file), NEXUS_TOKEN (provided only by
   tenant and team admins), the LLM connection copilot,
   the MCP server bitbucket; tenant globex with tenant admin gina; operator
   oscar; erin, who is not in demo and whose payments harness exporter
   needs a credential nobody added; carol, in acme but in no team; hank, a
   member of globex. People in two teams: bob (member of demo and payments), dave
   (admin of payments, member of ops) and ivy of globex (member of research, admin
   of lab); henry is only in ops. acme requires PAGERDUTY_TOKEN (tenant.json,
   teams ops) and ops requires JIRA_TOKEN (its team.json) of each person in ops,
   so dave and henry must add their own. An operator registers tenants from the onboarding
   repository MANAGER_IT_TENANT_ONBOARDING (default the compose path of
   globex's)."
  {:tenant-admin "alice"
   :tenant "acme"
   :member "bob"
   :other-tenant "globex"
   :other-tenant-admin "gina"
   :operator "oscar"
   :outsider "erin"
   :teamless "carol"
   :other-tenant-member "hank"
   :two-teams-member "bob"
   :two-teams-admin "dave"
   :other-team-member "henry"
   :other-tenant-two-teams "ivy"
   :other-team "research"
   :register-onboarding (or (System/getenv "MANAGER_IT_TENANT_ONBOARDING") "file:///repos/globex-onboarding")
   :team "demo"
   :workflow "bitbucket-workspace"
   :analysis-workflow "requirements"
   :session-workflow "workspace"
   :personal-credential "JIRA_TOKEN"
   :checked-credential "BITBUCKET_TOKEN"
   :admin-only-credential "NEXUS_TOKEN"
   :required-member "henry"
   :required-team "ops"
   :required-workflow "workspace"
   :tenant-required-credential "PAGERDUTY_TOKEN"
   :team-required-credential "JIRA_TOKEN"
   :connection "copilot"
   :mcp-server "bitbucket"
   :ready-harness ["demo" "bitbucket-tools"]
   :unready-harness ["payments" "exporter"]
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

(def auth-mode
  "The server's authentication mode from /api/auth/config: \"login\",
   \"oidc\" or \"jwt\", nil when it doesn't answer."
  (delay
    (let [response
          (.send (HttpClient/newHttpClient)
                 (.build (HttpRequest/newBuilder (URI/create (str base-url "/api/auth/config"))))
                 (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode response))
        (second (re-find #"\"mode\"\s*:\s*\"([a-z]+)\"" (.body response)))))))

(defn oidc-mode?
  []
  (= "oidc" @auth-mode))

(def ^:private keycloak-inputs
  "Keycloak's login page is a third-party page without el-ids; its login
   theme keeps these ids stable (see oidc_it)."
  {:username "#username"
   :password "#password"
   :submit "#kc-login"})

(defn keycloak-locator
  ^Locator [input]
  (.locator ^Page *current-page* ^String (get keycloak-inputs input)))

(defn sign-in-at-keycloak
  "Signs in through Keycloak; the local realm's password is the username."
  [username]
  (click login-els/sign-in)
  (.waitFor (keycloak-locator :username) (doto (Locator$WaitForOptions.) (.setTimeout 30000.0)))
  (.fill (keycloak-locator :username) username)
  (.fill (keycloak-locator :password) username)
  (.click (keycloak-locator :submit)))

(defn submit-login
  "Logs in as username without leaving the setup page."
  [username]
  (goto "/login")
  (if (oidc-mode?)
    (sign-in-at-keycloak username)
    (do (fill login-els/username username)
        (.press (locator login-els/username) "Escape")
        (click login-els/submit))))

(defn enabled?
  [element]
  (and (visible? element) (.isEnabled (locator element))))

(defn- step-saved?
  "The visible credential form is a fresh one (the next step's), or setup is done."
  []
  (or (not (visible? setup-els/page))
      (enabled? setup-els/later)
      (and (visible? component-els/credential-secret)
           (= "" (.inputValue (locator component-els/credential-secret))))))

(defn add-required-in-setup
  "Saves a placeholder value for each required step setup won't let the
   person leave for later."
  []
  (loop [steps 0]
    (when (and (< steps 10) (visible? setup-els/later) (not (enabled? setup-els/later)))
      (when (visible? component-els/credential-username)
        (fill component-els/credential-username "it-user@example.com"))
      (fill component-els/credential-secret "it-required-value")
      (click component-els/credential-submit)
      (wait-until "the step to be saved" default-timeout-ms 200 step-saved?)
      (recur (inc steps)))))

(def ^:private announce-seed
  "Prints the seed of kaocha's random test order once, to repeat a pass."
  (delay
    (when-let [seed
               (some-> (resolve 'kaocha.testable/*test-plan*) deref :kaocha.plugin.randomize/seed)]
      (println (str "Test order: kaocha seed " seed " (repeat it with --seed " seed ")")))))

(defn login
  "Logs in (with the persona form, or through Keycloak in oidc mode); people
   with credentials to connect land on the setup page first, add the ones
   their teams require and leave it with \"Do this later\"."
  [username]
  @announce-seed
  (submit-login username)
  (when (= setup-els/page (wait-any shell-els/user-name setup-els/page))
    (when (= setup-els/later (wait-any setup-els/later setup-els/continue))
      (add-required-in-setup))
    (let [next-step
          (wait-until "the app or a way out of setup" default-timeout-ms 100
                      #(some (fn [element] (when (enabled? element) element))
                             [shell-els/user-name setup-els/later setup-els/continue]))]
      (when (not= shell-els/user-name next-step)
        (click next-step))))
  (wait-visible shell-els/user-name))

(defn logout
  []
  (click shell-els/logout)
  (wait-visible login-els/page 30000))

(defn fork!
  "Forks the open session, from the banner when it offers the fork, else from
   the actions, and confirms; waits for the copy's page."
  [session-id]
  (let [before
        (current-path)]
    (click (wait-any session-els/fork-to-continue (component-els/session-action :fork session-id)))
    (wait-visible component-els/confirm-dialog)
    (click component-els/confirm-accept)
    (wait-path-change before)))

(defn start-workflow
  "Starts the workflow from its start button on the current page with inputs
   ({field value}) and returns the id of the run page it opens."
  [team-id workflow-id inputs]
  (click (component-els/workflow-start team-id workflow-id))
  (wait-visible component-els/run-dialog)
  (let [before
        (current-path)]
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

(defn search
  "The query string of the page's URL."
  []
  (str (.evaluate ^Page *current-page* "() => location.search")))

(defn become
  "Logs in as username, whoever is logged in and wherever the page is."
  [username]
  (goto "/")
  (let [where
        (wait-any shell-els/logout setup-els/logout login-els/page)]
    (when (not= login-els/page where)
      (click where)
      (wait-visible login-els/page 30000)))
  (login username))

(defn command!
  "Sends an edd command as the person logged in in this browser; the HTTP
   status. attrs is the JSON of its attributes."
  ([cmd-id id]
   (command! cmd-id id nil))
  ([cmd-id id attrs]
   (let [fields
         (cond-> [(str "\"cmd-id\":\":" (name cmd-id) "\"")]
           id (conj (str "\"id\":\"#" id "\""))
           attrs (conj (str "\"attrs\":" attrs)))

         body
         (str "{\"request-id\":\"#" (random-uuid) "\",\"interaction-id\":\"#" (random-uuid)
              "\",\"commands\":[{" (string/join "," fields) "}]}")]
     (.status (.post (.request ^Page *current-page*)
                     (str base-url "/api/command")
                     (-> (RequestOptions/create)
                         (.setHeader "Content-Type" "application/json")
                         (.setHeader "X-Requested-With" "manager-web")
                         (.setData ^String body)))))))

(defn clean-up!
  "Sends commands ([cmd-id id] or [cmd-id id attrs-json], those without what
   they name left out) as owner, whatever the test left behind; for a
   finally, so it never throws."
  [owner commands]
  (when-let [due
             (seq (filter (fn [[_ id attrs]] (or id attrs)) commands))]
    (try
      (become owner)
      (doseq [[cmd-id id attrs] due]
        (command! cmd-id id attrs))
      (catch Exception e
        (println "Could not clean up after" owner (ex-message e))))))

(defn delete-sessions!
  "Deletes the sessions as their owner; for a finally."
  [owner session-ids]
  (clean-up! owner (mapv (fn [id] [:delete-session id]) session-ids)))
