(ns manager-svc.platform.sync-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.platform.api :as api]
            [manager-svc.platform.fixtures :as f]
            [manager-svc.platform.registry :as registry]
            [manager-svc.platform.state :as state]
            [manager-svc.test-support :as support]))

(def dev
  (support/person "dev" {:teams {"team" :member}}))

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
          (f/build! roots (f/tenant-json roots)
                    {"agents/a.json" f/agent-spec "harnesses/h.json" f/harness "workflows/w.json" workflow})]
      (is
       (= []
          (get-in summary ["acme" :teams "team" :invalid])))
      (is
       (= 3
          (get-in summary ["acme" :teams "team" :definitions])))
      (is
       (= :member
          (api/role system dev "team")))
      (is
       (= :admin
          (api/role system (support/person "root" {:tenant-admin true}) "team")))
      (is
       (nil?
        (api/role system (support/person "stranger") "team")))
      (is
       (= #{"team"}
          (api/teams-of system dev)))
      (is
       (= #{}
          (api/teams-of system (support/person "dev" {:tenant "globex" :teams {"team" :member}}))))
      (is
       (= :ok
          (:status (api/team system "acme" "team"))))
      (is
       (= :git
          (get-in (api/team system "acme" "team") [:repository :type])))
      (is
       (re-matches #"[0-9a-f]{40}" (:commit (api/status system "acme"))))
      (is
       (= "analysis"
          (get-in (api/definition system "acme" "team" :workflows "w") [:spec :activity])))
      (is
       (= [:manual]
          (map #(get-in % [:spec :trigger :type]) (api/workflows system "acme" "team")))))))

(deftest cache-is-rebuilt-from-aggregates
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system]}
          (f/build! roots (f/tenant-json roots) {"agents/a.json" f/agent-spec})

          before
          (state/snapshot system)]
      (reset! (:platform/registry system) {})
      (api/load-cache! system)
      (is
       (= (:teams before)
          (:teams (state/snapshot system))))
      (is
       (= ["a"]
          (map :id (api/definitions system "acme" "team" :agents)))))))

(deftest a-failed-team-sync-keeps-the-last-good-definitions
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system]}
          (f/build! roots (f/tenant-json roots) {"agents/a.json" f/agent-spec})

          commit
          (:commit (api/team system "acme" "team"))]
      (f/delete-dir! (io/file roots "team"))
      (api/sync! system)
      (is
       (= :error
          (:status (api/team system "acme" "team"))))
      (is
       (= commit
          (:commit (api/team system "acme" "team"))))
      (is
       (= ["a"]
          (map :id (api/definitions system "acme" "team" :agents)))))))

(deftest onboarding-errors-are-recorded-in-the-status
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system summary]}
          (f/build! roots {:teams [{:id "t" :name "T" :repository {:url "ftp://x/y"}}]} {})]
      (is
       (string/includes? (get-in summary ["acme" :error]) "teams.0.repository.url"))
      (is
       (string/includes? (:error (api/status system "acme")) "file:// repository URL")))))

(deftest a-missing-tenant-file-is-said-without-server-paths
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          _
          (f/git-repo! (io/file roots "onboarding") {"README.md" "no tenant.json here"})

          {:keys [system]}
          (f/system (f/settings roots))

          summary
          (api/sync! system)]
      (is
       (= "tenant.json: not found at the root of the onboarding source"
          (get-in summary ["acme" :error])))
      (is
       (= "tenant.json: not found at the root of the onboarding source"
          (:error (api/status system "acme")))))))

(deftest unexpected-sync-failures-are-logged-not-shown
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system]}
          (f/build! roots (f/tenant-json roots) {"agents/a.json" f/agent-spec})]
      (testing "a team's"
        (with-redefs [registry/load-definitions (fn [& _] (throw (java.io.IOException. "/data/repos/secret (Permission denied)")))]
          (api/sync! system))
        (is
         (= registry/sync-failed
            (:error (api/team system "acme" "team")))))
      (testing "the onboarding's"
        (with-redefs [registry/fetch-source (fn [& _] (throw (java.io.IOException. "/data/repos/secret (Permission denied)")))]
          (api/sync! system))
        (is
         (= registry/sync-failed
            (:error (api/status system "acme"))))))))

