(ns manager-svc.platform.sync-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.platform.api :as api]
            [manager-svc.platform.fixtures :as f]
            [manager-svc.platform.state :as state]
            [manager-svc.test-support :as support]))

(def workflow
  {:name "Analyse" :activity "analysis" :agent "a" :harness "h"})

(defn- some-error?
  [fragment errors]
  (boolean (some #(string/includes? % fragment) errors)))

(defn- frequencies-by-kind
  [definitions]
  (frequencies (map :kind definitions)))

(deftest valid-definitions-sync
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system summary]}
          (f/build! roots (f/platform-json roots)
                    {"agents/a.json" f/agent-spec "harnesses/h.json" f/harness "workflows/w.json" workflow})]
      (is
       (= []
          (get-in summary [:teams "team" :invalid])))
      (is
       (= 3
          (get-in summary [:teams "team" :definitions])))
      (is
       (= :member
          (api/role system "dev" "team")))
      (is
       (= :admin
          (api/role system "root" "team")))
      (is
       (nil?
        (api/role system "stranger" "team")))
      (is
       (= #{"team"}
          (api/teams-of system "dev")))
      (is
       (= :ok
          (:status (api/team system "team"))))
      (is
       (= :git
          (get-in (api/team system "team") [:repository :type])))
      (is
       (re-matches #"[0-9a-f]{40}" (:commit (api/status system))))
      (is
       (= "analysis"
          (get-in (api/definition system "team" :workflows "w") [:spec :activity])))
      (is
       (= [:manual]
          (map #(get-in % [:spec :trigger :type]) (api/workflows system "team")))))))

(deftest cache-is-rebuilt-from-aggregates
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system]}
          (f/build! roots (f/platform-json roots) {"agents/a.json" f/agent-spec})

          before
          (state/snapshot system)]
      (reset! (:platform/registry system) {})
      (api/load-cache! system)
      (is
       (= (:teams before)
          (:teams (state/snapshot system))))
      (is
       (= ["a"]
          (map :id (api/definitions system "team" :agents)))))))

(deftest a-failed-team-sync-keeps-the-last-good-definitions
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system]}
          (f/build! roots (f/platform-json roots) {"agents/a.json" f/agent-spec})

          commit
          (:commit (api/team system "team"))]
      (f/delete-dir! (io/file roots "team"))
      (api/sync! system)
      (is
       (= :error
          (:status (api/team system "team"))))
      (is
       (= commit
          (:commit (api/team system "team"))))
      (is
       (= ["a"]
          (map :id (api/definitions system "team" :agents)))))))

(deftest onboarding-errors-are-recorded-in-the-status
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system summary]}
          (f/build! roots {:teams [{:id "t" :name "T" :repository {:url "ftp://x/y"}}]} {})]
      (is
       (string/includes? (:error summary) "teams.0.repository.url"))
      (is
       (string/includes? (:error (api/status system)) "file:// repository URL")))))

(deftest unconfigured-platform-reports-and-records-nothing
  (mock/with-mock-dal
    (let [{:keys [system]}
          (f/system (assoc (f/settings (support/temp-dir)) :onboarding-url nil))]
      (is
       (string/includes? (:error (api/sync! system)) "MANAGER_ONBOARDING_URL"))
      (is
       (false?
        (:configured (api/status system))))
      (is
       (= ["ghcr.io/anomalyco/opencode:1.19.5"]
          (:allowed-images (api/platform-spec system)))))))

(deftest secret-cannot-be-sent-to-a-foreign-host
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          exfiltrate
          {:name "Exfiltrate" :activity "implement" :agent "a" :harness "h"
           :options {:repository {:url "https://attacker.example/x.git" :credentials "GIT_TOKEN"}}}

          {:keys [system]}
          (f/build! roots (f/platform-json roots :secrets ["GIT_TOKEN"])
                    {"agents/a.json" f/agent-spec "harnesses/h.json" f/harness "workflows/w.json" exfiltrate})]
      (is
       (some-error? "may not be sent to attacker.example" (f/errors-of system :workflows "w"))))))

