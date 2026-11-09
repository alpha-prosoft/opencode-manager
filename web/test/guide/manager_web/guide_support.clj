(ns manager-web.guide-support
  "What the guides share: the browser, switching people, reading the API as
   the person logged in and small UI steps."
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [manager-web.components.elements :as component-els]
            [manager-web.helpers :as h]
            [manager-web.login.elements :as login-els]
            [manager-web.setup.elements :as setup-els]
            [manager-web.shell.elements :as shell-els]
            [testing.pw :as pw])
  (:import [com.microsoft.playwright Browser Browser$NewContextOptions BrowserContext BrowserType$LaunchOptions
            Locator Page Playwright]
           [com.microsoft.playwright.options ColorScheme RequestOptions]))

(def tenant
  (:tenant h/fixture))

(defn start-browser
  "A light-theme 1440x900 page at device scale factor 2."
  []
  (let [playwright
        (Playwright/create)

        ^Browser browser
        (.launch (.chromium playwright)
                 (.setHeadless (BrowserType$LaunchOptions.)
                               (not= "false" (System/getProperty "playwright.browser.headless" "true"))))

        ^BrowserContext context
        (.newContext browser (doto (Browser$NewContextOptions.)
                               (.setViewportSize 1440 900)
                               (.setDeviceScaleFactor 2.0)
                               (.setColorScheme ColorScheme/LIGHT)))]
    {:playwright playwright
     :browser browser
     :page (.newPage context)}))

(defn stop-browser
  [{:keys [^Playwright playwright ^Browser browser]}]
  (try
    (.close browser)
    (.close playwright)
    (catch Exception e
      (println "Could not close the browser:" (ex-message e)))))

