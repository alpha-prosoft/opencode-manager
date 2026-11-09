(ns manager-web.guide-seed
  "The cast the guides are shot with: a week of the example company acme,
   played through the platform's API by its own people, always in the same
   order with the same names, inputs and ids. It needs the documentation
   stack (make docs-stack), whose scripted LLM answers each of these tasks
   the same way every time (images/llm-script/scenarios.json), and runs once
   per stack: a stack that has the first seeded run is left as it is."
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [manager-web.guide-support :as s]
            [manager-web.helpers :as h])
  (:import [com.microsoft.playwright.options RequestOptions]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.util UUID]))

(defn id
  "The fixed id of something the seed creates."
  [what]
  (str (UUID/nameUUIDFromBytes (.getBytes (str "guide-seed:" what) "UTF-8"))))

(def passing-change
  (id "run:subtotal"))

(def failing-change
  (id "run:percent-discount"))

(def branch-review
  (id "run:branch-review"))

(def patch-run
  (id "run:negative-prices"))

(def webhook
  (id "webhook:service-desk"))

(def webhook-name
  "Service desk intake")

(def webhook-secret
  "whsec_guide_seed_3c1f8a7e2b9d4056")

(def analysis-brief
  (str "Sales wants a volume discount for large orders instead of the single 10% rule applied by hand. "
       "Describe the tiers, the boundaries and what has to be tested."))

(def failing-task
  (str "Change total() in demo/pricing.py so that the discount is given in percent (10 means 10%), "
       "as the checkout API sends it. Add a test."))

(def patch-task
  "Make total() raise ValueError when a price is negative, with a test.")

(def tickets
  "What the service desk sent to the webhook, in order: the issue's summary
   and the secret it was sent with."
  [["Customers can't pay with stored cards since Monday" webhook-secret]
   ["Invoices show the wrong VAT rate for Austrian customers" "whsec_an_old_secret"]
   ["Invoices show the wrong VAT rate for Austrian customers" webhook-secret]
   ["VAT" webhook-secret]
   ["The export of the March invoices times out for large customers" webhook-secret]])

(defn- command!
  [cmd-id target attrs]
  (let [status
        (h/command! cmd-id target (when attrs (json/write-str attrs)))]
    (when (not= 200 status)
      (throw (ex-info (str "The seed's " (name cmd-id) " answered " status) {:attrs attrs})))))

(defn- put!
  [path body]
  (let [status
        (s/put-json! path body)]
    (when (not= 200 status)
      (throw (ex-info (str "The seed's PUT " path " answered " status) {})))))

(defn- run
  [run-id]
  (s/query :get-run :run-id (str "#" run-id)))

(defn- start!
  "Starts a workflow as the person logged in under a fixed run id."
  [what team workflow inputs & {:keys [visibility cmd] :or {cmd :start-run}}]
  (let [run-id
        (id (str "run:" what))]
    (when-not (run run-id)
      (command! cmd run-id (cond-> {:team-id team :workflow-id workflow :inputs inputs}
                             visibility (assoc :visibility (str ":" (name visibility)))))
      (println "  [seed]" what "started in" team))
    run-id))

(defn- wait-for
  [what timeout-ms f]
  (h/wait-until (str "the seed's " what) timeout-ms 2000 f))

(defn- finished!
  "Waits until the run is completed; a run that fails stops the seed."
  [what run-id]
  (wait-for what h/run-timeout-ms
            #(let [{:keys [status error-message]} (run run-id)]
               (when (contains? #{:failed :cancelled} status)
                 (throw (ex-info (str "The seed's " what " ended " (name status) ": " error-message) {})))
               (= :completed status))))

