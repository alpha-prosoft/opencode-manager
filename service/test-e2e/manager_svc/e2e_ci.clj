(ns manager-svc.e2e-ci
  "End-to-end checks of the Gerrit and Jenkins flows of compose.ci.yml,
   with the real LLM:

     docker compose -f compose.yml -f compose.ci.yml up -d --build
     cd service && MANAGER_URL=http://localhost:8080 clojure -M:e2e-ci

   A change with a deliberate bug is pushed to Gerrit; the gerrit-review
   trigger reviews it and the review (summary, inline comments, vote)
   appears on the change. implement-gerrit pushes a change, Jenkins builds
   it and the run follows the build; a drill whose change breaks a test
   follows the failed build into the build-failure analysis. manager-svc.e2e
   runs this section when Gerrit and Jenkins are reachable."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]
            [jsonista.core :as json]
            [manager-svc.e2e :as e2e])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.time Duration Instant]
           [java.util Base64 UUID]))

(def gerrit-url
  (or (System/getenv "GERRIT_URL") "http://gerrit.localhost:8080"))

(def jenkins-url
  (or (System/getenv "JENKINS_URL") "http://jenkins.localhost:8080"))

(def gerrit-admin
  ["admin" (or (System/getenv "GERRIT_ADMIN_PASSWORD") "gerrit-admin-secret")])

(def jenkins-platform
  ["platform" (or (System/getenv "JENKINS_PLATFORM_TOKEN") "1100000000000000000000000000000001")])

(def project
  "demo-service")

(def votes
  {"APPROVE" 1 "APPROVE_WITH_NITS" 0 "REQUEST_CHANGES" -1})

(def ^:private http
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn- basic
  [[username secret]]
  (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes (str username ":" secret) StandardCharsets/UTF_8))))

(defn- http-get
  [url auth]
  (try
    (let [request
          (cond-> (-> (HttpRequest/newBuilder (URI/create url))
                      (.timeout (Duration/ofSeconds 30)))
            auth (.header "Authorization" (basic auth)))

          response
          (.send ^HttpClient http (.build (.GET request)) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response) :body (.body response)})
    (catch Exception e
      {:status 0 :body (ex-message e)})))

(defn- read-json
  [text]
  (json/read-value (string/replace-first (str text) ")]}'" "") json/keyword-keys-object-mapper))

(defn- gerrit
  [path]
  (let [{:keys [status body]}
        (http-get (str gerrit-url "/a" path) gerrit-admin)]
    (when (= 200 status)
      (read-json body))))

(defn- jenkins
  [path]
  (let [{:keys [status body]}
        (http-get (str jenkins-url path) jenkins-platform)]
    (when (= 200 status)
      (read-json body))))

(defn- http-post
  [url auth body]
  (try
    (let [request
          (-> (HttpRequest/newBuilder (URI/create url))
              (.timeout (Duration/ofSeconds 30))
              (.header "Authorization" (basic auth))
              (.header "Content-Type" "application/json")
              (.POST (HttpRequest$BodyPublishers/ofString body)))]
      (.statusCode (.send ^HttpClient http (.build request) (HttpResponse$BodyHandlers/ofString))))
    (catch Exception _
      0)))

(def pushed-changes
  "Numbers of the Gerrit changes a pass pushed, abandoned at its end."
  (atom #{}))

(defn- pushed!
  [number]
  (when number
    (swap! pushed-changes conj number))
  number)

(defn reachable?
  []
  (and (= 200 (:status (http-get (str gerrit-url "/config/server/version") nil)))
       (= 200 (:status (http-get (str jenkins-url "/api/json") jenkins-platform)))))

(defn- wait-until
  [timeout-ms f]
  (let [deadline
        (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [value
            (f)]
        (cond
          value value
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 5000) (recur)))))))

(defn- change-id
  [text]
  (let [digest
        (.digest (MessageDigest/getInstance "SHA-1") (.getBytes (str text) StandardCharsets/UTF_8))]
    (apply str "I" (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- git!
  [dir & args]
  (let [{:keys [exit out err]}
        (apply shell/sh "git" "-c" (str "http.extraHeader=Authorization: " (basic gerrit-admin)) (concat args [:dir dir]))]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (first args) " failed: " err) {})))
    out))

