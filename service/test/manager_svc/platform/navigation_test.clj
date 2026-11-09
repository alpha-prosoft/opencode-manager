(ns manager-svc.platform.navigation-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.platform.fixtures :as f]
            [manager-svc.platform.navigation :as navigation]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.test-support :as support]))

(def people
  {"dev" {:teams {"team" :member}}
   "root" {:tenant-admin true}
   "stranger" {}
   "oscar" {:tenant nil :operator true}
   "gina" {:tenant "globex" :tenant-admin true :teams {"team" :admin}}})

(def projects
  [{:id "app" :name "App" :branch "develop" :scm {:provider "github" :repository "acme/app" :credentials "GIT_TOKEN"}}
   {:id "review-tool" :scm {:provider "gerrit" :repository "tools/review" :url "http://gerrit:8080"
                            :web_url "http://gerrit.example"}
    :ci {:mcp_server "jenkins" :jobs ["review-verify"] :job "review-verify"}}])

(defn- built
  []
  (let [roots
        (support/temp-dir)]
    (f/build! roots
              (assoc (f/tenant-json roots :name "Platform team" :secrets ["GIT_TOKEN"])
                     :mcp_servers [{:id "jenkins" :name "Jenkins" :url "http://jenkins.internal/"}])
              {"team.json" {:projects projects}
               "agents/a.json" f/agent-spec
               "harnesses/h.json" f/harness
               "workflows/review-app.json" {:name "Review app" :activity "review" :agent "a" :harness "h" :project "app"}
               "workflows/broken.json" {:name "Broken" :activity "review" :agent "nope" :harness "h"
                                        :project "review-tool"}
               "workflows/analyse.json" {:name "Analyse" :activity "analysis" :agent "a" :harness "h"}})))

(defn- query
  [ctx username q]
  (mock/query (support/as-user ctx username (get people username {})) q))

(deftest projects-of-the-callers-teams
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)]
      (is
       (= [{:id "app"
            :name "App"
            :team-id "team"
            :team-name "Platform team"
            :branch "develop"
            :scm {:provider :github :repository "acme/app"}
            :repository-url "https://github.com/acme/app"
            :workflows 1}
           {:id "review-tool"
            :name "review-tool"
            :team-id "team"
            :team-name "Platform team"
            :branch "main"
            :scm {:provider :gerrit :repository "tools/review" :web-url "http://gerrit.example"}
            :repository-url "http://gerrit.example"
            :ci {:mcp-server "jenkins" :jobs ["review-verify"] :job "review-verify"}
            :workflows 1}]
          (query ctx "dev" {:query-id :list-projects})))
      (is
       (= ["app" "review-tool"]
          (mapv :id (query ctx "root" {:query-id :list-projects :team-id "team"}))))
      (testing "a tenant admin lists the projects of every team"
        (is
         (= ["app" "review-tool"]
            (mapv :id (query ctx "root" {:query-id :list-projects})))))
      (testing "a team's projects look the same wherever they are listed, without credential or clone URL"
        (let [listed
              (mapv #(dissoc % :workflows) (query ctx "dev" {:query-id :list-projects}))]
          (is
           (= listed
              (:projects (first (query ctx "dev" {:query-id :list-teams})))))
          (is
           (= listed
              (:projects (query ctx "dev" {:query-id :get-team :team-id "team"}))))))
      (testing "nobody sees the projects of teams they aren't in, or of another tenant"
        (is
         (= []
            (query ctx "stranger" {:query-id :list-projects})))
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"You're not in Platform team"
                           (query ctx "stranger" {:query-id :list-projects :team-id "team"})))
        (is
         (= []
            (query ctx "gina" {:query-id :list-projects})))))))

(deftest a-project-with-the-workflows-that-name-it
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)

          found
          (query ctx "dev" {:query-id :get-project :team-id "team" :project-id "app"})]
      (is
       (= {:tenant "acme"
           :team {:id "team" :name "Platform team" :role :member}
           :changes-workflow "review-app"}
          (select-keys found [:tenant :team :changes-workflow])))
      (is
       (= "https://github.com/acme/app"
          (get-in found [:project :repository-url])))
      (is
       (= [["review-app" true]]
          (mapv (juxt :id :valid) (:workflows found))))
      (is
       (some? (get-in found [:workflows 0 :inputs-schema])))
      (testing "an invalid workflow naming the project is shown, and delivers nothing"
        (let [tool
              (query ctx "dev" {:query-id :get-project :team-id "team" :project-id "review-tool"})]
          (is
           (= [["broken" false]]
              (mapv (juxt :id :valid) (:workflows tool))))
          (is
           (nil? (:changes-workflow tool)))))
      (testing "unknown projects, other people's teams and other tenants"
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"Project nope is not in Platform team"
                           (query ctx "dev" {:query-id :get-project :team-id "team" :project-id "nope"})))
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"You're not in Platform team"
                           (query ctx "stranger" {:query-id :get-project :team-id "team" :project-id "app"})))
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo #"Team not found"
                           (query ctx "gina" {:query-id :get-project :team-id "team" :project-id "app"})))))))