(deftest secret-must-be-granted-to-the-team
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          clone
          {:name "Clone" :activity "implement" :agent "a" :harness "h"
           :options {:repository {:url "https://git.example.com/x.git" :credentials "GIT_TOKEN"}}}

          {:keys [system]}
          (f/build! roots (f/platform-json roots)
                    {"agents/a.json" f/agent-spec "harnesses/h.json" f/harness "workflows/w.json" clone})]
      (is
       (some-error? "not granted" (f/errors-of system :workflows "w"))))))

(deftest ssh-urls-are-bound-to-their-host
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          clone
          {:name "Clone" :activity "implement" :agent "a" :harness "h"
           :options {:repository {:url "git@attacker.example:x/y.git" :credentials "GIT_TOKEN"}}}

          {:keys [system]}
          (f/build! roots (f/platform-json roots :secrets ["GIT_TOKEN"])
                    {"agents/a.json" f/agent-spec "harnesses/h.json" f/harness "workflows/w.json" clone})]
      (is
       (some-error? "attacker.example" (f/errors-of system :workflows "w"))))))

(deftest file-urls-are-confined-to-local-roots
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          read-etc
          {:name "Read" :activity "implement" :agent "a" :harness "h"
           :options {:repository {:url "file:///etc"}}}

          {:keys [system]}
          (f/build! roots (f/platform-json roots)
                    {"agents/a.json" f/agent-spec "harnesses/h.json" f/harness "workflows/w.json" read-etc})]
      (is
       (some-error? "must live under" (f/errors-of system :workflows "w"))))))

(deftest referenced-files-cannot-escape-the-repository
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          leak
          {:name "Leak" :activity "analysis" :agent "a" :harness "h"
           :options {:template_file "../../../../etc/passwd"}}

          {:keys [system]}
          (f/build! roots (f/platform-json roots)
                    {"agents/a.json" f/agent-spec "harnesses/h.json" f/harness "workflows/w.json" leak})]
      (is
       (some-error? "not found" (f/errors-of system :workflows "w")))
      (is
       (thrown? clojure.lang.ExceptionInfo
                (api/read-team-file system "team" "../../../../etc/passwd"))))))

(deftest symlinks-out-of-the-repository-are-rejected
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system]}
          (f/build! roots (f/platform-json roots) {"agents/a.json" f/agent-spec "context/notes.md" "notes"})

          outside
          (io/file roots "secret.txt")]
      (spit outside "secret")
      (java.nio.file.Files/createSymbolicLink (.toPath (io/file (api/team-dir system "team") "secrets-link"))
                                              (.toPath outside)
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (is
       (= "notes"
          (api/read-team-file system "team" "context/notes.md")))
      (is
       (thrown? clojure.lang.ExceptionInfo
                (api/read-team-file system "team" "secrets-link"))))))

(deftest harness-rules
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          bad
          {:llm {:connection "llm" :models ["model-b"]} :image "evil/image:latest"}

          {:keys [system]}
          (f/build! roots (f/platform-json roots) {"harnesses/h.json" bad})

          problems
          (string/join " " (f/errors-of system :harnesses "h"))]
      (is
       (string/includes? problems "allowed_images"))
      (is
       (string/includes? problems "not offered")))))

(deftest invalid-json-and-schema-errors-are-reported-per-definition
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system summary]}
          (f/build! roots (f/platform-json roots)
                    {"agents/broken.json" "{not json"
                     "agents/both.json" {:prompt "x" :prompt_file "y"}})]
      (is
       (= ["agents/both" "agents/broken"]
          (sort (get-in summary [:teams "team" :invalid]))))
      (is
       (some-error? "invalid JSON" (f/errors-of system :agents "broken")))
      (is
       (nil?
        (api/definition system "team" :agents "both"))))))

(deftest path-source-must-stay-under-local-roots
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [summary]}
          (f/build! roots
                    (assoc (f/platform-json roots)
                           :teams [{:id "team" :name "T" :repository {:type "path" :location "/etc"}}])
                    {"agents/a.json" f/agent-spec})]
      (is
       (string/includes? (get-in summary [:teams "team" :error]) "must live under")))))