(defn page
  ^Page []
  pw/*current-page*)

(defn- keywordize
  [value]
  (cond
    (map? value) (into {} (map (fn [[k v]] [(keyword (string/replace (str k) #"^:" "")) (keywordize v)])) value)
    (sequential? value) (mapv keywordize value)
    (and (string? value) (string/starts-with? value ":")) (keyword (subs value 1))
    (and (string? value) (string/starts-with? value "#")) (subs value 1)
    :else value))

(defn query
  "The result of an edd query as the person logged in; nil when it fails."
  [query-id & {:as params}]
  (let [fields
        (into {"query-id" (str ":" (name query-id))}
              (map (fn [[k v]] [(name k) v]))
              params)

        body
        (json/write-str {"request-id" (str "#" (random-uuid))
                         "interaction-id" (str "#" (random-uuid))
                         "query" fields})

        response
        (.post (.request (page))
               (str h/base-url "/api/query")
               (-> (RequestOptions/create)
                   (.setHeader "Content-Type" "application/json")
                   (.setHeader "X-Requested-With" "manager-web")
                   (.setData ^String body)))]
    (when (= 200 (.status response))
      (:result (keywordize (json/read-str (.text response)))))))

(defn put-json!
  "PUT to one of the edge's own endpoints as the person logged in; the status."
  [path body]
  (.status (.put (.request (page))
                 (str h/base-url path)
                 (-> (RequestOptions/create)
                     (.setHeader "Content-Type" "application/json")
                     (.setHeader "X-Requested-With" "manager-web")
                     (.setData ^String (json/write-str body))))))

(defn settle
  "Waits until the page has stopped loading and rendering."
  ([]
   (settle 400))
  ([quiet-ms]
   (try
     (.waitForLoadState (page) com.microsoft.playwright.options.LoadState/NETWORKIDLE)
     (catch Exception _ nil))
   (Thread/sleep (long quiet-ms))))

(defn goto
  "Opens a path under the tenant (\"/runs\", \"/demo?tab=settings\") and settles."
  [path]
  (h/goto (str "/" tenant path))
  (settle))

(defn sign-out
  "Signs out whoever is signed in; on the login page afterwards."
  []
  (h/goto "/")
  (let [where
        (h/wait-any shell-els/logout setup-els/logout login-els/page)]
    (when (not= login-els/page where)
      (h/click where)
      (h/wait-visible login-els/page 30000))))

(defn as!
  "Logs in as username (setup, if any, is passed with placeholder values)."
  [username]
  (h/become username)
  (settle))

(defn confirm!
  []
  (h/wait-visible component-els/confirm-dialog)
  (h/click component-els/confirm-accept)
  (h/wait-hidden component-els/confirm-dialog))

(defn open-select
  "Opens a MUI select and waits for option."
  [element option]
  (h/click element)
  (h/wait-visible option)
  (Thread/sleep 300))

(defn close-menu
  []
  (.press (.keyboard (page)) "Escape")
  (Thread/sleep 300))

(defn tab!
  [tab-element]
  (h/click tab-element)
  (h/wait-attribute tab-element "aria-selected" "true")
  (settle))

(defn present?
  "True when an element with the el-id shows up within timeout-ms."
  [element timeout-ms]
  (try
    (h/wait-visible element timeout-ms)
    true
    (catch Exception _
      false)))

(defn state-of
  [element]
  (.getAttribute ^Locator (h/raw-locator element) "data-state"))

(def ^:private created-file
  "Ids of what the guides created, kept across runs so that a later run
   removes what a crashed one left behind. Nothing in the UI shows them."
  (java.io.File. "target/guide/created.edn"))

(defn- created
  []
  (if (.isFile created-file)
    (read-string (slurp created-file))
    {}))

(defn created!
  "Remembers that the guides created id of kind (:sessions, :webhooks)."
  [kind id]
  (.mkdirs (.getParentFile created-file))
  (spit created-file (pr-str (update (created) kind (fnil conj #{}) (str id)))))

(defn delete-created!
  "Deletes, as the person logged in, the sessions or webhooks the guides
   created and may manage; forgets those that are gone."
  [kind query-id cmd-id]
  (let [rows
        (query query-id)

        mine
        (get (created) kind #{})

        present
        (filter #(contains? mine (str (:id %))) rows)]
    (doseq [row present
            :when (:can-manage row true)]
      (h/command! cmd-id (:id row)))
    (when (some? rows)
      (let [left
            (set (keep #(when-not (:can-manage % true) (str (:id %))) present))]
        (spit created-file (pr-str (assoc (created) kind left)))))))

(defn delete-guide-sessions!
  []
  (delete-created! :sessions :list-sessions :delete-session))

(defn delete-guide-webhooks!
  []
  (delete-created! :webhooks :list-webhooks :delete-webhook))

(def change-workflow
  "implement-gerrit")

(def change-task
  (str "Add a function subtotal(prices: list[float]) -> float to demo/pricing.py that returns the rounded sum "
       "of the prices, with a unit test in tests/test_pricing.py. Run the tests with python3 -m unittest."))

(defn- change-run
  "A run of the change workflow that delivered, or may still deliver, a change."
  []
  (first (filter #(and (= change-workflow (:workflow-id %))
                       (not (contains? #{:failed :cancelled} (:status %))))
                 (query :list-runs :team-id (:team h/fixture)))))

(defn ensure-change-run!
  "The id of a run that implements a small change in the demo project, pushes
   it to Gerrit and is followed to its Jenkins build: the one there is, else
   one started now as the person logged in. The project's changes and builds
   are shown from it."
  []
  (or (some-> (change-run) :id str)
      (do
        (goto (str "/" (:team h/fixture)))
        (let [run-id
              (h/start-workflow (:team h/fixture) change-workflow {:task change-task})]
          (created! :runs run-id)
          (println "  [guide] started the change run" run-id)
          run-id))))

(defn delete-created-run-sessions!
  "Deletes the sessions of the runs the guides started, as the person logged in."
  []
  (doseq [run-id (get (created) :runs)]
    (when-let [session-id
               (:session-id (query :get-run :run-id (str "#" run-id)))]
      (h/command! :delete-session session-id)))
  (spit created-file (pr-str (assoc (created) :runs #{}))))

(def started-at
  "When this program started, as the platform writes timestamps."
  (str (java.time.Instant/now)))

(defn delete-triggered-sessions!
  "Deletes the sessions of the runs the platform started in the demo team
   since this program began: the review of the change the guides pushed."
  []
  (doseq [run (query :list-runs :team-id (:team h/fixture))
          :when (and (:platform-started run)
                     (:session-id run)
                     (pos? (compare (str (:created-at run)) started-at)))]
    (h/command! :delete-session (:session-id run))))
