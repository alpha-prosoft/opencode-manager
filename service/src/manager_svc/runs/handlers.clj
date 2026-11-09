(ns manager-svc.runs.handlers
  "What each activity does around the agent: prepare the workspace, write the
   first prompt, and turn the submitted result into the run's result (posting
   review comments, pushing the change and opening a pull request). Mirrors
   manager/app/platform/handlers.py.

   A handler gets a run context:

     {:system :run :team :workflow :options :inputs :workspace :session-url
      :author [name email] :record! (fn [cmd-id attrs])}

   finalize! records each external change (comment posted, pull request
   opened) with :record! as it happens, and skips those already recorded on
   the run, so finalizing again after a restart does not repeat them."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.http :as http]
            [manager-svc.platform.api :as platform]
            [manager-svc.runs.git :as git]
            [manager-svc.runs.model :as model]
            [manager-svc.runs.review :as review]
            [manager-svc.runs.scm :as scm]
            [manager-svc.time :as time]))

(def context-dir ".platform")

(def repo-dir "repo")

(def max-patch-bytes 1000000)

(def generic-review-guidelines
  "# Review guidelines

Review the change for correctness, security, and consistency with the conventions already used in the
repository. Report only issues you verified in the diff or the repository. Phrase anything you cannot verify
as a question.
")

(defn handler-error
  "A problem with the run's inputs or options that retrying won't fix."
  [message]
  (ex-info message {:permanent true}))

(defn context-file
  [ctx file-name]
  (io/file (:workspace ctx) context-dir file-name))

(defn write-context!
  [ctx file-name content]
  (let [file (context-file ctx file-name)]
    (io/make-parents file)
    (spit file content)))

(defn read-context
  [ctx file-name]
  (slurp (context-file ctx file-name)))

(defn repo
  [ctx]
  (str (io/file (:workspace ctx) repo-dir)))

(defn credential!
  "{:secret :username} of a credential as the run's person (or the platform) sees it."
  [{:keys [system run]} credential-id]
  (let [value
        (:value (credentials/resolve-credential system (model/credential-scope run) credential-id))]
    (if (string/blank? (:secret value))
      (throw (handler-error (str "Credential " credential-id " is not set up")))
      value)))

(defn team-file
  [{:keys [system run]} relative default]
  (if relative
    (platform/read-team-file system (:tenant run) (:team-id run) relative)
    default))

(defn- source-credential
  "{:secret :username} for a git source; the credential's own username (a
   Gerrit account) wins over the source's."
  [{:keys [system run] :as ctx} {:keys [url credentials username]}]
  (if credentials
    (do
      (when-not (platform/secret-host-allowed? system (:tenant run) credentials url)
        (throw (handler-error (str "Secret " credentials " may not be sent to " (git/host-of url)))))
      (let [credential
            (credential! ctx credentials)]
        (assoc credential :username (or (:username credential) username "x-token-auth"))))
    {:username (or username "x-token-auth")}))

(defn- git-access
  [{:keys [system]}]
  {:local-roots (get-in system [:settings :local-repo-roots])
   :http-hosts (get-in system [:settings :http-git-hosts])})

(defn checkout!
  [ctx {:keys [url] :as source} branch]
  (let [{:keys [secret username]}
        (source-credential ctx source)]
    (git/checkout! (merge (git-access ctx)
                          {:url url
                           :branch (or branch (:branch source) "main")
                           :target (repo ctx)
                           :username username
                           :token secret
                           :depth 50}))))

(defn branch-diff!
  "The diff of the checked-out branch against base (base...branch)."
  [ctx {:keys [url] :as source} base]
  (let [{:keys [secret username]}
        (source-credential ctx source)]
    (git/branch-diff {:target (repo ctx)
                      :url url
                      :base base
                      :username username
                      :token secret
                      :depth 50})))

(defn- activity-of
  [ctx]
  (get-in ctx [:run :activity]))

(defmulti prepare! activity-of)

(defmulti prompt
  "The first message to the agent, or nil."
  activity-of)

(defmulti finalize!
  "The run's result from the validated submission."
  (fn [ctx _submission] (activity-of ctx)))

(defmethod prepare! :default [_ctx] nil)