(deftest a-cache-load-never-replaces-a-later-one
  (let [first-reads
        (promise)

        release
        (promise)

        calls
        (atom 0)

        system
        {:platform/registry (atom {})
         :platform/edd {:query (fn [_query]
                                 (if (= 1 (swap! calls inc))
                                   (do
                                     (deliver first-reads true)
                                     @release
                                     {:registered [{:name "first"}] :tenants []})
                                   {:registered [{:name "second"}] :tenants []}))}}

        earlier
        (future (api/load-cache! system))

        _
        @first-reads

        later
        (future (api/load-cache! system))]
    (Thread/sleep 50)
    (deliver release true)
    @earlier
    @later
    (is
     (= [{:name "second"}]
        (:registered (state/snapshot system))))))

(deftest a-request-for-an-unknown-tenant-reloads-the-registry-once
  (let [requests
        (atom [{:tenant "gone" :requested-at "2026-01-01T00:00:00Z"}])

        loads
        (atom 0)

        system
        {:settings (f/settings (support/temp-dir) :tenants nil)
         :platform/registry (atom {})
         :platform/unknown-sync-requests (atom #{})
         :platform/edd {:query (fn [{:keys [query-id]}]
                                 (case query-id
                                   :get-sync-requests @requests
                                   :get-registry (do
                                                   (swap! loads inc)
                                                   {:registered [] :tenants []})))}}]
    (dotimes [_ 3]
      (is
       (= {}
          (api/sync-requested! system))))
    (is
     (= 1
        @loads))
    (testing "a newer request looks again"
      (swap! requests conj {:tenant "gone" :requested-at "2026-01-02T00:00:00Z"})
      (api/sync-requested! system)
      (is
       (= 2
          @loads)))))

(deftest without-tenants-nothing-syncs
  (mock/with-mock-dal
    (let [{:keys [system]}
          (f/system (assoc (f/settings (support/temp-dir)) :tenants nil))]
      (is
       (= {}
          (api/sync! system)))
      (is
       (false?
        (:configured (api/status system "acme"))))
      (is
       (= ["ghcr.io/anomalyco/opencode:1.19.5"]
          (:allowed-images (api/tenant-spec system "acme")))))))

(deftest secret-cannot-be-sent-to-a-foreign-host
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          exfiltrate
          {:name "Exfiltrate" :activity "implement" :agent "a" :harness "h"
           :options {:repository {:url "https://attacker.example/x.git" :credentials "GIT_TOKEN"}}}

          {:keys [system]}
          (f/build! roots (f/tenant-json roots :secrets ["GIT_TOKEN"])
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
          (f/build! roots (f/tenant-json roots)
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
          (f/build! roots (f/tenant-json roots :secrets ["GIT_TOKEN"])
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
          (f/build! roots (f/tenant-json roots)
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
          (f/build! roots (f/tenant-json roots)
                    {"agents/a.json" f/agent-spec "harnesses/h.json" f/harness "workflows/w.json" leak})]
      (is
       (some-error? "not found" (f/errors-of system :workflows "w")))
      (is
       (thrown? clojure.lang.ExceptionInfo
                (api/read-team-file system "acme" "team" "../../../../etc/passwd"))))))

(deftest symlinks-out-of-the-repository-are-rejected
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system]}
          (f/build! roots (f/tenant-json roots) {"agents/a.json" f/agent-spec "context/notes.md" "notes"})

          outside
          (io/file roots "secret.txt")]
      (spit outside "secret")
      (java.nio.file.Files/createSymbolicLink (.toPath (io/file (api/team-dir system "acme" "team") "secrets-link"))
                                              (.toPath outside)
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (is
       (= "notes"
          (api/read-team-file system "acme" "team" "context/notes.md")))
      (is
       (thrown? clojure.lang.ExceptionInfo
                (api/read-team-file system "acme" "team" "secrets-link"))))))

