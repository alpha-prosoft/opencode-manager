(ns manager-svc.platform.projects-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.platform.projects :as projects]
            [manager-svc.platform.spec :as spec]))

(def team
  {:id "demo"
   :projects [{:id "demo-service"
               :branch "main"
               :scm {:provider :gerrit
                     :repository "demo-service"
                     :url "http://gerrit:8080"
                     :web-url "http://gerrit.localhost:8080"
                     :credentials "GERRIT_TOKEN"}
               :ci {:mcp-server "jenkins" :jobs [] :job "demo-service-verify"}}
              {:id "app"
               :scm {:provider :github :repository "acme/app" :credentials "GITHUB_TOKEN"}}]})

(defn- resolved
  [workflow]
  (projects/apply-project team workflow))

(deftest a-review-workflow-reviews-the-projects-changes
  (let [{:keys [workflow errors]}
        (resolved {:project "demo-service"
                   :activity "review"
                   :trigger {:type :pull-request :poll-minutes 1}
                   :options {:post_mode "auto"}})]
    (is
     (= []
        errors))
    (is
     (= {:type :pull-request
         :poll-minutes 1
         :provider :gerrit
         :repository "demo-service"
         :url "http://gerrit:8080"
         :web-url "http://gerrit.localhost:8080"
         :credentials "GERRIT_TOKEN"}
        (:trigger workflow)))
    (is
     (= {:post_mode "auto"
         :scm {:provider "gerrit"
               :repository "demo-service"
               :url "http://gerrit:8080"
               :web_url "http://gerrit.localhost:8080"
               :credentials "GERRIT_TOKEN"}}
        (:options workflow)))))

(deftest an-implement-workflow-delivers-to-the-project-and-follows-its-ci
  (let [{:keys [workflow]}
        (resolved {:project "demo-service"
                   :activity "implement"
                   :options {:guidelines_file "context/coding-guidelines.md"
                             :ci {:on_failure "analyse"}}})]
    (is
     (= {:guidelines_file "context/coding-guidelines.md"
         :repository {:url "http://gerrit:8080/a/demo-service" :branch "main" :credentials "GERRIT_TOKEN"}
         :pull_request {:provider "gerrit"
                        :repository "demo-service"
                        :url "http://gerrit:8080"
                        :web_url "http://gerrit.localhost:8080"}
         :ci {:mcp_server "jenkins" :job "demo-service-verify" :on_failure "analyse"}}
        (:options workflow)))))

(deftest what-a-workflow-sets-itself-wins
  (let [{:keys [workflow]}
        (resolved {:project "app"
                   :activity "implement"
                   :options {:repository {:url "file:///repos/app"}}})]
    (is
     (= {:repository {:url "file:///repos/app"}
         :pull_request {:provider "github" :repository "acme/app"}}
        (:options workflow))))
  (testing "a review with its own repository reviews branches, not the project's pull requests"
    (is
     (= {:repository {:url "file:///repos/app"}}
        (:options (:workflow (resolved {:project "app"
                                        :activity "review"
                                        :options {:repository {:url "file:///repos/app"}}})))))))

(deftest sessions-and-analyses-check-out-the-project
  (is
   (= {:repository {:url "https://github.com/acme/app.git" :branch "main" :credentials "GITHUB_TOKEN"}}
      (:options (:workflow (resolved {:project "app" :activity "session"}))))))

(deftest unknown-projects-and-workflows-without-one
  (is
   (= ["project nope is not in the team's team.json projects"]
      (:errors (resolved {:project "nope" :activity "review"}))))
  (is
   (= {:workflow {:activity "review" :options {:x 1}} :errors []}
      (resolved {:activity "review" :options {:x 1}}))))

(deftest scm-sources-are-checked-per-provider
  (is
   (= []
      (:errors (spec/validate (spec/scm-source) {:provider "gerrit" :repository "demo-service"
                                                 :url "http://gerrit:8080" :credentials "GERRIT_TOKEN"}))))
  (is
   (= ["file: gerrit is self-hosted: set url"]
      (:errors (spec/validate (spec/scm-source) {:provider "gerrit" :repository "demo-service" :credentials "X"}))))
  (is
   (= ["file: repository should be <workspace>/<repository>"]
      (:errors (spec/validate (spec/scm-source) {:provider "github" :repository "app" :credentials "X"}))))
  (is
   (= "gerrit"
      (spec/scm-api-host {:provider :gerrit :url "http://gerrit:8080"})))
  (is
   (= "api.github.com"
      (spec/scm-api-host {:provider :github}))))
