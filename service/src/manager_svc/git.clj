(ns manager-svc.git
  "The git CLI on the platform host: checkouts of onboarding, team and run
   repositories. git runs with a clean environment, no credential helpers
   and a timeout; tokens never appear in its arguments or error messages
   (https sends them as a header, ssh keys go to an owner-only temp file).

   Failures throw ex-info with {:type :git-error :git true :message}."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [manager-svc.files :as files]
            [manager-svc.paths :as paths])
  (:import [java.io File]
           [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path]
           [java.util Base64]
           [java.util.concurrent TimeUnit]))

(def timeout-seconds 300)

(def scp-like
  #"^(?<user>[A-Za-z0-9._-]+)@(?<host>[A-Za-z0-9.-]+):(?<path>[^/].*)$")

(def known-hosts
  (or (System/getenv "PLATFORM_KNOWN_HOSTS") "/data/known_hosts"))

(defn git-error
  [message]
  (ex-info message {:type :git-error :git true :message message}))

(defn ssh?
  [url]
  (boolean (or (string/starts-with? url "ssh://") (re-matches scp-like url))))

(defn host-of
  [url]
  (if-let [[_ _ host]
           (re-matches scp-like url)]
    host
    (or (try (.getHost (URI. url)) (catch Exception _ nil)) "")))

(defn file-url->path
  [url]
  (.getPath (URI. url)))

(defn roots-label
  [local-roots]
  (if (seq local-roots)
    (string/join ", " local-roots)
    "an allowed root (none configured)"))

(defn check-url
  "Throws a git error unless url is ssh, https, file:// under a local root,
   or http:// to one of http-hosts (local CI systems only)."
  ([url local-roots]
   (check-url url local-roots []))
  ([url local-roots http-hosts]
   (cond
     (ssh? url)
     nil

     (string/starts-with? url "file://")
     (when-not (paths/under-any? (file-url->path url) local-roots)
       (throw (git-error (str "file:// repositories must live under " (roots-label local-roots)))))

     (string/starts-with? url "http://")
     (when-not (some #{(host-of url)} http-hosts)
       (throw (git-error (str "http:// is only allowed for the hosts in MANAGER_HTTP_GIT_HOSTS, not " (host-of url)))))

     (not (string/starts-with? url "https://"))
     (throw (git-error "only https://, ssh (git@host:repo) and file:// repositories are supported")))))

(def ^:private git-config
  (delay
    (let [file
          (File/createTempFile "platform" ".gitconfig")]
      (.deleteOnExit file)
      (spit file "[safe]\n\tdirectory = *\n")
      (.getPath file))))

(defn- base-env
  []
  {"GIT_TERMINAL_PROMPT" "0"
   "PATH" "/usr/bin:/bin"
   "HOME" (System/getProperty "java.io.tmpdir")
   "GIT_CONFIG_NOSYSTEM" "1"
   "GIT_CONFIG_GLOBAL" @git-config})

(defn failure-message
  "The first fatal or error line of git's stderr, with the token masked."
  [stderr token args]
  (let [masked
        (if (string/blank? token) stderr (string/replace stderr token "***"))

        lines
        (string/split-lines (string/trim masked))

        fatal
        (filter #(or (string/starts-with? % "fatal:") (string/starts-with? % "error:")) lines)]
    (or (first fatal)
        (first (remove string/blank? lines))
        (str "git " (first args) " failed"))))

(defn- process-builder
  ^ProcessBuilder [command dir env]
  (let [builder
        (ProcessBuilder. ^java.util.List command)

        environment
        (.environment builder)]
    (.clear environment)
    (doseq [[k v] (merge (base-env) env)]
      (.put environment k v))
    (when dir
      (.directory builder (io/file dir)))
    builder))

(defn run
  "Runs git and returns its trimmed stdout; throws a git error with the first
   fatal line (token masked) on failure or timeout."
  [args & {:keys [token dir env]}]
  (let [command
        (into ["git" "-c" "credential.helper=" "-c" "protocol.file.allow=always"] args)

        process
        (.start (process-builder command dir env))

        stdout
        (future (slurp (.getInputStream process)))

        stderr
        (future (slurp (.getErrorStream process)))]
    (when-not (.waitFor process timeout-seconds TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (git-error (str "git " (first args) " timed out"))))
    (if (zero? (.exitValue process))
      (string/trim @stdout)
      (throw (git-error (failure-message @stderr token args))))))

(defn ssh-command
  [^Path key-file]
  (str "ssh -i " key-file " -o IdentitiesOnly=yes -o BatchMode=yes"
       " -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=" known-hosts))

(defn with-credentials
  "Calls (f extra-git-args env) with the token applied for url."
  [url username token f]
  (cond
    (string/blank? token)
    (f [] {})

    (ssh? url)
    (let [key-file
          (files/temp-private! "key-" (str (string/trim token) "\n"))]
      (try
        (f [] {"GIT_SSH_COMMAND" (ssh-command key-file)})
        (finally
          (Files/deleteIfExists key-file))))

    :else
    (let [encoded
          (.encodeToString (Base64/getEncoder)
                           (.getBytes (str (or username "x-token-auth") ":" token) StandardCharsets/UTF_8))]
      (f ["-c" (str "http.extraHeader=Authorization: Basic " encoded)] {}))))

(defn- git-dir?
  [target]
  (Files/isDirectory (.toPath (io/file target ".git")) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn checkout
  "Clones or fast-forwards branch of url into target; returns the commit id.
   branch may also be a ref (refs/changes/01/1/1), which is fetched into a
   new repository. Anything at target that is not a checkout is removed
   (links are not followed)."
  [{:keys [url branch target username token local-roots http-hosts depth] :or {depth 1}}]
  (check-url url local-roots http-hosts)
  (let [target-file
        (io/file target)]
    (with-credentials url username token
      (fn [auth env]
        (let [fetch!
              (fn []
                (run (concat auth ["fetch" "--quiet" "--depth" (str depth) "origin" branch])
                     :token token :dir target-file :env env)
                (run ["reset" "--quiet" "--hard" "FETCH_HEAD"] :token token :dir target-file))]
          (when (and (Files/exists (.toPath target-file) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                     (not (git-dir? target-file)))
            (paths/delete-tree! target-file))
          (cond
            (git-dir? target-file)
            (do
              (run ["remote" "set-url" "origin" url] :token token :dir target-file)
              (fetch!))

            (string/starts-with? branch "refs/")
            (do
              (io/make-parents target-file)
              (run ["init" "--quiet" (.getPath target-file)] :token token)
              (run ["remote" "add" "origin" url] :token token :dir target-file)
              (fetch!))

            :else
            (do
              (io/make-parents target-file)
              (run (concat auth ["clone" "--quiet" "--depth" (str depth) "--single-branch" "--branch" branch
                                 url (.getPath target-file)])
                   :token token :env env))))))
    (run ["rev-parse" "HEAD"] :token token :dir target-file)))
