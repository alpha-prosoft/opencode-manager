(ns manager-svc.runs.git
  "git for runs, on manager-svc.git: check out the repository a run works on,
   read the agent's change and push it. Every git failure is permanent for
   the run step."
  (:require [clojure.string :as string]
            [manager-svc.git :as git])
  (:import [clojure.lang ExceptionInfo]))

(defn host-of
  [url]
  (git/host-of url))

(defn- permanent
  [f]
  (try
    (f)
    (catch ExceptionInfo e
      (if (:git (ex-data e))
        (throw (ex-info (ex-message e) (assoc (ex-data e) :permanent true)))
        (throw e)))))

(defn- git!
  [args token & {:keys [dir env]}]
  (permanent #(git/run args :token token :dir dir :env env)))

(defn- with-credentials
  [url username token f]
  (git/with-credentials url username token f))

(defn- git-error
  [message]
  (ex-info message {:permanent true :git true}))

(defn checkout!
  "Clones or fast-forwards branch of url into target; returns the commit id."
  [{:keys [depth] :as options}]
  (permanent #(git/checkout (assoc options :depth (or depth 1)))))

(defn commit-and-push!
  "Commits the working tree and pushes it to branch; returns the commit id."
  [{:keys [target url branch username token message author]}]
  (git! ["add" "--all"] nil :dir target)
  (when (string/blank? (git! ["status" "--porcelain"] nil :dir target))
    (throw (git-error "the agent made no changes, so there is nothing to push")))
  (let [[author-name email]
        author]
    (git! ["commit" "--quiet" "--no-verify" "-m" message] nil
          :dir target
          :env {"GIT_AUTHOR_NAME" author-name "GIT_AUTHOR_EMAIL" email
                "GIT_COMMITTER_NAME" author-name "GIT_COMMITTER_EMAIL" email}))
  (with-credentials url username token
    (fn [auth env]
      (git! (into auth ["push" "--quiet" url (str "HEAD:refs/heads/" branch)]) token :dir target :env env)))
  (git! ["rev-parse" "HEAD"] nil :dir target))

(defn diff
  [target]
  (git! ["add" "--all" "--intent-to-add"] nil :dir target)
  (git! ["diff" "--no-color" "HEAD"] nil :dir target))

(defn branch-diff
  "The change of the branch checked out in target since it forked from base
   (git diff base...HEAD); base is fetched from origin first."
  [{:keys [target url base username token depth]}]
  (let [base-ref
        (str "refs/remotes/origin/" base)]
    (with-credentials url username token
      (fn [auth env]
        (git! (into auth ["fetch" "--quiet" "--depth" (str (or depth 1)) "origin" (str "+refs/heads/" base ":" base-ref)])
              token :dir target :env env)))
    (git! ["diff" "--no-color" (str base-ref "...HEAD")] nil :dir target)))
