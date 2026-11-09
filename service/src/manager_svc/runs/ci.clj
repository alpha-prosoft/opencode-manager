(ns manager-svc.runs.ci
  "Following the build of a pull request a run opened, through the
   get_build, get_builds and get_build_log tools of the CI system's MCP
   server (Jenkins). A job with {pr} in its name is a job per pull request
   whose last build is followed; any other job is shared (a Gerrit verify
   job) and the build whose description names the pushed commit is
   followed. Tests put (fn [system run ci tool arguments]) under :runs/ci in
   the system map."
  (:require [clojure.string :as string]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.definitions :as definitions]
            [manager-svc.runs.model :as model]
            [manager-svc.time :as time]))

(def poll-seconds 60)

(def tool-timeout-seconds 30)

(defn tool-failed
  [message]
  (ex-info message {:tool-failed true}))

(defn tool-failed?
  [e]
  (or (true? (:tool-failed (ex-data e)))
      (credentials/mcp-tool-failed? e)))

(defn- mcp-tool
  [system run {:keys [mcp-server]} tool arguments]
  (let [server
        (or (some-> (platform/mcp-server system (:tenant run) mcp-server) definitions/kebab)
            (throw (tool-failed (str "MCP server " mcp-server " is not configured"))))

        credential-id
        (:credential server)

        resolved
        (when credential-id
          (or (:value (credentials/resolve-credential system (model/credential-scope run) credential-id))
              (throw (tool-failed (str "credential " credential-id " is not set up")))))]
    (credentials/call-mcp-tool server resolved tool arguments tool-timeout-seconds)))

(defn tool
  [system run ci tool-name arguments]
  ((or (:runs/ci system) mcp-tool) system run ci tool-name arguments))

(defn follow-config
  "The workflow's options.ci when the run opened a pull request, else nil."
  [workflow run]
  (let [ci
        (get-in workflow [:options :ci])]
    (when (and ci (get-in run [:result :pull-request]))
      (merge {:timeout-minutes 120} ci))))

(defn job-name
  [ci pull-request]
  (string/replace (:job ci) "{pr}" (str (:id pull-request))))

(defn per-pull-request?
  [ci]
  (string/includes? (str (:job ci)) "{pr}"))

(def builds-searched
  20)

(defn build-of-commit
  "The newest build whose description names commit (its first 12
   characters are enough), or nil."
  [builds commit]
  (let [short
        (subs (str commit) 0 (min 12 (count (str commit))))]
    (when-not (string/blank? short)
      (some #(when (string/includes? (str (:description %)) short) %) builds))))

(defn- epoch-millis
  [iso]
  (.toEpochMilli (time/parse iso)))

(defn build-state
  "{:state :build :detail} from a get_build answer for the pushed change;
   with pushed-at, builds that started more than a minute before the push
   are not it."
  [build pushed-at]
  (let [pushed
        (if pushed-at (epoch-millis pushed-at) 0)]
    (if (or (not (map? build))
            (< (or (:started build) 0) (- pushed 60000)))
      {:state "waiting" :detail "No build of the pushed change yet"}
      {:state (cond
                (:building build) "running"
                (= "SUCCESS" (:result build)) "passed"
                :else "failed")
       :build build
       :detail nil})))

(defn- shared-job-state
  [system run ci job commit]
  (if-let [found
           (build-of-commit (tool system run ci "get_builds" {:job job :limit builds-searched}) commit)]
    (build-state (tool system run ci "get_build" {:job job :number (str (:number found))}) nil)
    {:state "waiting" :detail "No build of the pushed change yet"}))

(defn poll
  "Checks the build; returns the changes to the run's result.ci."
  [system run ci]
  (let [pull-request
        (get-in run [:result :pull-request])

        job
        (get-in run [:result :ci :job])]
    (try
      (if (per-pull-request? ci)
        (build-state (tool system run ci "get_build" {:job job :number "lastBuild"})
                     (:pushed-at pull-request))
        (shared-job-state system run ci job (:commit pull-request)))
      (catch clojure.lang.ExceptionInfo e
        (if (tool-failed? e)
          {:state "waiting"
           :detail (if (string/includes? (string/lower-case (ex-message e)) "not found")
                     "The build job doesn't exist yet"
                     (subs (ex-message e) 0 (min 300 (count (ex-message e)))))}
          (throw e))))))

(defn timed-out?
  [run ci now]
  (let [started
        (get-in run [:result :ci :started-at])]
    (boolean (and started
                  (time/after? now (time/plus-seconds started (* 60 (long (:timeout-minutes ci)))))))))

(defn poll-due?
  [run now]
  (let [checked
        (get-in run [:result :ci :checked-at])]
    (or (nil? checked)
        (not (time/after? (time/plus-seconds checked poll-seconds) now)))))

(defn failure-context
  [run job build log]
  (let [pull-request
        (get-in run [:result :pull-request])

        failed
        (keep #(when-not (contains? #{"SUCCESS" "NOT_EXECUTED" "SKIPPED"} (:status %)) (:name %))
              (:stages build))

        log-text
        (str log)]
    (string/join "\n"
                 [(str "The build of pull request #" (:id pull-request) " (" (:url pull-request) ") failed.")
                  (str "Jenkins job `" job "`, build #" (:number build) ": " (:result build) " — " (:url build))
                  (str "Failed stage(s): " (if (seq failed) (string/join ", " failed) "unknown") ".")
                  (str "The change is on branch `" (:branch pull-request) "` (commit "
                       (subs (str (:commit pull-request)) 0 (min 12 (count (str (:commit pull-request))))) ").")
                  ""
                  "Find the cause of the failure and propose a fix. Use the Jenkins tools for more of the log or other builds."
                  ""
                  "Last lines of the console log:"
                  "```"
                  (subs log-text (max 0 (- (count log-text) 8000)))
                  "```"])))

(defn failure-run
  "attrs of the follow-up run analysing a failed build."
  [system run ci follow-up-workflow]
  (let [{:keys [job build]}
        (get-in run [:result :ci])

        pull-request
        (get-in run [:result :pull-request])

        log
        (try
          (tool system run ci "get_build_log" {:job job :number (str (:number build)) :tail_lines 150})
          (catch clojure.lang.ExceptionInfo e
            (str "(log unavailable: " (ex-message e) ")")))

        context
        (failure-context run job build log)

        title
        (str "Build failure: PR #" (:id pull-request) ", build #" (:number build))]
    {:tenant (:tenant run)
     :team-id (:team-id run)
     :workflow-id (:on-failure ci)
     :inputs (if (= "analysis" (:activity follow-up-workflow))
               {:brief context}
               {:title title :prompt context :branch (:branch pull-request)})
     :trigger {:type "ci_failure" :run (:id run) :build (:number build) :by (:created-by run)}
     :person (model/person run)
     :created-by (:created-by run)}))