(deftest harness-rules
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          bad
          {:llm {:connection "llm" :models ["model-c"]} :image "evil/image:latest"}

          {:keys [system]}
          (f/build! roots (f/tenant-json roots) {"harnesses/h.json" bad})

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
          (f/build! roots (f/tenant-json roots)
                    {"agents/broken.json" "{not json"
                     "agents/both.json" {:prompt "x" :prompt_file "y"}})]
      (is
       (= ["agents/both" "agents/broken"]
          (sort (get-in summary ["acme" :teams "team" :invalid]))))
      (is
       (some-error? "invalid JSON" (f/errors-of system :agents "broken")))
      (is
       (nil?
        (api/definition system "acme" "team" :agents "both"))))))

(deftest path-source-must-stay-under-local-roots
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [summary]}
          (f/build! roots
                    (assoc (f/tenant-json roots)
                           :teams [{:id "team" :name "T" :repository {:type "path" :location "/etc"}}])
                    {"agents/a.json" f/agent-spec})]
      (is
       (string/includes? (get-in summary ["acme" :teams "team" :error]) "must live under")))))

(deftest path-source-syncs-definitions
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system summary]}
          (f/build! roots
                    (assoc (f/tenant-json roots)
                           :teams [{:id "team" :name "T"
                                    :repository {:type "path" :location (.getPath (io/file roots "team"))}}])
                    {"agents/a.json" f/agent-spec})]
      (is
       (string/starts-with? (get-in summary ["acme" :teams "team" :commit]) "files:"))
      (is
       (= ["a"]
          (map :id (api/definitions system "acme" "team" :agents)))))))

(deftest onboarding-from-a-plain-local-directory
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          _
          (f/git-repo! (io/file roots "team") {".platform/agents/a.json" f/agent-spec})

          _
          (f/write-files! (io/file roots "plain") {"tenant.json" (f/tenant-json roots)})

          {:keys [system]}
          (f/system (f/settings roots :tenants (f/tenants-setting {"acme" (f/file-url (io/file roots "plain"))})))

          summary
          (api/sync! system)]
      (is
       (string/starts-with? (get-in summary ["acme" :commit]) "files:"))
      (is
       (= ["a"]
          (map :id (api/definitions system "acme" "team" :agents)))))))

(deftest automated-workflows-cannot-rely-on-personal-credentials
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          platform
          (assoc (f/tenant-json roots :secrets [])
                 :credentials [{:id "TOKEN" :name "Token" :hosts ["tools.internal"] :provided_by ["user"]}]
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
          (api/harness-credentials system "acme" (:spec (api/definition system "acme" "team" :harnesses "h"))))))))