(defn- session-of
  [run-id]
  (some-> (wait-for "session" h/session-timeout-ms #(:session-id (run run-id)))
          str
          (string/replace #"^#" "")))

(defn- session
  [session-id]
  (s/query :get-session :session-id (str "#" session-id)))

(defn- answered!
  "Waits until the session's agent has made `calls` LLM calls."
  [what session-id calls]
  (wait-for (str what " to be answered") h/session-timeout-ms
            #(<= calls (get-in (session session-id) [:usage :calls] 0))))

(defn- workspace!
  "A workspace of the person logged in; the id of its session once its
   first prompt (if any) has been answered with `calls` LLM calls."
  [what team title prompt calls & {:as options}]
  (let [run-id
        (start! what team "workspace" (cond-> {:title title} prompt (assoc :prompt prompt)) options)

        session-id
        (session-of run-id)]
    (finished! what run-id)
    (wait-for (str what " to run") h/session-timeout-ms #(= :running (:status (session session-id))))
    (when prompt
      (answered! what session-id calls))
    session-id))

(defn- credential!
  [credential-id username]
  (put! (str "/api/credentials/" credential-id)
        {"secret" "guide-placeholder-value" "username" username}))

(defn- ask-in-opencode!
  "Asks a follow-up question in the session's OpenCode, with another of the
   harness's models, as its web UI does."
  [session-id title model text]
  (let [{:keys [url open-url]}
        (session session-id)

        post!
        (fn [path body]
          (let [response
                (.post (.request (s/page))
                       (str url path)
                       (-> (RequestOptions/create)
                           (.setHeader "Content-Type" "application/json")
                           (.setData ^String (json/write-str body))))]
            (when (<= 300 (.status response))
              (throw (ex-info (str "OpenCode answered " (.status response) " to " path) {})))
            (.text response)))]
    (h/goto open-url)
    (s/settle 1500)
    (let [conversation
          (get (json/read-str (post! "/session" {"title" title})) "id")]
      (post! (str "/session/" conversation "/prompt_async")
             {"model" {"providerID" "platform" "modelID" model}
              "parts" [{"type" "text" "text" text}]}))
    (h/goto "/")))

(defn- call-webhook!
  "A call of the service desk: no login, only the webhook's secret; the status."
  [summary secret]
  (let [request
        (-> (HttpRequest/newBuilder (URI/create (str h/base-url "/hooks/" s/tenant "/" webhook)))
            (.header "Content-Type" "application/json")
            (.header "X-Webhook-Token" secret)
            (.POST (HttpRequest$BodyPublishers/ofString (json/write-str {"issue" {"summary" summary}})))
            (.build))]
    (.statusCode (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString)))))

(defn- synced!
  []
  (s/as! "oscar")
  (wait-for "tenants to be synced" 300000
            #(let [tenants (s/query :list-tenants)]
               (and (seq tenants)
                    (every? (fn [tenant] (and (:synced-at tenant) (nil? (:error-message tenant)))) tenants)))))

(defn- bob-starts-the-week
  []
  (s/as! "bob")
  (start! "subtotal" "demo" s/change-workflow {:task s/change-task})
  (start! "branch-review" "demo" "review-branch" {:branch "e2e-review" :base "main"})
  (workspace! "pricing-questions" "demo" "Pricing questions" "Explain how the discount in total() works." 2
              :visibility :team))

(defn- dave-in-payments-and-ops
  []
  (s/as! "dave")
  (put! "/api/teams/payments/credentials/NEXUS_TOKEN" {"secret" "guide-placeholder-value"})
  (workspace! "payment-export" "payments" "Payment export job" "Run the tests and tell me the result." 2
              :visibility :team)
  (workspace! "on-call-handover" "ops" "On-call handover notes" nil 0))

(defn- erin-in-payments
  []
  (s/as! "erin")
  (credential! "JIRA_TOKEN" "erin@acme.test")
  (workspace! "invoice-rounding" "payments" "Invoice rounding" "Explain how the discount in total() works." 2))

(defn- henry-in-ops
  []
  (s/as! "henry")
  (workspace! "pager-rota" "ops" "Pager rota questions" "Who is on call for the demo service this week?" 1))

(defn- alice-reviews-and-shares
  []
  (s/as! "alice")
  (credential! "JIRA_TOKEN" "alice@acme.test")
  (start! "negative-prices" "demo" "implement-change" {:task patch-task})
  (let [session-id
        (workspace! "onboarding-walkthrough" "demo" "Onboarding walkthrough"
                    "List the modules in this repository and say in one line what each does." 3
                    :visibility :tenant)]
    (when-not (:locked (session session-id))
      (command! :lock-session session-id nil)))
  (finished! "passing change and its build" passing-change))

(defn- save-webhook!
  []
  (put! (str "/api/webhooks/" webhook)
        {"name" webhook-name
         "description" "The service desk files an analysis for every new incident."
         "scope" {"kind" "team" "team-id" "demo"}
         "target" {"team-id" "demo" "workflow-id" "requirements"}
         "auth" {"type" "header" "header-name" "X-Webhook-Token" "secret" webhook-secret}
         "mapping" [{"input" "brief" "source" "body" "value" "$.issue.summary"}]
         "rate-limit-per-minute" 30
         "enabled" true}))

(defn- frank-runs-the-team
  []
  (s/as! "frank")
  (credential! "JIRA_TOKEN" "frank@acme.test")
  (start! "volume-discount" "demo" "requirements" {:brief analysis-brief})
  (when-not (s/query :get-webhook :webhook-id (str "#" webhook))
    (save-webhook!)
    (doseq [[summary secret] tickets]
      (println "  [seed] the service desk called the webhook:" (call-webhook! summary secret))
      (Thread/sleep 1500)))
  (start! "sprint-review" "demo" "workspace" {:title "Sprint review workspace"}
          :cmd :start-team-session :visibility :team)
  (start! "percent-discount" "demo" s/change-workflow {:task failing-task})
  (finished! "failing change and its build" failing-change)
  (let [analysis
        (wait-for "build failure analysis" h/run-timeout-ms
                  #(some-> (run failing-change) :result :ci :follow-up-run str (string/replace #"^#" "")))]
    (finished! "build failure analysis" analysis)))

(defn- bob-follows-up
  []
  (s/as! "bob")
  (let [questions
        (session-of (id "run:pricing-questions"))

        walkthrough
        (session-of (id "run:onboarding-walkthrough"))

        fork
        (id "session:walkthrough-fork")]
    (when (< (get-in (session questions) [:usage :calls] 0) 4)
      (ask-in-opencode! questions "Tests" "gemini-3.5-flash" "Run the tests and tell me the result.")
      (answered! "bob's second question" questions 4))
    (when-not (session fork)
      (command! :fork-session fork {:source-id (str "#" walkthrough)}))
    (wait-for "fork to run" h/session-timeout-ms #(= :running (:status (session fork))))))

(defn- dave-stops-his-session
  []
  (s/as! "dave")
  (let [session-id
        (session-of (id "run:payment-export"))]
    (when (= :running (:status (session session-id)))
      (command! :stop-session session-id nil))
    (wait-for "dave's session to stop" 120000 #(= :stopped (:status (session session-id))))))

(defn- unfinished
  [runs]
  (remove #(contains? #{:completed :failed :cancelled} (:status %)) runs))

(defn quiet!
  "Waits, as someone who sees every run of the tenant, until every run is
   over and the usage of the last LLM call is recorded."
  []
  (wait-for "runs to finish" h/run-timeout-ms #(empty? (unfinished (s/query :list-runs))))
  (let [usage
        (atom nil)]
    (wait-for "usage to settle" 120000
              #(let [now (s/query :get-admin-usage :days 1)
                     same? (= now @usage)]
                 (reset! usage now)
                 same?))))

(defn- settled!
  "As the tenant admin: the first scheduled run of Operations has happened
   and everything the cast started is over."
  []
  (s/as! "alice")
  (wait-for "first scheduled run in Operations" (* 20 60 1000)
            #(some (fn [row] (= "platform:schedule" (:created-by row))) (s/query :list-runs :team-id "ops")))
  (quiet!))

(defn scripted-llm?
  "True when the example tenant the stack was seeded with (the docs variant
   of scripts/seed-local-repos.sh) calls the scripted LLM."
  []
  (let [published
        (java.io.File. "../local/repos/acme-onboarding/tenant.json")]
    (and (.isFile published)
         (string/includes? (slurp published) "http://llm-script:"))))

(defn seeded?
  "True when the stack carries the cast; asked as someone logged in to acme."
  []
  (some? (run passing-change)))

(defn seed!
  []
  (println "\n== Seed: a week at acme")
  (let [started
        (System/currentTimeMillis)]
    (synced!)
    (bob-starts-the-week)
    (dave-in-payments-and-ops)
    (erin-in-payments)
    (henry-in-ops)
    (alice-reviews-and-shares)
    (frank-runs-the-team)
    (bob-follows-up)
    (dave-stops-his-session)
    (settled!)
    (println "  [seed] took" (quot (- (System/currentTimeMillis) started) 1000) "s")))

(defn ensure-seeded!
  "Seeds a documentation stack once; true when the stack carries the cast."
  []
  (s/as! "alice")
  (cond
    (seeded?)
    (do (println "The stack already carries the seeded cast.")
        true)

    (scripted-llm?)
    (do (seed!)
        true)

    :else
    (do (println "WARNING: this stack does not use the scripted LLM, so the cast is not seeded:"
                 "figures that show it are left out. Run make docs-stack for the full guides.")
        false)))