(deftest workflows-that-deliver-changes
  (is
   (= [true true true false false]
      (mapv navigation/delivers-changes?
            [{:valid true :spec {:activity "review"}}
             {:valid true :spec {:activity "implement"}}
             {:valid true :spec {:activity "analysis" :trigger {:type :pull-request}}}
             {:valid true :spec {:activity "analysis" :trigger {:type :manual}}}
             {:valid false :spec {:activity "review"}}]))))

(deftest ranking-puts-exact-and-prefix-matches-first
  (is
   (= [0 1 2 3 nil]
      (mapv #(navigation/match-rank "app" [%]) ["App" "apple" "my-app" "webapp" "web"])))
  (is
   (= 1
      (navigation/match-rank "rev" ["Tools" nil "review-tool"])))
  (is
   (= ["app" "apple" "Big apple" "webapp"]
      (navigation/ranked "APP" 10 vector ["webapp" "Big apple" "nothing" "apple" "app"])))
  (is
   (= ["app" "apple"]
      (navigation/ranked "app" 2 vector ["webapp" "apple" "app"])))
  (is
   (= []
      (navigation/ranked "  " 5 vector ["app"]))))

(def session-rows
  [{:id #uuid "00000000-0000-0000-0000-000000000001" :name "Review the app" :team-id "team" :project "app"
    :tenant "acme" :visibility :private :sub "dev"}
   {:id #uuid "00000000-0000-0000-0000-000000000002" :name "app notes" :team-id "team"
    :tenant "acme" :visibility :team :sub "root"}
   {:id #uuid "00000000-0000-0000-0000-000000000003" :name "Nightly triage" :team-id "team"
    :tenant "acme" :visibility :team :sub "platform"}])

(deftest search-finds-projects-teams-and-visible-sessions
  (mock/with-mock-dal
    (let [{:keys [ctx]}
          (built)

          asked
          (atom [])]
      (with-redefs [sessions/visible-rows (fn [_system caller]
                                            (swap! asked conj (:id caller))
                                            (filterv #(= "acme" (:tenant caller) (:tenant %)) session-rows))]
        (let [found
              (query ctx "dev" {:query-id :search :text "app"})]
          (is
           (= [["app" "team" "Platform team"]]
              (mapv (juxt :id :team-id :team-name) (:projects found))))
          (is
           (= []
              (:teams found)))
          (is
           (= [["app notes" false "Platform team"] ["Review the app" true "Platform team"]]
              (mapv (juxt :name :is-owner :team-name) (:sessions found)))))
        (is
         (= [{:id "team" :name "Platform team" :description "" :role :member :member true}]
            (:teams (query ctx "dev" {:query-id :search :text "PLATFORM"}))))
        (testing "teams of the tenant are found by people outside them, their projects are not"
          (let [found
                (query ctx "stranger" {:query-id :search :text "team"})]
            (is
             (= [["team" false]]
                (mapv (juxt :id :member) (:teams found))))
            (is
             (= []
                (:projects (query ctx "stranger" {:query-id :search :text "app"}))))))
        (testing "another tenant finds nothing of acme"
          (is
           (= {:projects [] :teams [] :sessions []}
              (query ctx "gina" {:query-id :search :text "a"}))))
        (testing "an empty text finds nothing and reads no sessions"
          (reset! asked [])
          (is
           (= {:projects [] :teams [] :sessions []}
              (query ctx "dev" {:query-id :search :text " "})))
          (is
           (= []
              @asked)))
        (is
         (= 1
            (count (:sessions (query ctx "dev" {:query-id :search :text "app" :limit 1})))))
        (testing "a match at the start of a word"
          (is
           (= ["review-tool"]
              (mapv :id (:projects (query ctx "dev" {:query-id :search :text "tool"}))))))
        (testing "a team session is the platform's and names no owner"
          (is
           (= [{:name "Nightly triage" :team-session true :owner nil :is-owner false}]
              (mapv #(select-keys % [:name :team-session :owner :is-owner])
                    (:sessions (query ctx "dev" {:query-id :search :text "nightly"})))))
          (is
           (= [{:team-session false :owner "root"}]
              (mapv #(select-keys % [:team-session :owner])
                    (:sessions (query ctx "dev" {:query-id :search :text "notes"}))))))
        (testing "an operator without a tenant finds nothing"
          (is
           (= {:projects [] :teams [] :sessions []}
              (query ctx "oscar" {:query-id :search :text "app"})))))
      (testing "projects and teams are still found when sessions can't be read"
        (with-redefs [sessions/visible-rows (fn [_system _caller] (throw (ex-info "sessions are down" {})))]
          (is
           (= {:projects ["app"] :teams [] :sessions []}
              (update (query ctx "dev" {:query-id :search :text "app"}) :projects #(mapv :id %)))))))))

(deftest the-limit-stays-within-bounds
  (is
   (= [navigation/default-limit 1 3 navigation/max-limit]
      (mapv navigation/clamped-limit [nil 0 3 99]))))
