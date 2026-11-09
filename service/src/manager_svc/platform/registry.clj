(ns manager-svc.platform.registry
  "Fetching the onboarding repository and team repositories and loading
   their definitions; the results are recorded in aggregates by the sync
   (manager-svc.platform.api/sync!)."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [manager-svc.platform.catalog :as catalog]
            [manager-svc.git :as git]
            [manager-svc.paths :as paths]
            [manager-svc.platform.sources :as sources]
            [manager-svc.platform.spec :as spec]
            [manager-svc.time :as time])
  (:import [java.io File]
           [java.nio ByteBuffer]
           [java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets]
           [java.nio.file Files LinkOption]))

(def kinds
  [:agents :harnesses :workflows])

(def file-options
  [:guidelines-file :instructions-file :template-file])

(def scm-api-hosts
  {:bitbucket "api.bitbucket.org"
   :github "api.github.com"})

(def skills-max-bytes
  1000000)

(defn valid?
  [definition]
  (and (empty? (:errors definition)) (some? (:spec definition))))

(defn with-validity
  [definition]
  (assoc definition :valid (valid? definition)))

(defn- skill-error
  [message]
  (ex-info message {:type :skill-error :message message}))

(defn- read-text
  [^File file relative-name skill]
  (let [decoder
        (doto (.newDecoder StandardCharsets/UTF_8)
          (.onMalformedInput CodingErrorAction/REPORT)
          (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (try
      (str (.decode decoder (ByteBuffer/wrap (Files/readAllBytes (.toPath file)))))
      (catch CharacterCodingException _
        (throw (skill-error (str "skill " skill ": " relative-name " is not a text file")))))))

(defn collect-skills
  "Text files of each skill directory keyed by <skill name>/<relative path>,
   the skill name being the directory's name. Refuses links, escapes, two
   skills with the same name and more than 1 MB in total."
  [base skills]
  (let [root
        (paths/resolved base)]
    (loop [remaining skills
           files (sorted-map)
           total 0]
      (if-let [skill
               (first remaining)]
        (let [directory
              (paths/resolved (io/file (str root) skill))]
          (when-not (and (.startsWith directory root)
                         (Files/isRegularFile (.resolve directory "SKILL.md") (make-array LinkOption 0)))
            (throw (skill-error (str "skill " skill ": no SKILL.md found in the team definitions"))))
          (when (contains? files (str (.getFileName directory) "/SKILL.md"))
            (throw (skill-error (str "skill " skill ": another skill is also named " (.getFileName directory)))))
          (let [entries
                (with-open [stream (Files/walk directory (make-array java.nio.file.FileVisitOption 0))]
                  (vec (sort-by str (rest (iterator-seq (.iterator stream))))))

                [skill-files skill-total]
                (reduce (fn [[acc sum] path]
                          (let [relative
                                (str (.relativize directory path))]
                            (cond
                              (Files/isSymbolicLink path)
                              (throw (skill-error (str "skill " skill ": links are not allowed (" relative ")")))

                              (not (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
                              [acc sum]

                              :else
                              (let [size
                                    (+ sum (Files/size path))]
                                (when (> size skills-max-bytes)
                                  (throw (skill-error "skills are larger than 1 MB in total")))
                                [(assoc acc (str (.getFileName directory) "/" relative)
                                        (read-text (.toFile path) (str (.getFileName path)) skill))
                                 size]))))
                        [files total]
                        entries)]
            (recur (rest remaining) skill-files skill-total)))
        files))))

(defn secret-host-allowed?
  [platform secret url]
  (or (string/starts-with? (str url) "file:")
      (boolean (some #{(git/host-of url)} (spec/credential-hosts platform secret)))))

(defn team-may-use?
  [platform team credential-id]
  (if-let [declared
           (spec/credential platform credential-id)]
    (spec/in-scope? (:teams declared) (:id team))
    (boolean (some #{credential-id} (:secrets team)))))

(defn harness-credentials
  [platform harness]
  (vec (sort (set (keep #(:credential (spec/mcp-server-of platform %)) (:mcp harness))))))

(defn source-root
  "Directory a source is checked out to (path sources are used in place)."
  [settings key source]
  (if (= :path (:type source))
    (.toFile (paths/resolved (:location source)))
    (io/file (:repos-dir settings) key)))

(defn team-key
  [team-id]
  (str "teams/" team-id))

(defn team-dir
  [settings team]
  (if (nil? team)
    nil
    (io/file (source-root settings (team-key (:id team)) (:repository team))
             (str (get-in team [:repository :path])))))

(defn read-team-file
  "Text of relative inside the team's definitions directory, or nil."
  [settings team relative]
  (when-let [base
             (team-dir settings team)]
    (when (paths/file-inside? base relative)
      (slurp (str (paths/resolved (io/file (str (paths/resolved base)) (str relative))))))))

(defn- definition
  [kind id source spec errors]
  {:kind kind
   :id id
   :source source
   :spec spec
   :errors (vec errors)})

(defn- load-file-definition
  [kind ^File file source]
  (let [id
        (string/replace (.getName file) #"\.json$" "")]
    (try
      (let [{:keys [value errors]}
            (spec/validate (spec/definition-kinds kind) (spec/read-json (slurp file)))]
        (definition kind id source value errors))
      (catch com.fasterxml.jackson.core.JacksonException error
        (definition kind id source nil [(str "invalid JSON: " (first (string/split-lines (ex-message error))))])))))

(defn- read-kind
  [base path kind]
  (let [directory
        (io/file base (name kind))]
    (if (.isDirectory directory)
      (let [files
            (sort-by #(.getName ^File %)
                     (filter #(string/ends-with? (.getName ^File %) ".json") (.listFiles directory)))

            definitions
            (map (fn [^File file]
                   (load-file-definition kind file (str path "/" (name kind) "/" (.getName file))))
                 files)]
        (into (array-map) (map (juxt :id identity)) definitions))
      {})))

(defn- check-harness
  [platform team base harness]
  (let [image-errors
        (when (and (:image harness) (not (some #{(:image harness)} (:allowed-images platform))))
          [(str "image " (:image harness) " is not in the platform's allowed_images")])

        connection
        (spec/llm-connection-of platform (get-in harness [:llm :connection]))

        connection-errors
        (if (nil? connection)
          [(str "llm.connection " (get-in harness [:llm :connection]) " does not exist")]
          (let [unknown
                (vec (remove (set (:models connection)) (get-in harness [:llm :models])))]
            (concat
             (when-not (spec/in-scope? (:teams connection) (:id team))
               [(str "team " (:id team) " may not use llm connection " (:id connection))])
             (when (seq unknown)
               [(str "models " (pr-str unknown) " are not offered by connection " (:id connection))]))))

        skill-errors
        (try
          (collect-skills base (:skills harness))
          nil
          (catch clojure.lang.ExceptionInfo error
            [(ex-message error)]))

        server-errors
        (for [server-id (:mcp harness)
              :let [server (spec/mcp-server-of platform server-id)]
              :when (or (nil? server)
                        (not (spec/in-scope? (:teams server) (:id team)))
                        (and (:credential server) (not (team-may-use? platform team (:credential server)))))]
          (cond
            (nil? server)
            (str "MCP server " server-id " is not configured in the onboarding repository")

            (not (spec/in-scope? (:teams server) (:id team)))
            (str "team " (:id team) " may not use MCP server " server-id)

            :else
            (str "MCP server " server-id " needs credential " (:credential server)
                 ", which team " (:id team) " may not use")))]
    (vec (concat image-errors connection-errors skill-errors server-errors))))

(defn- check-ci
  [platform team loaded options delivery]
  (when-let [ci
             (:ci options)]
    (let [server
          (spec/mcp-server-of platform (:mcp-server ci))

          needed
          (if (:on-failure ci) #{"get_build" "get_build_log"} #{"get_build"})

          follow-up
          (get-in loaded [:workflows (:on-failure ci)])

          follow-up-spec
          (when (and follow-up (valid? follow-up)) (:spec follow-up))]
      (concat
       (when-not delivery
         ["options.ci needs options.pull_request: only pushed pull requests have builds"])
       (cond
         (nil? server)
         [(str "options.ci: MCP server " (:mcp-server ci) " is not configured in the onboarding repository")]

         (not (spec/in-scope? (:teams server) (:id team)))
         [(str "options.ci: team " (:id team) " may not use MCP server " (:mcp-server ci))]

         (and (some? (:tools server)) (not-every? (set (:tools server)) needed))
         [(str "options.ci: MCP server " (:mcp-server ci) " must allow the tools " (pr-str (vec (sort needed))))])
       (when (:on-failure ci)
         (cond
           (nil? follow-up-spec)
           [(str "options.ci.on_failure: workflow " (:on-failure ci) " does not exist or is invalid")]

           (or (not (#{"analysis" "session"} (:activity follow-up-spec)))
               (not= :manual (get-in follow-up-spec [:trigger :type] :manual)))
           [(str "options.ci.on_failure: " (:on-failure ci) " must be a manual analysis or workspace workflow")]))))))

(defn- used-credentials
  [options workflow]
  (set (remove nil? [(or (get-in options [:repository :credentials])
                         (get-in options [:scm :credentials]))
                     (when (= :pull-request (get-in workflow [:trigger :type]))
                       (get-in workflow [:trigger :credentials]))])))

(defn- check-repository
  [settings platform repository]
  (when repository
    (concat
     (try
       (git/check-url (:url repository) (:local-repo-roots settings))
       nil
       (catch clojure.lang.ExceptionInfo error
         [(str "options.repository.url: " (ex-message error))]))
     (when (and (:credentials repository)
                (not (secret-host-allowed? platform (:credentials repository) (:url repository))))
       [(str "options.repository: secret " (:credentials repository) " may not be sent to "
             (git/host-of (:url repository)) " (see secret_hosts in the onboarding repository)")]))))

(defn- check-delivery
  [platform repository delivery]
  (when delivery
    (let [api-host
          (scm-api-hosts (:provider delivery))]
      (cond
        (not (:credentials repository))
        ["options.pull_request needs options.repository.credentials to push and open the pull request"]

        (not (some #{api-host} (spec/credential-hosts platform (:credentials repository))))
        [(str "options.pull_request: credential " (:credentials repository) " may not be sent to " api-host)]))))

(defn- check-automation
  [platform loaded workflow used]
  (when (not= :manual (get-in workflow [:trigger :type]))
    (let [harness
          (get-in loaded [:harnesses (:harness workflow)])

          needed
          (into used (when (and harness (valid? harness))
                       (harness-credentials platform (:spec harness))))]
      (for [secret (sort needed)
            :let [declared (spec/credential platform secret)]
            :when (and declared (= :user (:provided-by declared)))]
        (str "runs automatically, but credential " secret
             " only has personal values; use provided_by platform_or_user")))))

(defn- check-review-source
  [workflow options]
  (when (= "review" (:activity workflow))
    (let [pull-requests?
          (or (some? (:scm options))
              (= :pull-request (get-in workflow [:trigger :type])))

          branch?
          (some? (:repository options))]
      (cond
        (= pull-requests? branch?)
        ["set exactly one of options.scm (or a pull_request trigger) to review pull requests, or options.repository to review a branch"]

        (and branch? (= :auto (:post-mode options)))
        ["options.post_mode: branch reviews never post; use off or dry-run"]))))

(defn- check-workflow
  "Errors of a workflow and its options decoded against its activity."
  [settings platform team base loaded workflow]
  (if-not (catalog/activity (:activity workflow))
    {:errors [(str "unknown activity " (:activity workflow))]}
    (let [reference-errors
          (for [[kind ref] [[:agents (:agent workflow)] [:harnesses (:harness workflow)]]
                :let [label (subs (name kind) 0 (dec (count (name kind))))
                      loaded-ref (get-in loaded [kind ref])]
                :when (or (nil? loaded-ref) (not (valid? loaded-ref)))]
            (if (nil? loaded-ref)
              (str label " " ref " does not exist")
              (str label " " ref " is invalid")))

          {options :value option-errors :errors}
          (catalog/validate-options (:activity workflow) (:options workflow))]
      (if (seq option-errors)
        {:errors (vec (concat reference-errors (map #(str "options." %) option-errors)))}
        (let [file-errors
              (for [option file-options
                    :let [relative (get options option)]
                    :when (and relative (not (paths/file-inside? base relative)))]
                (str "options." (spec/snake option) ": " relative " not found in the team definitions"))

              repository
              (:repository options)

              delivery
              (:pull-request options)

              used
              (used-credentials options workflow)

              grant-errors
              (for [secret (sort used)
                    :when (not (team-may-use? platform team secret))]
                (str "credential " secret " is not granted to team " (:id team) " in the onboarding repository"))

              trigger-errors
              (when (and (= :pull-request (get-in workflow [:trigger :type])) (not= "review" (:activity workflow)))
                ["pull_request triggers can only start the review activity"])]
          {:options options
           :errors (vec (concat reference-errors
                                file-errors
                                (check-repository settings platform repository)
                                (check-delivery platform repository delivery)
                                (check-ci platform team loaded options delivery)
                                grant-errors
                                (check-automation platform loaded workflow used)
                                trigger-errors
                                (check-review-source workflow options)))})))))

(defn- check-agent
  [base agent]
  (when (and (:prompt-file agent) (not (paths/file-inside? base (:prompt-file agent))))
    [(str "prompt_file " (:prompt-file agent) " not found")]))

(defn- update-valid
  [loaded kind f]
  (update loaded kind
          (fn [group]
            (into (array-map)
                  (map (fn [[id item]]
                         [id (if (valid? item) (f item) item)]))
                  group))))

(defn load-definitions
  "Definitions of a team's checked-out repository, validated against the
   platform spec: [{:kind :id :source :spec :errors :valid}]."
  [settings platform team]
  (let [base
        (team-dir settings team)

        path
        (get-in team [:repository :path])

        raw
        (into {} (map (fn [kind] [kind (read-kind base path kind)])) kinds)

        with-agents
        (update-valid raw :agents
                      (fn [item] (update item :errors into (check-agent base (:spec item)))))

        with-harnesses
        (update-valid with-agents :harnesses
                      (fn [item] (update item :errors into (check-harness platform team base (:spec item)))))

        with-workflows
        (update-valid with-harnesses :workflows
                      (fn [item]
                        (let [{:keys [options errors]}
                              (check-workflow settings platform team base with-harnesses (:spec item))]
                          (cond-> (update item :errors into errors)
                            options (assoc-in [:spec :options] options)))))]
    (vec (for [kind kinds
               item (vals (get with-workflows kind))]
           (with-validity item)))))

(defn- token
  [{:keys [secret]} platform secret-name url]
  (when secret-name
    (when-not (secret-host-allowed? platform secret-name url)
      (throw (sources/source-error (str "secret " secret-name " may not be sent to " (git/host-of url)
                                        " (see secret_hosts)"))))
    (or (secret secret-name)
        (throw (sources/source-error (str "Platform secret " secret-name " is not configured"))))))

(defn fetch-source
  "Fetches a source into the repos directory under key (path sources are
   only fingerprinted) with token; returns its commit id."
  [settings key source token]
  (let [roots
        (:local-repo-roots settings)

        target
        (io/file (:repos-dir settings) key)]
    (case (:type source)
      :path (sources/fingerprint (sources/local-directory (:location source) roots))
      :url (sources/fetch-archive (:url source) target token)
      (git/checkout {:url (:url source)
                     :branch (:branch source)
                     :target target
                     :username (:username source)
                     :token token
                     :local-roots roots}))))

(defn fetch!
  "Fetches a team source; its secret must be allowed for the source's host."
  [{:keys [settings] :as env} key source platform]
  (fetch-source settings key source (token env platform (:credentials source) (:url source))))

(defn onboarding-source
  "Source of the onboarding repository from MANAGER_ONBOARDING_*. A plain
   directory (absolute path, or a file:// URL that is not a git repository)
   is a path source."
  [{:keys [onboarding-url onboarding-type onboarding-branch onboarding-credentials onboarding-username]}]
  (let [location
        (str onboarding-url)

        file-directory?
        (and (string/starts-with? location "file://")
             (not (.isDirectory (io/file (git/file-url->path location) ".git")))
             (.isDirectory (io/file (git/file-url->path location))))

        kind
        (cond
          (not (string/blank? onboarding-type)) (keyword onboarding-type)
          (string/starts-with? location "/") :path
          file-directory? :path
          (some #(string/ends-with? location %) [".tar.gz" ".tgz" ".zip"]) :url
          :else :git)]
    (case kind
      :path {:type :path
             :location (if (string/starts-with? location "file://") (git/file-url->path location) location)
             :path "."}
      :url {:type :url :url location :credentials onboarding-credentials :path "."}
      {:type :git
       :url location
       :branch onboarding-branch
       :credentials onboarding-credentials
       :username onboarding-username
       :path "."})))

(defn configured?
  [settings]
  (not (string/blank? (:onboarding-url settings))))

(defn- failure-message
  [error]
  (or (:message (ex-data error)) (ex-message error) (str error)))

(defn- fetch-onboarding
  "The onboarding token has no host check: no platform spec declares its
   hosts before the onboarding repository is read."
  [{:keys [settings secret]}]
  (let [source
        (onboarding-source settings)

        token
        (when-let [name (:onboarding-credentials settings)]
          (secret name))

        commit
        (fetch-source settings "onboarding" source token)

        file
        (io/file (source-root settings "onboarding" source) (:onboarding-file settings))

        {:keys [value errors]}
        (spec/parse-platform (slurp file))]
    (if (seq errors)
      {:error (string/join "; " errors)}
      {:commit commit
       :spec value})))

(defn sync-team
  "Fetches a team repository and loads its definitions."
  [{:keys [settings] :as env} platform team]
  (let [{:keys [commit definitions error]}
        (try
          {:commit (fetch! env (team-key (:id team)) (:repository team) platform)
           :definitions (load-definitions settings platform team)}
          (catch Exception error
            {:error (failure-message error)}))]
    {:team-id (:id team)
     :spec team
     :commit commit
     :synced-at (time/now)
     :status (if error :error :ok)
     :error error
     :members (vec (concat (for [username (:members team)
                                 :when (not (some #{username} (:admins team)))]
                             {:username username :role :member})
                           (for [username (distinct (:admins team))]
                             {:username username :role :admin})))
     :definitions (when-not error definitions)}))

(defn run-sync
  "Fetches the onboarding repository and every team repository.
   env: {:settings :secret (fn [name] value-or-nil)}. Returns
   {:synced-at :commit :spec :teams [...]} or {:synced-at :error}."
  [{:keys [settings] :as env}]
  (if-not (configured? settings)
    {:error "No onboarding repository configured (MANAGER_ONBOARDING_URL)"
     :unconfigured true}
    (let [synced-at
          (time/now)

          onboarding
          (try
            (fetch-onboarding env)
            (catch Exception error
              {:error (failure-message error)}))]
      (if (:error onboarding)
        (assoc onboarding :synced-at synced-at)
        (assoc onboarding
               :synced-at synced-at
               :teams (mapv #(sync-team env (:spec onboarding) %) (get-in onboarding [:spec :teams])))))))

(defn summary
  [{:keys [error commit teams]}]
  (if error
    {:error error}
    {:commit commit
     :teams (into {}
                  (map (fn [{:keys [team-id commit error definitions]}]
                         [team-id {:commit commit
                                   :error error
                                   :definitions (count definitions)
                                   :invalid (vec (for [d definitions
                                                       :when (not (:valid d))]
                                                   (str (name (:kind d)) "/" (:id d))))}]))
                  teams)}))