(deftest path-source-syncs-definitions
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system summary]}
          (f/build! roots
                    (assoc (f/platform-json roots)
                           :teams [{:id "team" :name "T"
                                    :repository {:type "path" :location (.getPath (io/file roots "team"))}}])
                    {"agents/a.json" f/agent-spec})]
      (is
       (string/starts-with? (get-in summary [:teams "team" :commit]) "files:"))
      (is
       (= ["a"]
          (map :id (api/definitions system "team" :agents)))))))

(deftest onboarding-from-a-plain-local-directory
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          _
          (f/git-repo! (io/file roots "team") {".platform/agents/a.json" f/agent-spec})

          _
          (f/write-files! (io/file roots "plain") {"platform.json" (f/platform-json roots)})

          {:keys [system]}
          (f/system (f/settings roots :onboarding-url (f/file-url (io/file roots "plain"))))

          summary
          (api/sync! system)]
      (is
       (string/starts-with? (:commit summary) "files:"))
      (is
       (= ["a"]
          (map :id (api/definitions system "team" :agents)))))))

(deftest automated-workflows-cannot-rely-on-personal-credentials
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          platform
          (assoc (f/platform-json roots :secrets [])
                 :credentials [{:id "TOKEN" :name "Token" :hosts ["tools.internal"] :provided_by "user"}]
                 :mcp_servers [{:id "tools" :name "Tools" :url "http://tools.internal/" :credential "TOKEN"}])

          nightly
          (assoc workflow :trigger {:type "schedule" :every_minutes 60})

          {:keys [system]}
          (f/build! roots platform {"agents/a.json" f/agent-spec
                                    "harnesses/h.json" (assoc f/harness :mcp ["tools"])
                                    "workflows/w.json" nightly})]
      (is
       (some-error? "only has personal values" (f/errors-of system :workflows "w")))
      (is
       (= ["TOKEN"]
          (api/harness-credentials system (:spec (api/definition system "team" :harnesses "h"))))))))

(deftest ci-following-needs-a-pull-request-and-allowed-tools
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          platform
          (assoc (f/platform-json roots :secrets ["GIT_TOKEN"])
                 :credentials [{:id "CI" :name "CI" :hosts ["ci.internal"]}]
                 :mcp_servers [{:id "ci" :name "CI" :url "http://ci.internal/" :credential "CI" :tools ["get_build"]}])

          change
          {:name "Change" :activity "implement" :agent "a" :harness "h"
           :options {:repository {:url (f/file-url (io/file roots "team"))}
                     :ci {:mcp_server "ci" :job "repo/PR-{pr}" :on_failure "missing"}}}

          {:keys [system]}
          (f/build! roots platform {"agents/a.json" f/agent-spec "harnesses/h.json" f/harness "workflows/w.json" change})

          problems
          (string/join " " (f/errors-of system :workflows "w"))]
      (is
       (string/includes? problems "needs options.pull_request"))
      (is
       (string/includes? problems "must allow the tools"))
      (is
       (string/includes? problems "missing does not exist")))))

(deftest a-review-reviews-either-pull-requests-or-a-branch
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          review
          {:name "Review" :activity "review" :agent "a" :harness "h"}

          repository
          {:url (f/file-url (io/file roots "team"))}

          scm
          {:provider "github" :repository "acme/app" :credentials "GIT_TOKEN"}

          {:keys [system]}
          (f/build! roots (f/platform-json roots :secrets ["GIT_TOKEN"])
                    {"agents/a.json" f/agent-spec
                     "harnesses/h.json" f/harness
                     "workflows/branch.json" (assoc review :options {:repository repository})
                     "workflows/pull-requests.json" (assoc review :options {:scm scm})
                     "workflows/neither.json" review
                     "workflows/both.json" (assoc review :options {:repository repository :scm scm})
                     "workflows/triggered-branch.json" (assoc review
                                                              :options {:repository repository}
                                                              :trigger (assoc scm :type "pull_request"))
                     "workflows/posting-branch.json" (assoc review :options {:repository repository :post_mode "auto"})})]
      (is
       (= [[] []]
          [(f/errors-of system :workflows "branch") (f/errors-of system :workflows "pull-requests")]))
      (doseq [id ["neither" "both" "triggered-branch"]]
        (is
         (some-error? "set exactly one of options.scm" (f/errors-of system :workflows id))
         id))
      (is
       (some-error? "branch reviews never post" (f/errors-of system :workflows "posting-branch"))))))