(defmethod prompt :default [_ctx] nil)

(defmethod finalize! :default [_ctx _submission] {})

(defmethod prepare! "session"
  [{:keys [options inputs] :as ctx}]
  (when (:repository options)
    (checkout! ctx (:repository options) (:branch inputs))))

(defmethod prompt "session"
  [{:keys [inputs]}]
  (not-empty (:prompt inputs)))

(defn- review-scm
  [{:keys [system options] :as ctx}]
  (let [source
        (or (:scm options)
            (throw (handler-error "The review activity needs options.scm (or a pull_request trigger)")))]
    [source (scm/provider system source (credential! ctx (:credentials source)))]))

(defn- branch-review?
  [{:keys [options]}]
  (some? (:repository options)))

(defn- review-diff-file
  [ctx]
  (if (branch-review? ctx) "branch.diff" "pr.diff"))

(defn- prepare-pull-request-review!
  [{:keys [inputs] :as ctx}]
  (let [[source provider]
        (review-scm ctx)

        pr
        ((:pull-request provider) (:repository source) (:pull-request inputs))]
    (when-not (= (git/host-of (:clone-url pr)) (:git-host provider))
      (throw (handler-error (str "Refusing to clone " (:clone-url pr) ": not hosted on " (:git-host provider)))))
    (git/checkout! (merge (git-access ctx)
                          {:url (:clone-url pr)
                           :branch (:source-branch pr)
                           :target (repo ctx)
                           :username (:git-username provider)
                           :token (:token provider)
                           :depth 50}))
    (write-context! ctx "pr.diff" ((:diff provider) (:repository source) (:id pr)))
    (write-context! ctx "pull-request.md"
                    (str "# PR #" (:id pr) ": " (:title pr) "\n\n"
                         "- Repository: " (:repository source) " (" (name (:provider source)) ")\n"
                         "- Author: " (:author pr) "\n"
                         "- Branch: " (:source-branch pr) " → " (:destination-branch pr) "\n"
                         "- Commit: " (:source-commit pr) "\n"
                         "- URL: " (:url pr) "\n"))))

(defn- prepare-branch-review!
  [{:keys [options inputs] :as ctx}]
  (let [source
        (:repository options)

        {:keys [branch base]}
        inputs

        commit
        (checkout! ctx source branch)

        diff
        (branch-diff! ctx source base)]
    (when (string/blank? diff)
      (throw (handler-error (str "Branch " branch " has no changes against " base))))
    (write-context! ctx "branch.diff" diff)
    (write-context! ctx "branch.md"
                    (str "# Branch " branch "\n\n"
                         "- Repository: " (:url source) "\n"
                         "- Compared with: " base "\n"
                         "- Commit: " commit "\n"))))

(defmethod prepare! "review"
  [{:keys [options] :as ctx}]
  (if (branch-review? ctx)
    (prepare-branch-review! ctx)
    (prepare-pull-request-review! ctx))
  (write-context! ctx "guidelines.md" (team-file ctx (:guidelines-file options) generic-review-guidelines)))

(def ^:private review-instructions
  (str "- Review guidelines: `" context-dir "/guidelines.md`. Read them first.\n\n"
       "Read every hunk of the diff, including removed lines. Inspect the repository for context. "
       "Do not modify any files.\n\n"
       "Submit the result by calling the `submit_review` tool exactly once (verdict, summary, findings with "
       "path and NEW-file line inside the diff, checked items). If it reports a validation error, fix the "
       "arguments and call it again. Do not post anything anywhere yourself."))

(defmethod prompt "review"
  [{:keys [inputs options] :as ctx}]
  (if (branch-review? ctx)
    (str "Review branch `" (:branch inputs) "` of " (get-in options [:repository :url])
         " against `" (:base inputs) "`.\n\n"
         "- The branch is checked out in `" repo-dir "/`.\n"
         "- The diff (" (:base inputs) "..." (:branch inputs) ") is in `" context-dir "/branch.diff`, "
         "metadata in `" context-dir "/branch.md`.\n"
         review-instructions)
    (str "Review pull request #" (:pull-request inputs) " of " (get-in options [:scm :repository]) ".\n\n"
         "- The PR source branch is checked out in `" repo-dir "/`.\n"
         "- The diff is in `" context-dir "/pr.diff`, metadata in `" context-dir "/pull-request.md`.\n"
         review-instructions)))

