(ns manager-web.components.workflows-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.workflows :as workflows]
            [manager-web.roles :as roles]))

(def dialog
  {:team-id "demo" :workflow {:id "workspace"} :inputs {:title "x"}})

(deftest a-team-session-is-started-by-the-platform-for-the-team
  (let [run-id
        (random-uuid)

        spec
        (workflows/submit-spec (assoc dialog :team-session? true :visibility :tenant) run-id)]
    (is
     (= {:cmd-id :start-team-session
         :id run-id
         :attrs {:team-id "demo" :workflow-id "workspace" :inputs {:title "x"} :visibility :tenant}}
        (:command spec)))
    (is
     (= [:run {:run-id (str run-id)}]
        (:navigate spec)))))

(deftest team-sessions-are-shared-with-the-team-by-default
  (is
   (= :team
      (get-in (workflows/submit-spec (assoc dialog :team-session? true) (random-uuid)) [:command :attrs :visibility]))))

(deftest other-runs-are-the-person's-own
  (is
   (= :start-run
      (get-in (workflows/submit-spec dialog (random-uuid)) [:command :cmd-id]))))

(deftest a-run-says-who-sees-its-session
  (is
   (= :private
      (get-in (workflows/submit-spec (assoc dialog :visibility :private) (random-uuid)) [:command :attrs :visibility]))))

(deftest the-default-sharing-is-the-service's
  (let [interactive {:interactive true}
        automated {:interactive false}
        team {:spec {:visibility "team"}}
        tenant {:spec {:visibility "tenant"}}]
    (is
     (= {:interactive :private :interactive-tenant :tenant :automated :team :automated-tenant :tenant
         :team-session :team :team-session-tenant :tenant}
        {:interactive (workflows/default-visibility team interactive false)
         :interactive-tenant (workflows/default-visibility tenant interactive false)
         :automated (workflows/default-visibility {:spec {}} automated false)
         :automated-tenant (workflows/default-visibility tenant automated false)
         :team-session (workflows/default-visibility team interactive true)
         :team-session-tenant (workflows/default-visibility tenant interactive true)}))
    (is
     (= [[:private :team :tenant] [:team :tenant]]
        [(roles/visibility-options false) (roles/visibility-options true)]))))

(deftest only-the-open-dialog-takes-a-reply
  (let [dialog
        (workflows/opened {:team-id "ops" :workflow {:id "review"} :activity {:interactive false}} 2)]
    (is
     (= [{:id 7}]
        (:pull-requests (workflows/answered dialog 2 :pull-requests [{:id 7}]))))
    (is
     (= []
        (:pull-requests (workflows/answered dialog 1 :pull-requests [{:id 7}])))
     "a reply to an earlier dialog")
    (is
     (nil? (workflows/answered nil 2 :pull-requests [{:id 7}]))
     "a reply after the dialog closed")))

(deftest pull-requests-are-named-by-the-workflows-provider
  (is
   (= ["gerrit" "github" "bitbucket" nil]
      (mapv workflows/scm-provider [{:spec {:options {:scm {:provider "gerrit"}}}}
                                    {:spec {:options {:pull-request {:provider "github"}}}}
                                    {:spec {:trigger {:type "pull_request" :provider "bitbucket"}}}
                                    {:spec {}}]))))

(deftest a-refused-start-reads-the-credentials-again
  (is
   (= [[:my-credentials] [:my-credentials]]
      [(:refetch-on-failure (workflows/start-run-spec {:team-id "ops" :workflow {:id "w"}} 1))
       (:refetch-on-failure (workflows/start-team-session-spec {:team-id "ops" :workflow {:id "w"}} 1))])))

(def me
  {:tenant "acme" :sub "bob" :teams [{:id "ops" :name "Operations" :role :admin :claimed-role :admin}]})

(deftest the-dialog-says-whose-credentials-run
  (is
   (= "You (bob). Credentials: your own where set, else Operations', else acme's."
      (workflows/runs-as-text me "ops" false)))
  (is
   (= "Team Operations. Credentials: Operations', else acme's; never yours."
      (workflows/runs-as-text me "ops" true)))
  (is
   (= "Operations (ops) · you are an admin"
      (workflows/team-text me "ops"))))

(deftest missing-credentials-of-the-workflow's-harness
  (let [harnesses
        [{:team-id "ops" :id "tools" :credentials [{:name "Jira" :source nil} {:name "Bitbucket" :source "tenant"}]}
         {:team-id "demo" :id "tools" :credentials [{:name "Nexus"}]}]]
    (is
     (= ["Jira"]
        (workflows/missing-credentials harnesses "ops" {:spec {:harness "tools"}})))
    (is
     (empty? (workflows/missing-credentials harnesses "ops" {:spec {:harness "other"}})))))

(deftest admins-who-are-no-members-learn-where-a-run-goes
  (is
   (re-find #"Runs in Demo team as you"
            (workflows/start-tooltip {:tenant "acme" :tenant-admin true
                                      :teams [{:id "demo" :name "Demo team" :role :admin :claimed-role nil}]}
                                     "demo" false)))
  (is
   (= ""
      (workflows/start-tooltip me "ops" false))))

(deftest the-dialog-says-what-starting-does
  (let [workspace
        {:spec {:activity "session"}}

        review
        {:spec {:activity "review"}}]
    (is
     (= [true true false]
        [(workflows/interactive? workspace {:interactive true})
         (workflows/interactive? workspace nil)
         (workflows/interactive? review {:interactive false})]))
    (is
     (re-find #"^Starts an interactive session: " (workflows/dialog-intro {:workflow workspace :activity {:interactive true}})))
    (is
     (re-find #"^Starts a run: .*reports a result" (workflows/dialog-intro {:workflow review :activity {:interactive false}})))
    (is
     (re-find #"belongs to the team" (workflows/dialog-intro {:team-session? true :workflow workspace})))))