(defn push-buggy-change!
  "Pushes examples/review-fixture (bulk_total with an off-by-one and a
   truncating round) as a new change; returns its Change-Id."
  [subject]
  (let [dir
        (.toFile (Files/createTempDirectory "e2e-gerrit" (make-array FileAttribute 0)))

        id
        (change-id (str subject (UUID/randomUUID)))]
    (try
      (git! (.getParent dir) "clone" "--quiet" (str gerrit-url "/" project) (.getPath dir))
      (io/copy (io/file ".." "examples" "review-fixture" "demo" "pricing.py") (io/file dir "demo" "pricing.py"))
      (git! dir "add" "--all")
      (git! dir "-c" "user.name=E2E" "-c" "user.email=e2e@example.com"
            "commit" "--quiet" "-m" (str subject "\n\nChange-Id: " id))
      (git! dir "push" "--quiet" (str gerrit-url "/a/" project) "HEAD:refs/for/main")
      id
      (finally
        (doseq [file (reverse (file-seq dir))]
          (io/delete-file file true))))))

(defn- schedule-ran-after
  [alice schedule-id after]
  (some #(when (and (= schedule-id (:id %))
                    (:last-run %)
                    (pos? (compare (:last-run %) after)))
           %)
        (e2e/result alice {:query-id :list-schedules})))

(defn- run-of-change
  [alice number]
  (some #(when (and (= "gerrit-review" (:workflow-id %))
                    (= (str number) (str (get-in % [:inputs :pull-request]))))
           %)
        (e2e/result alice {:query-id :list-runs :team-id "demo"})))