(deftest ci-following-needs-a-pull-request-and-allowed-tools
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          platform
          (assoc (f/tenant-json roots :secrets ["GIT_TOKEN"])
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
          (f/build! roots (f/tenant-json roots :secrets ["GIT_TOKEN"])
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

(defn- gerrit-project-team
  [roots & settings]
  (let [platform
        (assoc (f/tenant-json roots)
               :credentials [{:id "GERRIT_TOKEN" :name "Gerrit" :kind "basic" :hosts ["gerrit"]}
                             {:id "JENKINS_TOKEN" :name "Jenkins" :kind "basic" :hosts ["mcp-jenkins"]}]
               :mcp_servers [{:id "jenkins" :name "Jenkins" :url "http://mcp-jenkins:8000/" :credential "JENKINS_TOKEN"
                              :tools ["get_build" "get_builds" "get_build_log"]}])

        team-json
        {:projects [{:id "demo-service"
                     :scm {:provider "gerrit" :url "http://gerrit:8080" :web_url "http://gerrit.localhost:8080"
                           :repository "demo-service" :credentials "GERRIT_TOKEN"}
                     :ci {:mcp_server "jenkins" :job "demo-service-verify"}}]}

        base
        {:agent "a" :harness "h" :project "demo-service"}]
    (apply f/build! roots platform
           {"team.json" team-json
            "agents/a.json" f/agent-spec
            "harnesses/h.json" f/harness
            "workflows/gerrit-review.json" (assoc base :name "Review" :activity "review"
                                                  :trigger {:type "pull_request" :poll_minutes 1}
                                                  :options {:post_mode "auto"})
            "workflows/implement.json" (assoc base :name "Implement" :activity "implement"
                                              :options {:ci {:on_failure "analyse"}})
            "workflows/analyse.json" (assoc base :name "Analyse" :activity "analysis")
            "workflows/unknown.json" (assoc base :name "Unknown" :activity "review" :project "nope")}
           settings)))

(deftest workflows-take-their-source-and-ci-from-a-team-project
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system]}
          (gerrit-project-team roots :http-git-hosts ["gerrit"])

          spec-of
          (fn [id] (:spec (api/definition system "acme" "team" :workflows id)))]
      (is
       (= [[] [] []]
          (mapv #(f/errors-of system :workflows %) ["gerrit-review" "implement" "analyse"])))
      (testing "the trigger polls the project's changes"
        (is
         (= {:type :pull-request :provider :gerrit :repository "demo-service" :url "http://gerrit:8080"
             :web-url "http://gerrit.localhost:8080" :credentials "GERRIT_TOKEN"}
            (select-keys (:trigger (spec-of "gerrit-review"))
                         [:type :provider :repository :url :web-url :credentials]))))
      (testing "the implementation pushes to the project and follows its CI job"
        (is
         (= {:url "http://gerrit:8080/a/demo-service" :credentials "GERRIT_TOKEN"}
            (select-keys (get-in (spec-of "implement") [:options :repository]) [:url :credentials])))
        (is
         (= {:provider :gerrit :repository "demo-service"}
            (select-keys (get-in (spec-of "implement") [:options :pull-request]) [:provider :repository])))
        (is
         (= {:mcp-server "jenkins" :job "demo-service-verify" :on-failure "analyse" :timeout-minutes 120}
            (get-in (spec-of "implement") [:options :ci]))))
      (is
       (some-error? "project nope is not in the team's team.json projects" (f/errors-of system :workflows "unknown"))))))

(deftest plain-http-git-needs-the-operators-consent
  (mock/with-mock-dal
    (let [{:keys [system]}
          (gerrit-project-team (support/temp-dir))]
      (is
       (some-error? "MANAGER_HTTP_GIT_HOSTS" (f/errors-of system :workflows "implement"))))))

(deftest skills-are-read-without-links-or-escapes
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          skill
          "---\nname: triage\ndescription: x\n---\n"

          {:keys [system]}
          (f/build! roots (f/tenant-json roots)
                    {"skills/triage/SKILL.md" skill
                     "harnesses/h.json" (assoc f/harness :skills ["skills/triage"])
                     "harnesses/escape.json" (assoc f/harness :skills ["../"])})]
      (is
       (= {"triage/SKILL.md" skill}
          (api/read-skills system "acme" "team" ["skills/triage"])))
      (is
       (some-error? "no SKILL.md" (f/errors-of system :harnesses "escape")))
      (java.nio.file.Files/createSymbolicLink (.toPath (io/file (api/team-dir system "acme" "team") "skills/triage/secret"))
                                              (.toPath (io/file "/etc/passwd"))
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (is
       (thrown-with-msg? clojure.lang.ExceptionInfo #"links are not allowed"
                         (api/read-skills system "acme" "team" ["skills/triage"]))))))

(deftest two-skills-with-one-name-are-refused
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          skill
          "---\nname: lint\ndescription: x\n---\n"

          {:keys [system]}
          (f/build! roots (f/tenant-json roots)
                    {"a/lint/SKILL.md" skill
                     "b/lint/SKILL.md" skill
                     "harnesses/h.json" (assoc f/harness :skills ["a/lint" "b/lint"])})]
      (is
       (some-error? "another skill is also named lint" (f/errors-of system :harnesses "h"))))))

(defn- publish-example!
  "examples/tenants/<tenant>/teams/<team> as the git repository <tenant>-<team>."
  [roots tenant team]
  (let [dir
        (io/file roots (str tenant "-" team))]
    (f/git-repo! dir {})
    (f/copy-tree! (str "../examples/tenants/" tenant "/teams/" team) dir)
    (f/commit-all! dir)))

