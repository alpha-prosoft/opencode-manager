(ns manager-svc.platform.fixtures
  "Temporary git repositories and a platform system whose sync records into
   the mock DAL."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [edd.security :as security]
            [edd.test.fixture.dal :as mock]
            [jsonista.core :as json]
            [manager-svc.platform.api :as api]
            [manager-svc.platform.core :as platform]
            [manager-svc.paths :as paths]
            [manager-svc.test-support :as support]))

(defn delete-dir!
  [dir]
  (paths/delete-tree! dir))

(defn- git!
  [dir & args]
  (let [{:keys [exit err]}
        (apply shell/sh "git" (concat args [:dir dir]))]
    (when-not (zero? exit)
      (throw (ex-info err {:args args})))))

(defn write-files!
  [dir files]
  (doseq [[relative content] files]
    (let [file
          (io/file dir relative)]
      (io/make-parents file)
      (spit file (if (string? content) content (json/write-value-as-string content))))))

(defn commit-all!
  [dir]
  (git! dir "add" "-A")
  (git! dir "-c" "user.name=t" "-c" "user.email=t@t" "commit" "--allow-empty" "-qm" "change"))

(defn git-repo!
  "A git repository on branch main with files {relative content}; content
   that is not a string is written as JSON."
  [dir files]
  (.mkdirs (io/file dir))
  (write-files! dir files)
  (git! dir "init" "-q" "-b" "main")
  (commit-all! dir)
  dir)

(defn copy-tree!
  [from to]
  (let [root
        (io/file from)]
    (doseq [^java.io.File file (file-seq root)
            :when (.isFile file)]
      (let [target
            (io/file to (str (.relativize (.toPath root) (.toPath file))))]
        (io/make-parents target)
        (io/copy file target)))
    to))

(defn file-url
  [dir]
  (str "file://" (.getPath (io/file dir))))

(defn settings
  [roots & {:as overrides}]
  (merge support/settings
         {:onboarding-url (file-url (io/file roots "onboarding"))
          :onboarding-branch "main"
          :onboarding-file "platform.json"
          :repos-dir (.getPath (io/file roots "data"))
          :local-repo-roots [(.getPath (io/file roots))]}
         overrides))

(defn- succeeded!
  [response]
  (when (or (:error response) (:exception response))
    (throw (ex-info "command failed" {:response response})))
  response)

(defn system
  "A system with the platform components, its edd gateway on the mock DAL."
  [settings & {:as extra}]
  (let [ctx-ref
        (promise)

        as-platform
        #(support/as-platform @ctx-ref)

        system
        (merge {:settings settings
                :platform/registry (atom {})
                :platform/edd {:query (fn [query] (mock/query (as-platform) query))
                               :command (fn [& commands]
                                          (succeeded! (mock/handle-cmd (as-platform) {:commands (vec commands)})))}}
               extra)

        ctx
        (security/register (platform/register (support/ctx system)))]
    (deliver ctx-ref ctx)
    {:system system
     :ctx ctx}))

(def harness
  {:llm {:connection "llm" :models ["model-a"]}})

(def agent-spec
  {:prompt "You are helpful."})

(defn platform-json
  [roots & {:as team}]
  {:admins ["root"]
   :llm_connections [{:id "llm" :base_url "https://llm.example" :secret "LLM_KEY" :models ["model-a"]}]
   :secret_hosts {:GIT_TOKEN ["git.example.com"]}
   :teams [(merge {:id "team"
                   :name "Team"
                   :repository {:url (file-url (io/file roots "team"))}
                   :members ["dev"]}
                  team)]})

(defn build!
  "Creates the team and onboarding repositories under roots and syncs; call
   inside mock/with-mock-dal. {:system :ctx :summary}."
  [roots platform team-files & {:as overrides}]
  (git-repo! (io/file roots "team")
             (into {} (map (fn [[k v]] [(str ".platform/" k) v])) team-files))
  (git-repo! (io/file roots "onboarding") {"platform.json" platform})
  (let [{:keys [system] :as built}
        (system (apply settings roots (mapcat identity overrides)))]
    (assoc built :summary (api/sync! system))))

(defn errors-of
  [system kind id]
  (:errors (some #(when (= id (:id %)) %) (api/definitions system "team" kind))))