(defn gerrit-review
  [{:keys [alice]}]
  (e2e/section "Gerrit change review")
  (let [listed
        (e2e/query alice {:query-id :list-open-pull-requests :team-id "demo" :workflow-id "gerrit-review"})

        reachable-at
        (str (Instant/now))]
    (e2e/check "the platform lists Gerrit's open changes" (sequential? (:ok listed)) (:error listed))
    (e2e/check "the gerrit-review trigger polled Gerrit after it was reachable"
               (wait-until 180000 #(schedule-ran-after alice "trigger-acme-demo-gerrit-review" reachable-at)))
    (let [id
          (push-buggy-change! "e2e: bulk pricing for large orders")

          change
          (first (gerrit (str "/changes/?q=change:" id)))

          number
          (pushed! (:_number change))

          _
          (e2e/check "a change with a buggy diff is in Gerrit" number id)

          triggered
          (when number
            (wait-until 240000 #(run-of-change alice number)))

          _
          (e2e/check "the new change started a review run" triggered number)

          run
          (when triggered
            (e2e/wait-for-run alice (e2e/track-run! alice (:id triggered))))

          {:keys [review posted]}
          (:result run)

          detail
          (when number
            (gerrit (str "/changes/" number "/detail")))

          comments
          (when number
            (gerrit (str "/changes/" number "/comments")))

          platform-votes
          (keep #(when (= "platform" (:username %)) (:value %)) (get-in detail [:labels :Code-Review :all]))]
      (when (e2e/check "the review run completes" (= :completed (:status run)) (or (:error run) (:status run)))
        (e2e/check "the review found an issue in demo/pricing.py"
                   (some #(string/includes? (str (:path %)) "pricing.py") (:findings review))
                   (:findings review))
        (e2e/check "the review was posted" (pos? (or posted 0)) (:result run))
        (e2e/check "the summary is a message on the change"
                   (some #(string/includes? (str (:message %)) "Automated review") (:messages detail))
                   (map :message (:messages detail)))
        (e2e/check "inline comments are on the latest patch set"
                   (or (seq (get comments :demo/pricing.py)) (seq (vals comments)))
                   (keys comments))
        (e2e/check "the platform voted Code-Review as the verdict says"
                   (= [(get votes (:verdict review))] (vec platform-votes))
                   [(:verdict review) platform-votes])))))

(defn- verify-build-of
  [commit]
  (some #(when (string/includes? (str (:description %)) commit) %)
        (:builds (jenkins "/job/demo-service-verify/api/json?tree=builds%5Bnumber,result,description%5D"))))

(defn- implementation-checks
  [alice run-name run-id expected-state]
  (let [run
        (e2e/wait-for-run alice run-id)

        pull-request
        (get-in run [:result :pull-request])

        ci
        (get-in run [:result :ci])

        change
        (when (:id pull-request)
          (gerrit (str "/changes/" (pushed! (:id pull-request)) "?o=CURRENT_REVISION")))]
    (when (e2e/check (str run-name " run completes") (= :completed (:status run)) (or (:error run) (:status run)))
      (e2e/check (str run-name " pushed a change to Gerrit")
                 (and change (string/includes? (str (:url pull-request)) (str "/c/" project "/+/")))
                 pull-request)
      (e2e/check (str run-name " knows it delivered to Gerrit, for its project")
                 (= [:gerrit "demo-service"] [(:scm-provider run) (:project run)])
                 [(:scm-provider run) (:project run)])
      (e2e/check (str run-name " records the patch set ref")
                 (string/starts-with? (str (:branch pull-request)) (str "refs/changes/"))
                 (:branch pull-request))
      (e2e/check (str run-name " change holds the pushed commit")
                 (= (:commit pull-request) (:current_revision change))
                 [(:commit pull-request) (:current_revision change)])
      (e2e/check (str run-name " followed the Jenkins build to " expected-state)
                 (and (= expected-state (:state ci)) (number? (get-in ci [:build :number])))
                 ci)
      (e2e/check (str run-name " build is the verify build of the commit")
                 (= (get-in ci [:build :number]) (:number (verify-build-of (:commit pull-request))))
                 (verify-build-of (:commit pull-request))))
    run))

(defn implement-and-follow-builds
  [{:keys [alice]}]
  (e2e/section "Gerrit changes and Jenkins builds")
  (let [passing
        (e2e/start-run alice "demo" "implement-gerrit"
                       {:task "Add a function subtotal(prices: list[float]) -> float to demo/pricing.py that returns the rounded sum of the prices, with a unit test in tests/test_pricing.py. Run the tests with python3 -m unittest."})

        drill
        (e2e/start-run alice "demo" "implement-gerrit"
                       {:task "CI drill: we need to check that our build pipeline reports failing tests. In tests/test_pricing.py change the expected value in test_sum from 3.5 to 4.0 so that the test fails. Change nothing else and do not try to fix it."})]
    (let [run
          (implementation-checks alice "implement-gerrit" passing "passed")

          in-project
          {:team-id "demo" :project-id project}

          listed?
          (fn [query-id id]
            (some #(= (str id) (str (:id %))) (e2e/result alice (assoc in-project :query-id query-id))))]
      (e2e/check "the project's runs list the run of its workflow" (listed? :list-runs (:id run)) (:id run))
      (e2e/check "the project's sessions list the session it worked in"
                 (listed? :list-sessions (:session-id run))
                 (:session-id run)))
    (let [run
          (implementation-checks alice "implement-gerrit drill" drill "failed")

          follow-up-id
          (get-in run [:result :ci :follow-up-run])

          follow-up
          (when follow-up-id
            (e2e/wait-for-run alice (e2e/track-run! alice follow-up-id)))]
      (e2e/check "the failed build started the build-failure analysis"
                 (= "build-failure" (:workflow-id follow-up))
                 follow-up-id)
      (e2e/check "the build-failure analysis completes with a document"
                 (and (= :completed (:status follow-up))
                      (> (count (str (get-in follow-up [:result :document :markdown]))) 200))
                 (or (:error follow-up) (:status follow-up))))))

(defn abandon-changes!
  "Abandons the changes the pass pushed, so they are neither reviewed again
   nor pile up in Gerrit; the reviews the trigger started for them are left
   to the pass's cleanup."
  [{:keys [alice]}]
  (doseq [number @pushed-changes]
    (when-let [review (run-of-change alice number)]
      (e2e/track-run! alice (:id review)))
    (http-post (str gerrit-url "/a/changes/" number "/abandon") gerrit-admin "{\"message\":\"Abandoned by the e2e test\"}"))
  (reset! pushed-changes #{}))

(defn gerrit-and-jenkins
  "The section manager-svc.e2e runs; skipped when the CI stack is down."
  [people]
  (if (reachable?)
    (try
      (e2e/guarded "Gerrit change review" #(gerrit-review people))
      (e2e/guarded "Gerrit changes and Jenkins builds" #(implement-and-follow-builds people))
      (finally
        (abandon-changes! people)))
    (println "Gerrit and Jenkins are not reachable: skipping their section (docker compose -f compose.yml -f compose.ci.yml up -d)")))

(defn -main
  [& _args]
  (when-let [people
             (e2e/log-in ["alice"])]
    (try
      (if (reachable?)
        (let [review
              (future (e2e/guarded "Gerrit change review" #(gerrit-review people)))]
          (e2e/guarded "Gerrit changes and Jenkins builds" #(implement-and-follow-builds people))
          @review)
        (e2e/check "Gerrit and Jenkins are reachable" false gerrit-url))
      (finally
        (abandon-changes! people)
        (e2e/guarded "Cleanup" #(e2e/clean-up! people)))))
  (let [exit-code
        (e2e/summary!)]
    (shutdown-agents)
    (System/exit exit-code)))