(defn- publish-onboarding!
  [roots tenant]
  (let [text
        (string/replace (slurp (str "../examples/tenants/" tenant "/onboarding/tenant.json"))
                        "file:///repos/" (str "file://" (.getPath (io/file roots)) "/"))]
    (f/git-repo! (io/file roots (str tenant "-onboarding")) {"tenant.json" text})))

(deftest example-repositories-sync
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          _
          (doseq [[tenant team] [["acme" "demo"] ["acme" "payments"] ["acme" "ops"] ["globex" "research"] ["globex" "lab"]]]
            (publish-example! roots tenant team))

          _
          (doseq [tenant ["acme" "globex"]]
            (publish-onboarding! roots tenant))

          {:keys [system]}
          (f/system (f/settings roots
                                :tenants (f/tenants-setting {"acme" (f/file-url (io/file roots "acme-onboarding"))
                                                             "globex" (f/file-url (io/file roots "globex-onboarding"))})
                                :local-repo-roots [(.getPath roots) "/repos"]))

          summary
          (api/sync! system)]
      (testing "both tenants and every team sync"
        (is
         (= {"acme" {"demo" nil "payments" nil "ops" nil}
             "globex" {"research" nil "lab" nil}}
            (update-vals summary #(update-vals (:teams %) :error)))))
      (testing "registry views of the example"
        (is
         (= #{"demo" "payments" "ops"}
            (api/teams-of system (support/person "alice" {:tenant-admin true}))))
        (is
         (= #{"research" "lab"}
            (api/teams-of system (support/person "gina" {:tenant "globex" :tenant-admin true}))))
        (is
         (= {"workspace" true "scheduled-workspace" true}
            (into {} (map (juxt :id :valid)) (api/definitions system "acme" "ops" :workflows))))
        (is
         (= {:agents 3 :harnesses 4 :workflows 9}
            (frequencies-by-kind (api/definitions system "acme" "demo"))))
        (is
         (= #{"github-review" "implement-gerrit"}
            (set (keep #(when-not (:valid %) (:id %)) (mapcat #(api/definitions system "acme" %) ["demo" "payments" "ops"]))))
         "only the workflow needing a credential the tenant never granted, and the one pushing to Gerrit over http without MANAGER_HTTP_GIT_HOSTS, are invalid")
        (is
         (every? :valid (api/definitions system "globex" "research")))
        (is
         (= "file:///repos/demo-service"
            (get-in (api/definition system "acme" "demo" :workflows "review-branch") [:spec :options :repository :url])))
        (is
         (= :internet
            (get-in (api/definition system "acme" "demo" :harnesses "connected") [:spec :network])))
        (is
         (= {:bash :allow :edit :allow}
            (get-in (api/definition system "acme" "demo" :harnesses "sandboxed") [:spec :permissions]))))
      (testing "team.json budgets"
        (is
         (= {:daily-tokens 5000000 :max-running-sessions 10 :idle-minutes 30}
            (:budget (api/team system "acme" "demo"))))
        (is
         (= 2
            (get-in (api/team system "acme" "payments") [:budget :max-running-sessions])))
        (is
         (= [{:id "demo-service" :name "Demo service" :branch "main"
              :scm {:provider :gerrit :url "http://gerrit:8080" :web-url "http://gerrit.localhost:8080"
                    :repository "demo-service" :credentials "GERRIT_TOKEN"}
              :ci {:mcp-server "jenkins" :jobs ["demo-service-verify"] :job "demo-service-verify"}}]
            (:projects (api/team system "acme" "demo"))))
        (is
         (= :gerrit
            (get-in (api/definition system "acme" "demo" :workflows "gerrit-review") [:spec :trigger :provider]))))
      (testing "the tenants keep apart"
        (is
         (nil? (api/team system "globex" "demo")))
        (is
         (= ["lab" "research"]
            (mapv :id (api/teams system "globex"))))
        (is
         (nil? (api/credential system "globex" "BITBUCKET_TOKEN")))
        (is
         (= [:tenant :team :user]
            (:provided-by (api/credential system "acme" "BITBUCKET_TOKEN"))))))))

(defn- two-tenants
  [roots]
  (doseq [tenant ["acme" "globex"]]
    (f/git-repo! (io/file roots (str tenant "-team")) {".platform/agents/a.json" f/agent-spec})
    (f/git-repo! (io/file roots (str tenant "-onboarding"))
                 {"tenant.json" (assoc (f/tenant-json roots)
                                       :teams [{:id "team" :name (str tenant " team")
                                                :repository {:url (f/file-url (io/file roots (str tenant "-team")))}}])}))
  (f/system (f/settings roots :tenants (f/tenants-setting {"acme" (f/file-url (io/file roots "acme-onboarding"))
                                                           "globex" (f/file-url (io/file roots "globex-onboarding"))}))))

(deftest tenants-sync-independently
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system ctx]}
          (two-tenants roots)

          _
          (api/sync! system)]
      (is
       (= {"acme" "acme team" "globex" "globex team"}
          {"acme" (:name (api/team system "acme" "team")) "globex" (:name (api/team system "globex" "team"))}))
      (testing "one tenant's broken onboarding never touches another"
        (f/write-files! (io/file roots "globex-onboarding") {"tenant.json" "{broken"})
        (f/commit-all! (io/file roots "globex-onboarding"))
        (let [summary
              (api/sync! system)]
          (is
           (some? (get-in summary ["globex" :error])))
          (is
           (nil? (get-in summary ["acme" :error])))
          (is
           (string/includes? (:error (api/status system "globex")) "tenant.json"))
          (is
           (nil? (:error (api/status system "acme"))))
          (is
           (= "globex team"
              (:name (api/team system "globex" "team")))
           "the last good tenant.json stays")))
      (testing "aggregates are found by identity"
        (is
         (= #{"acme" "globex"}
            (set (map :name (:tenants (state/get-registry (support/as-platform ctx) {}))))))))))