(deftest skills-are-read-without-links-or-escapes
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          skill
          "---\nname: triage\ndescription: x\n---\n"

          {:keys [system]}
          (f/build! roots (f/platform-json roots)
                    {"skills/triage/SKILL.md" skill
                     "harnesses/h.json" (assoc f/harness :skills ["skills/triage"])
                     "harnesses/escape.json" (assoc f/harness :skills ["../"])})]
      (is
       (= {"triage/SKILL.md" skill}
          (api/read-skills system "team" ["skills/triage"])))
      (is
       (some-error? "no SKILL.md" (f/errors-of system :harnesses "escape")))
      (java.nio.file.Files/createSymbolicLink (.toPath (io/file (api/team-dir system "team") "skills/triage/secret"))
                                              (.toPath (io/file "/etc/passwd"))
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (is
       (thrown-with-msg? clojure.lang.ExceptionInfo #"links are not allowed"
                         (api/read-skills system "team" ["skills/triage"]))))))

(deftest two-skills-with-one-name-are-refused
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          skill
          "---\nname: lint\ndescription: x\n---\n"

          {:keys [system]}
          (f/build! roots (f/platform-json roots)
                    {"a/lint/SKILL.md" skill
                     "b/lint/SKILL.md" skill
                     "harnesses/h.json" (assoc f/harness :skills ["a/lint" "b/lint"])})]
      (is
       (some-error? "another skill is also named lint" (f/errors-of system :harnesses "h"))))))

(deftest example-repositories-sync
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          team-url
          (f/file-url (io/file roots "demo-team"))

          _
          (f/git-repo! (io/file roots "demo-team") {})

          _
          (f/copy-tree! "../examples/team-repo" (io/file roots "demo-team"))

          _
          (f/commit-all! (io/file roots "demo-team"))

          example
          (string/replace (slurp "../examples/onboarding/platform.json") "/repos/demo-team"
                          (.getPath (io/file roots "demo-team")))

          _
          (f/git-repo! (io/file roots "onboarding") {"platform.json" example})

          {:keys [system]}
          (f/system (f/settings roots :local-repo-roots [(.getPath roots) "/repos"]))

          summary
          (api/sync! system)]
      (testing "every team source type syncs"
        (is
         (= {"demo" nil "payments" nil "data" nil "ops" nil}
            (update-vals (:teams summary) :error)))
        (is
         (string/starts-with? (get-in summary [:teams "payments" :commit]) "files:")))
      (testing "registry views of the example"
        (is
         (= #{"demo" "payments" "data" "ops"}
            (api/teams-of system "alice")))
        (is
         (= #{"demo" "data"}
            (api/teams-of system "bob")))
        (is
         (= :admin
            (api/role system "dave" "payments")))
        (is
         (= {"workspace" true "scheduled-workspace" true}
            (into {} (map (juxt :id :valid)) (api/definitions system "ops" :workflows))))
        (is
         (= team-url
            (get-in (api/team system "demo") [:repository :url])))
        (is
         (= {:agents 3 :harnesses 4 :workflows 6}
            (frequencies-by-kind (api/definitions system "demo"))))
        (is
         (= "file:///repos/demo-service"
            (get-in (api/definition system "demo" :workflows "review-branch") [:spec :options :repository :url])))
        (is
         (= :internet
            (get-in (api/definition system "demo" :harnesses "connected") [:spec :network])))
        (is
         (= {:bash :allow :edit :allow}
            (get-in (api/definition system "demo" :harnesses "sandboxed") [:spec :permissions])))))))