(defmethod finalize! "review"
  [{:keys [run options inputs session-url record!] :as ctx} submission]
  (let [mode
        (name (or (:post-mode options) "dry-run"))

        plan
        (review/plan-post submission
                          (read-context ctx (review-diff-file ctx))
                          mode
                          (or (:max-comments options) 30)
                          session-url)

        _
        (write-context! ctx "review.json" (http/write-json submission))

        _
        (write-context! ctx "post-plan.json" (http/write-json plan))

        already
        (set (map :key (:posted-comments run)))

        posted
        (if (and (= "auto" mode) (not (branch-review? ctx)))
          (let [[source provider]
                (review-scm ctx)]
            (scm/post-review! provider
                              (:repository source)
                              (:pull-request inputs)
                              (assoc plan :verdict (:verdict submission))
                              already
                              (fn [key comment-id]
                                (record! :record-review-comment {:key key :comment-id comment-id}))))
          0)]
    (cond-> {:review submission
             :plan {:mode mode
                    :comments (count (:comments plan))
                    :inline (:inline-count plan)
                    :folded (:folded-count plan)}
             :posted (+ posted (count already))}
      (branch-review? ctx) (assoc :branch {:name (:branch inputs) :base (:base inputs)}))))

(defn short-title
  ([text]
   (short-title text 100))
  ([text limit]
   (let [line
         (if (string/blank? text) "" (first (string/split-lines (string/trim text))))]
     (if (<= (count line) limit)
       line
       (let [cut (subs line 0 limit)
             at-word (subs cut 0 (max 0 (or (string/last-index-of cut " ") (count cut))))]
         (str (string/replace at-word #"[ ,;:]+$" "") "…"))))))

(defn- delivery-provider
  "The provider pull requests are delivered to, and the credential pushes
   go with."
  [{:keys [system options] :as ctx}]
  (let [credential
        (source-credential ctx (:repository options))]
    [(scm/provider system (:pull-request options) (when (:secret credential) credential)) credential]))

(defmethod prepare! "implement"
  [{:keys [options inputs] :as ctx}]
  (let [branch
        (if (:pull-request inputs)
          (do
            (when-not (:pull-request options)
              (throw (handler-error "This workflow does not deliver pull requests, so it can't continue on one")))
            (let [[provider]
                  (delivery-provider ctx)

                  pr
                  ((:pull-request provider) (get-in options [:pull-request :repository]) (:pull-request inputs))]
              (write-context! ctx "pull_request.md"
                              (str "# Pull request #" (:id pr) ": " (:title pr) "\n\n"
                                   "Branch `" (:source-branch pr) "` into `" (:destination-branch pr) "`.\n"))
              (:source-branch pr)))
          (:branch inputs))]
    (checkout! ctx (:repository options) branch)
    (write-context! ctx "task.md" (:task inputs))
    (when (:guidelines-file options)
      (write-context! ctx "guidelines.md" (team-file ctx (:guidelines-file options) "")))))

(defmethod prompt "implement"
  [{:keys [options inputs]}]
  (str "Implement the task in `" context-dir "/task.md` in the repository checked out at `" repo-dir "/`."
       (when (:pull-request inputs)
         (str " You are continuing pull request #" (:pull-request inputs)
              " (see `" context-dir "/pull_request.md`); its branch is checked out."))
       (when (:guidelines-file options)
         (str " Follow `" context-dir "/guidelines.md`."))
       "\n\n"
       "Make focused changes, run the project's tests or linters if they exist, and do not commit or push. "
       "If you need a virtual environment, create it in your home directory. "
       "When you are done, call the `submit_implementation` tool exactly once with a short title, a summary, "
       "the list of changes, the commands you ran and their outcome, and any notes. Set `verified` to true only "
       "if the tests or checks actually ran and passed."))

(defn description
  [{:keys [run session-url]} {:keys [summary changes tests verified notes]}]
  (string/join "\n"
               (concat [summary ""]
                       (map #(str "- " %) changes)
                       (when (seq tests)
                         ["" (str "**How the agent verified it:** " (string/join "; " tests))])
                       (when-not verified
                         ["" "**Not verified:** the agent could not run the checks successfully, so this is a draft."])
                       (when-not (string/blank? notes)
                         ["" (str "**Notes:** " notes)])
                       [""
                        (str "_Opened by the agent platform for " (:created-by run) "._"
                             (when-not (string/blank? session-url) (str " [Session](" session-url ")")))])))

(defn- deliver!
  [{:keys [run options inputs workflow author] :as ctx} submission]
  (let [delivery
        (:pull-request options)

        source
        (:repository options)

        [provider credential]
        (delivery-provider ctx)

        title
        (or (not-empty (short-title (or (not-empty (:title submission)) (:summary submission))))
            (:name workflow))

        default-message
        (str title "\n\nMade by an agent for " (:created-by run) " (run " (:id run) ").")

        push!
        (fn [{:keys [ref trailer message amend]}]
          (git/commit-and-push! {:target (repo ctx)
                                 :url (:url source)
                                 :ref ref
                                 :trailer trailer
                                 :amend amend
                                 :username (:username credential)
                                 :token (:secret credential)
                                 :message (or message default-message)
                                 :author author}))

        delivered
        (fn [pr extra]
          (merge {:id (:id pr) :url (:url pr) :branch (:source-branch pr) :target (:destination-branch pr)
                  :commit (:commit pr) :pushed-at (time/now) :provider (some-> (:provider delivery) name)}
                 extra))]
    (if (:pull-request inputs)
      (let [pr
            ((:pull-request provider) (:repository delivery) (:pull-request inputs))]
        (delivered (scm/update-change! provider (:repository delivery) pr push!) {:updated true}))
      (let [target
            (or (:target-branch delivery) (:branch inputs) (:branch source) "main")

            draft
            (not (:verified submission))]
        (delivered (scm/open-change! provider
                                     (:repository delivery)
                                     {:key (str "run:" (:id run))
                                      :branch (str "agent/" (:id run))
                                      :target target
                                      :title title
                                      :description (description ctx submission)
                                      :draft draft}
                                     push!)
                   {:target target :draft draft})))))

(defmethod finalize! "implement"
  [{:keys [run options record!] :as ctx} submission]
  (let [patch
        (git/diff (repo ctx))

        files
        (vec (sort (set (keep #(when (string/starts-with? % "+++ b/") (subs % 6)) (string/split-lines patch)))))

        bytes
        (count (.getBytes ^String patch "UTF-8"))

        _
        (write-context! ctx "change.patch" patch)

        output
        {:implementation submission
         :patch (if (> bytes max-patch-bytes) (subs patch 0 (min (count patch) max-patch-bytes)) patch)
         :patch-truncated (> bytes max-patch-bytes)
         :files files}]
    (cond
      (get-in run [:result :pull-request])
      (assoc output :pull-request (get-in run [:result :pull-request]))

      (and (:pull-request options) (not (string/blank? patch)))
      (let [pull-request
            (deliver! ctx submission)]
        (record! :record-run-outcome {:pull-request pull-request})
        (assoc output :pull-request pull-request))

      :else
      output)))

(defmethod prepare! "analysis"
  [{:keys [options inputs] :as ctx}]
  (when (:repository options)
    (checkout! ctx (:repository options) nil))
  (write-context! ctx "brief.md" (:brief inputs))
  (when (:template-file options)
    (write-context! ctx "template.md" (team-file ctx (:template-file options) ""))))

(defmethod prompt "analysis"
  [{:keys [options]}]
  (str "Write a business analysis for the brief in `" context-dir "/brief.md`."
       (when (:template-file options) (str " Use the structure in `" context-dir "/template.md`."))
       (when (:repository options) (str " The repository at `" repo-dir "/` is available for context."))
       "\n\n"
       "Cover goals, stakeholders, functional and non-functional requirements, assumptions, risks and open "
       "questions. Do not modify any repository files. When the document is complete, call the "
       "`submit_document` tool exactly once with a title and the full Markdown."))

(defmethod finalize! "analysis"
  [ctx submission]
  (write-context! ctx "analysis.md" (:markdown submission))
  {:document submission})