(defmacro ^:private failure-status
  "The status a command failed with, nil when it succeeded."
  [command]
  `(try
     (let [response#
           ~command]
       (or (get-in response# [:exception :status]) (get-in response# [:error :status])))
     (catch clojure.lang.ExceptionInfo e#
       (:status (ex-data e#)))))

(deftest a-tenant-admin-requests-a-sync-of-their-own-tenant
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system ctx]}
          (two-tenants roots)

          request
          (fn [person attrs]
            (mock/handle-cmd (assoc-in ctx [:meta :user] person) {:cmd-id :sync-tenant :attrs attrs}))

          alice
          (support/person "alice" {:tenant-admin true})

          oscar
          (support/person "oscar" {:tenant nil :operator true})]
      (api/sync! system)
      (is
       (= 403
          (failure-status (request (support/person "bob") {}))))
      (testing "another tenant does not exist for a tenant admin"
        (is
         (= 404
            (failure-status (request alice {:tenant "globex"}))))
        (is
         (= 404
            (failure-status (request alice {:tenant "nowhere"})))))
      (is
       (= 404
          (failure-status (request (support/person "nobody" {:tenant nil}) {}))))
      (is
       (nil? (failure-status (request alice {}))))
      (is
       (nil? (failure-status (request oscar {:tenant "globex"}))))
      (is
       (= 404
          (failure-status (request oscar {:tenant "nowhere"}))))
      (is
       (= #{"acme" "globex"}
          (set (keys (api/sync-requested! system)))))
      (is
       (= {}
          (api/sync-requested! system))
       "a handled request is not synced again"))))

(deftest operators-register-tenants
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system ctx]}
          (two-tenants roots)

          register
          (fn [person attrs]
            (mock/handle-cmd (assoc-in ctx [:meta :user] person) {:cmd-id :register-tenant :attrs attrs}))

          oscar
          (support/person "oscar" {:tenant nil :operator true})

          initech
          {:name "initech" :onboarding (f/file-url (io/file roots "acme-onboarding"))}]
      (api/sync! system)
      (is
       (= 403
          (failure-status (register (support/person "alice" {:tenant-admin true}) initech))))
      (is
       (nil? (failure-status (register oscar initech))))
      (testing "the operator's list shows it before the cache is reloaded"
        (is
         (= ["acme" "globex" "initech"]
            (mapv :name (mock/query (assoc-in ctx [:meta :user] oscar) {:query-id :list-tenants}))))
        (is
         (= ["acme" "globex"]
            (mapv :name (api/tenants system)))))
      (support/settle! ctx)
      (testing "its first sync is requested; handling it reloads the cache and syncs it"
        (is
         (= ["initech"]
            (keys (api/sync-requested! system))))
        (is
         (= [["acme" :config] ["globex" :config] ["initech" :registered]]
            (mapv (juxt :name :origin) (api/tenants system))))
        (is
         (= ["team"]
            (mapv :id (api/teams system "initech")))))
      (is
       (= 409
          (failure-status (register oscar initech)))
       "a tenant is registered once")
      (is
       (= 409
          (failure-status (register oscar {:name "acme" :onboarding "/x"}))))
      (is
       (= 422
          (failure-status (register oscar {:name "Bad Name" :onboarding "/x"}))))
      (is
       (nil? (get-in (api/sync! system) ["initech" :error]))))))

(deftest team-config-limits-what-a-team-uses
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          tenant
          (assoc (f/tenant-json roots)
                 :credentials [{:id "TOOLS" :name "Tools" :hosts ["tools.internal" "other.internal"]}]
                 :mcp_servers [{:id "tools" :name "Tools" :url "http://tools.internal/" :credential "TOOLS"}
                               {:id "other" :name "Other" :url "http://other.internal/" :credential "TOOLS"}])

          team-config
          {:budget {:daily_tokens 20000 :idle_minutes 10}
           :llm_connections [{:id "llm" :models ["model-a"]}]
           :mcp_servers ["tools"]}

          {:keys [system]}
          (f/build! roots tenant
                    {"team.json" team-config
                     "agents/a.json" f/agent-spec
                     "harnesses/ok.json" (assoc f/harness :mcp ["tools"])
                     "harnesses/model.json" {:llm {:connection "llm" :models ["model-b"]}}
                     "harnesses/server.json" (assoc f/harness :mcp ["other"])})]
      (is
       (= {:daily-tokens 20000 :idle-minutes 10}
          (:budget (api/team system "acme" "team"))))
      (is
       (= [{:id "llm" :models ["model-a"]}]
          (:llm-connections (api/team system "acme" "team"))))
      (is
       (= ["tools"]
          (:mcp-servers (api/team system "acme" "team"))))
      (is
       (= [[] true]
          [(f/errors-of system :harnesses "ok") (:valid (api/definition system "acme" "team" :harnesses "ok"))]))
      (is
       (some-error? "not allowed by the team's team.json" (f/errors-of system :harnesses "model")))
      (is
       (some-error? "not in the team's team.json mcp_servers" (f/errors-of system :harnesses "server"))))))

(deftest without-team-json-a-team-uses-what-the-tenant-grants
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system]}
          (f/build! roots (f/tenant-json roots) {"harnesses/h.json" {:llm {:connection "llm" :models ["model-b"]}}})]
      (is
       (= [{:id "llm" :models ["model-a" "model-b"]}]
          (:llm-connections (api/team system "acme" "team"))))
      (is
       (= {}
          (:budget (api/team system "acme" "team"))))
      (is
       (= []
          (f/errors-of system :harnesses "h"))))))

(deftest an-invalid-team-json-fails-the-team-sync
  (mock/with-mock-dal
    (let [roots
          (support/temp-dir)

          {:keys [system summary]}
          (f/build! roots (f/tenant-json roots)
                    {"team.json" {:budget {:daily_tokens 5} :colour "red"}
                     "agents/a.json" f/agent-spec})

          error
          (get-in summary ["acme" :teams "team" :error])]
      (is
       (string/includes? error "team.json"))
      (is
       (= :error
          (:status (api/team system "acme" "team"))))
      (testing "it is checked against what the tenant declares"
        (f/write-files! (io/file roots "team") {".platform/team.json" {:llm_connections [{:id "missing"}] :mcp_servers ["nope"]}})
        (f/commit-all! (io/file roots "team"))
        (let [error
              (get-in (api/sync! system) ["acme" :teams "team" :error])]
          (is
           (string/includes? error "connection missing is not configured for the tenant"))
          (is
           (string/includes? error "MCP server nope is not configured for the tenant")))))))
