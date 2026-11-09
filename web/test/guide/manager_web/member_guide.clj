(ns manager-web.member-guide
  "The guide for team members: henry's first login with required credentials,
   then bob's day: start a workflow, follow it, work in a session, share it."
  (:require [clojure.string :as string]
            [manager-web.accounts.elements :as accounts-els]
            [manager-web.activities.elements :as activities-els]
            [manager-web.components.elements :as component-els]
            [manager-web.guide-doc :as doc :refer [chapter! section! p! steps! bullets! table! tip! note! figure!]]
            [manager-web.guide-seed :as seed]
            [manager-web.guide-support :as s]
            [manager-web.harnesses.elements :as harnesses-els]
            [manager-web.helpers :as h]
            [manager-web.home.elements :as home-els]
            [manager-web.login.elements :as login-els]
            [manager-web.projects.elements :as projects-els]
            [manager-web.run.elements :as run-els]
            [manager-web.runs.elements :as runs-els]
            [manager-web.search.elements :as search-els]
            [manager-web.session.elements :as session-els]
            [manager-web.sessions.elements :as sessions-els]
            [manager-web.setup.elements :as setup-els]
            [manager-web.shell.elements :as shell-els]
            [testing.pw :as pw])
  (:import [com.microsoft.playwright Locator$WaitForOptions Page]))

(def guide
  {:title "For team members"
   :file "member.md"
   :prefix "m"
   :lead (str "This guide is for everyone who works in a team on the Agent Platform. It takes you from your first "
              "sign-in to a finished piece of work: you connect your accounts, start one of your team's workflows, "
              "follow the run to its result, work in the sandboxed session behind it and decide who else may see it. "
              "The screenshots show the example tenant **acme**: the first ones follow Henry, who is new in the "
              "Operations team, the rest follow Bob, a member of the **Demo team**. Your teams and workflows have "
              "other names, the pages are the same.")})

(def newcomer
  (:required-member h/fixture))

(def member
  (:member h/fixture))

(def team
  (:team h/fixture))

(def jira
  (:team-required-credential h/fixture))

(def session-name
  "README usage example")

(def renamed-session
  "Tidy up the README")

(def session-prompt
  "Add a short usage example to the README, then commit and push it.")

(def brief
  (str "Customers ask for a CSV export of their monthly invoices from the billing page. "
       "Keep it short: list the requirements and the open questions."))

(def state
  "What the guide started and has to clean up."
  (atom {}))

(defn- remove-own-credential!
  [credential-id]
  (h/wait-visible (accounts-els/credential credential-id))
  (when (h/visible? (accounts-els/remove-credential credential-id))
    (h/click (accounts-els/remove-credential credential-id))
    (s/confirm!)
    (h/wait-hidden (accounts-els/remove-credential credential-id))))

(defn- newcomer-without-jira!
  "henry with every required credential but Jira, signed out: what a new
   member of ops finds on the first login."
  []
  (s/as! newcomer)
  (s/goto "/accounts")
  (remove-own-credential! jira)
  (h/wait-visible setup-els/page)
  (s/sign-out))

(def sign-in-caption
  "The login page. **Sign in** takes you to your organisation's login.")

(def provider-caption
  (str "Your identity provider's login page (here Keycloak; yours may look different). After signing in "
       "there you are sent back to the platform."))

(defn- provider-login-figures
  []
  (h/goto "/login")
  (h/wait-visible login-els/sign-in)
  (s/settle)
  (figure! "m01-sign-in"
           sign-in-caption
           {:dialog true
            :pad 40
            :marks [{:target login-els/sign-in :label "Continues with your company account" :side :bottom :color :green}]})
  (h/click login-els/sign-in)
  (.waitFor (h/keycloak-locator :username) (doto (Locator$WaitForOptions.) (.setTimeout 30000.0)))
  (.fill (h/keycloak-locator :username) newcomer)
  (.fill (h/keycloak-locator :password) newcomer)
  (s/settle)
  (figure! "m02-identity-provider"
           provider-caption
           {:pad 130
            :marks [{:target (h/keycloak-locator :username) :label "Your company user name" :side :right :color :blue}
                    {:target (h/keycloak-locator :submit) :label "Sends you back to the platform" :side :right :color :green}]}))

(defn- signing-in
  []
  (chapter! "Signing in"
            (str "You sign in with your company account. The platform has no passwords of its own: your "
                 "organisation's identity provider says who you are, which tenant you belong to and which teams "
                 "you are in."))
  (steps! "Open the platform's address in your browser."
          "Select **Sign in**."
          "Enter your company user name and password on your identity provider's page."
          (str "You land on **Home** or, the first time, on the setup page described in "
               "[Connect your accounts](#connect-your-accounts)."))
  (if (h/oidc-mode?)
    (provider-login-figures)
    (do
      (println "  [guide] WARNING: the stack is not in oidc mode; the sign-in figures are kept from the last run")
      (doc/published-figure! "m01-sign-in" sign-in-caption)
      (doc/published-figure! "m02-identity-provider" provider-caption)))
  (p! (str "What you may see and do comes from your account: your tenant, your teams and whether you are a team "
           "admin. If a team is missing, ask whoever manages groups in your identity provider; nobody adds "
           "members inside the platform."))
  (if (h/oidc-mode?)
    (.click (h/keycloak-locator :submit))
    (h/submit-login newcomer)))

(defn- connect-accounts
  []
  (chapter! "Connect your accounts"
            (str "Agents call tools such as Jira, Bitbucket or Gerrit with **credentials**. Some come from your "
                 "tenant or team; others you add yourself, so that tools act as you. The platform stores your "
                 "value encrypted and adds it to requests at its gateway: an agent never sees it."))
  (section! "The first login: credentials your team requires"
            (str "When your tenant or team **requires** a credential of every member, the platform holds you on a "
                 "setup page until you have added it. Until then no other page opens."))
  (steps! "Read the step's help text: it says where to create the token."
          (str "Enter the user name (if asked for) and the token, then select **Save …**, or **Check and save …** "
               "when the platform tests the credential against its service first.")
          (str "Go on to the next step. Optional credentials can be skipped; select **Do this later** when only "
               "optional ones are left."))
  (h/wait-visible setup-els/page 30000)
  (h/wait-visible (setup-els/step jira))
  (s/settle)
  (figure! "m03-setup-required"
           (str "Setup after the first login. The step says who requires the credential, and **Do this later** "
                "stays disabled until every required one is added.")
           {:ctx setup-els/page
            :marks [{:target (setup-els/step jira) :label "Required by your team" :side :right :color :red}
                    {:target component-els/credential-secret :label "Your API token" :side :right :color :blue}
                    {:target setup-els/later :label "Disabled while one is still required" :side :right :color :orange}]})
  (h/fill component-els/credential-username "henry@example.com")
  (h/fill component-els/credential-secret "guide-placeholder-value")
  (h/click component-els/credential-submit)
  (h/wait-until "the app or a way out of setup" 30000 200
                #(some (fn [element] (when (h/enabled? element) element))
                       [shell-els/user-name setup-els/later setup-els/continue]))
  (s/settle)
  (when (h/visible? setup-els/page)
    (figure! "m04-setup-optional"
             (str "With the required credential saved, the rest is optional. **Do this later** opens the app; you "
                  "can add the others any time under **Accounts**.")
             {:ctx setup-els/page
              :marks [{:target (h/wait-any setup-els/later setup-els/continue)
                       :label "Opens the app" :side :right :color :green}]})
    (h/click (h/wait-any setup-els/later setup-els/continue)))
  (h/wait-visible shell-els/user-name)
  (p! (str "Only your **own** value satisfies a required credential. A value your team or tenant has set is still "
           "used for calls, but it doesn't open the app for you. If you are signed in as the wrong person, the "
           "setup page says who is signed in and has a **Sign out** link."))
  (section! "Later: the Accounts page"
            (str "**Accounts** in the sidebar lists every credential your teams' tools use and whose value is used "
                 "for you right now."))
  (s/as! member)
  (s/goto "/accounts")
  (h/wait-visible accounts-els/credentials)
  (steps! "Open **Accounts**."
          "Select **Add yours** next to the credential (or **Replace** when you already have one)."
          "Enter the token, and the user name where the credential has one, and save."
          "To go back to the team's or tenant's value, select **Remove**.")
  (figure! "m05-accounts"
           (str "Accounts. Each credential shows whose value is used (yours, your team's or the tenant's) and, "
                "underneath, the same per team.")
           {:ctx accounts-els/page
            :marks [{:target (accounts-els/resolution "BITBUCKET_TOKEN")
                     :label "The tenant's value is used" :side :right :color :blue}
                    {:target (accounts-els/needs-own jira)
                     :label "Only you can provide this one" :side :right :color :orange}
                    {:target (accounts-els/edit jira) :label "Opens the dialog below" :side :left :color :green}]})
  (p! "A value is looked up from the most specific owner to the least:")
  (table! ["Label" "Meaning"]
          ["**Your value**" "You added your own. Tools act as you."]
          ["**Team value**" "Your team admin set one for the whole team."]
          ["**Tenant value**" "The tenant admin set one for everyone."]
          ["**Missing**" "Nobody provided one. Workflows whose tools need it can't use those tools."]
          ["**Needs your credential**" "Only people provide this one; there is no shared value to fall back to."]
          ["**Set by admins**" "Only team or tenant admins provide it; ask yours."])
  (h/click (accounts-els/edit jira))
  (h/wait-visible component-els/credential-dialog)
  (h/fill component-els/credential-username "bob@example.com")
  (h/fill component-els/credential-secret "guide-placeholder-value")
  (s/settle)
  (figure! "m06-add-credential"
           (str "Adding your own value. It is stored encrypted and never shown again; later you can replace or "
                "remove it.")
           {:dialog true
            :pad 28
            :marks [{:target component-els/credential-secret :label "Paste the token" :side :right :color :blue}
                    {:target component-els/credential-submit :label "Stores it encrypted" :side :right :color :green}]})
  (h/click component-els/credential-cancel)
  (h/wait-hidden component-els/credential-dialog)
  (p! (str "Some credentials are tested against their service when you save them. If the test fails, the dialog "
           "shows the service's answer and nothing is stored.")))

(defn- finding-your-way
  []
  (chapter! "Finding your way"
            (str "The sidebar is the same on every page: your tenant, the search, the team you are looking at, "
                 "the pages, and who you are."))
  (s/goto "")
  (h/wait-visible home-els/start-section)
  (s/settle)
  (figure! "m07-sidebar"
           "The sidebar every page has."
           {:scroll false
            :pad [8 16 8 0]
            :marks [{:target shell-els/brand-tenant :label "Your tenant" :side :right :color :orange :distance 150}
                    {:target search-els/open :label "Search everything (Ctrl+K)" :side :right :color :purple}
                    {:target component-els/team-context-box :label "The team you are looking at" :side :right :color :blue}
                    {:around [(shell-els/nav-item :home) (shell-els/nav-item :accounts)] :label "The pages" :side :right :color :green}
                    {:around [shell-els/user-name shell-els/user-role shell-els/logout]
                     :label "You, your role and the sign-out button" :side :right :color :red}]})
  (table! ["Page" "What it is for"]
          ["**Home**" "Start a workflow, see what is running and your latest runs."]
          ["**Projects**" "The repositories your teams work on, each with its workflows, runs, changes and builds."]
          ["**Runs**" "Every run you may see, newest first."]
          ["**Sessions**" "The sandboxes behind runs: yours, your teams' and those shared with the tenant."]
          ["**Webhooks**" "Endpoints that start workflows from other systems (managed by admins)."]
          ["**Teams**" "Your teams: their workflows, agents, settings and usage."]
          ["**Harnesses**, **Activities**" "What an agent may use, and the kinds of work a workflow can do."]
          ["**Accounts**" "Your credentials."])
  (section! "Switch team"
            (str "If you are in more than one team, the **Team** box narrows Home, Projects, Runs, Sessions and "
                 "Harnesses to one of them. The choice travels in the address (`?team=demo`), so a link you share "
                 "opens the same view."))
  (s/open-select component-els/team-context (component-els/team-context-option team))
  (figure! "m08-team-switcher"
           "The team switcher lists your teams; **All my teams** shows everything again."
           {:ctx component-els/team-context-box
            :pad [24 24 24 8]
            :marks [{:target (component-els/team-context-option team) :label "Shows only this team" :side :right :color :blue}]})
  (h/click (component-els/team-context-option team))
  (s/settle)
  (section! "Search"
            (str "Press **Ctrl+K** (**Cmd+K** on a Mac) anywhere, or select the search box, and type a few "
                 "letters. Results are grouped into projects, teams and sessions you may see. Arrow keys move, "
                 "Enter opens."))
  (h/click search-els/open)
  (h/wait-visible search-els/dialog)
  (h/fill search-els/input "demo")
  (h/wait-visible search-els/results)
  (s/settle 800)
  (figure! "m09-search"
           "Search finds projects, teams and sessions by name."
           {:ctx search-els/results
            :pad 24
            :marks [{:target search-els/input :label "Type a name" :side :right :color :blue}]})
  (s/close-menu)
  (h/wait-hidden search-els/dialog)
  (section! "Projects"
            (str "A **project** is a repository a team works on. Its page gathers the workflows made for it, "
                 "their runs and sessions, the open changes (pull requests) and the latest builds."))
  (s/goto "/projects")
  (h/wait-visible projects-els/grid)
  (figure! "m10-projects"
           "Projects of your teams. Select one to see everything that happens around that repository."
           {:pad 30
            :marks [{:target (projects-els/card team "demo-service") :label "Opens the project" :side :right :color :blue}]}))

(defn- open-start-dialog
  [workflow-id]
  (h/click (component-els/workflow-start team workflow-id))
  (h/wait-visible component-els/run-dialog)
  (s/settle))

(defn- submit-run!
  "Starts what the open dialog describes; the id of the run page it opens."
  []
  (let [before
        (h/current-path)]
    (h/click component-els/run-dialog-submit)
    (h/wait-until "the run page" 60000 200
                  #(cond
                     (h/visible? component-els/run-dialog-error)
                     (throw (ex-info (str "The run did not start: " (h/text component-els/run-dialog-error)) {}))

                     (not= before (h/current-path))
                     true))
    (h/wait-visible run-els/page)
    (h/path-id)))

(defn- starting-a-workflow
  []
  (chapter! "Starting a workflow"
            (str "A **workflow** is a piece of work your team has prepared for an agent: review a change, "
                 "implement something, analyse a problem, or open a workspace to work in yourself. Your team "
                 "defines them; you start them from **Home**, the team's page or a project."))
  (s/goto (str "?team=" team))
  (h/wait-visible (component-els/workflow-start team (:analysis-workflow h/fixture)))
  (figure! "m11-start-something"
           (str "**Start something** on Home. Each row says what kind of work it is and how it starts; **Run** "
                "(or **Start session** for a workspace) opens the start dialog.")
           {:ctx home-els/start-section
            :marks [{:target (component-els/workflow-start team (:analysis-workflow h/fixture))
                     :label "Opens the start dialog" :side :left :color :green}]})
  (steps! "Find the workflow under **Start something** and select **Run**."
          (str "Check the summary: the **Team** the run belongs to (and whose budget it uses), **Runs as**, which "
               "is you with your credentials, and **Shared with**, who may see the agent's session.")
          "Fill in the inputs. Each workflow asks for its own; required ones are marked."
          "Select **Run**. The run's page opens at once.")
  (open-start-dialog (:analysis-workflow h/fixture))
  (h/fill (component-els/schema-field "brief") brief)
  (s/settle)
  (figure! "m12-start-dialog"
           (str "The start dialog. Above the inputs it says where the work happens, whose credentials the agent "
                "uses and who will see the session.")
           {:dialog true
            :pad 24
            :marks [{:target component-els/run-dialog-context :label "Team, credentials, shared with" :side :left :color :blue}
                    {:target (component-els/schema-field "brief") :label "What the workflow asks for" :side :left :color :purple}
                    {:target component-els/run-dialog-submit :label "Starts the run" :side :right :color :green}]})
  (p! (str "\"Runs as you\" means tools use your own credential where you have added one, then your team's, then "
           "the tenant's. If a credential the team requires of you is missing, the dialog says which one and "
           "links to **Accounts** instead of starting."))
  (let [run-id
        (submit-run!)]
    (s/created! :runs run-id)
    (swap! state assoc :run-id run-id)))

(defn- open-run!
  [run-id]
  (h/goto (str "/" s/tenant "/runs/" run-id))
  (h/wait-visible run-els/page)
  (h/wait-visible run-els/result 30000)
  (s/settle 1500))

(defn- review-result-figure
  []
  (p! (str "A **code review** starts with the verdict and a summary. Each finding has a severity, the file and "
           "line it is about, an explanation and, where the agent has one, the replacement it suggests."))
  (open-run! seed/branch-review)
  (figure! "m15-review-result"
           (str "A finished review of a branch: the verdict, then the findings, most severe first. This workflow "
                "posts nothing; a review of a pull request can post the same findings as comments.")
           {:ctx run-els/result
            :pad [24 24 24 24]
            :max-height 900
            :marks [{:target component-els/review-verdict :label "The verdict and the summary" :side :top :color :orange :distance 16}]}))

(defn- implementation-result-figure
  []
  (p! (str "An **implementation** shows what the agent changed and how it checked it, then the change itself as "
           "a patch you can download. A workflow with a project also delivers the change: the run links to the "
           "pull request or change it opened and follows its build."))
  (open-run! seed/passing-change)
  (h/wait-visible component-els/patch-view)
  (figure! "m16-implementation-result"
           (str "A finished implementation: the change it opened for review, the summary and the tests the "
                "agent ran, and the change as a patch. Further down, the page shows the build that checked it.")
           {:ctx run-els/result
            :pad [70 24 24 24]
            :max-height 1040
            :marks [{:target run-els/pull-request-link :label "The change it opened for review" :side :top :color :blue :distance 70}
                    {:target component-els/patch-view :label "What the agent changed" :side :top :color :purple :distance 16}]}))

(defn- run-result-figures
  []
  (h/goto (str "/" s/tenant "/runs/" (:run-id @state)))
  (h/wait-visible run-els/page)
  (let [status
        (h/wait-run-finished run-els/status h/run-timeout-ms)]
    (when (not= :completed status)
      (throw (ex-info (str "The guide's analysis run ended " (name status)) {})))
    (h/wait-visible run-els/result 30000)
    (s/settle 3000)
    (if (< 200 (count (h/text run-els/result)))
      (figure! "m14-run-result"
               (str "A finished analysis. The result is the document the agent wrote; **Open session** shows how "
                    "it got there and **Run again** starts the same workflow with the same inputs.")
               {:ctx run-els/page
                :scroll false
                :max-height 760
                :marks [{:target run-els/result :label "The result" :side :top :color :blue :distance 16}
                        {:target run-els/open-session :label "Shows what the agent did" :side :left :color :green}
                        {:target run-els/retry :label "Same inputs, new run" :side :bottom :color :purple}]})
      (do
        (println "  [guide] WARNING: the run page shows an empty result; figure m14-run-result left out")
        (figure! "m14-run-finished"
                 (str "A finished run. **Open session** shows how the agent got to its result and **Run again** "
                      "starts the same workflow with the same inputs.")
                 {:ctx run-els/page
                  :scroll false
                  :max-height 200
                  :marks [{:target run-els/open-session :label "Shows what the agent did" :side :bottom :color :green :distance 24}
                          {:target run-els/retry :label "Same inputs, new run" :side :bottom :color :purple :distance 60}]})))
    (table! ["Workflow kind" "What you get"]
            ["Analysis" "A document, shown on the run page."]
            ["Implementation" "A patch with a summary and test results; with a project, a pull request or change."]
            ["Code review" "The findings and verdict, also posted to the pull request when the workflow says so."]
            ["Workspace" "No result: a session you work in yourself."])
    (when (s/query :get-run :run-id (str "#" seed/branch-review))
      (review-result-figure)
      (implementation-result-figure))))

(defn- following-a-run
  []
  (chapter! "Following a run to its result"
            (str "Every start creates a **run**. Its page shows where it is, what you gave it and, at the end, "
                 "the result. You can leave the page: the run goes on, and you find it again under **Runs**."))
  (steps! "Watch the progress steps, or come back later through **Runs**."
          "Select **Cancel run** if you started the wrong thing; the sandbox is stopped."
          "When the run is **Completed**, read the result on the same page.")
  (h/wait-visible run-els/status)
  (h/wait-visible run-els/cancel 60000)
  (s/settle)
  (figure! "m13-run-running"
           (str "A run right after its start. The progress steps update by themselves; the breadcrumbs lead "
                "back to the team.")
           {:ctx run-els/page
            :scroll false
            :max-height 520
            :marks [{:target run-els/progress :label "Where the run is" :side :bottom :color :blue :distance 16}
                    {:target run-els/cancel :label "Stops the run" :side :left :color :red}]})
  (p! "While a run is under way the page shows these steps, and the chip under the title names the current one:")
  (table! ["Step" "Meaning"]
          ["**Queued**" "The run waits for a sandbox."]
          ["**Starting sandbox**" "The container is being prepared."]
          ["**Agent working**" "The agent is doing the work."]
          ["**Collecting result**" "The agent has finished and the platform reads what it produced."]
          ["**Following build**" "Only for workflows that deliver a change: the platform waits for its build."]
          ["**Done**" "The run is over. A workspace shows **Ready** instead, when its session can be opened."])
  (p! (str "A run that is over shows one of three chips: **Completed** (the result is on the page), **Failed** "
           "(the page shows the reason) or **Cancelled** (somebody stopped it)."))
  (swap! state assoc :result-slot (doc/slot!)))

(defn- session-id-of-run!
  []
  (h/wait-visible run-els/session-link h/session-timeout-ms)
  (h/click run-els/session-link)
  (h/wait-visible session-els/page)
  (h/path-id))

(defn- open-session-tab
  [tab]
  (s/tab! (session-els/tab tab)))

(defn- wait-audit-count
  "The audit tab shows its number of records only once the trail was read;
   false when it still shows none after timeout-ms."
  ([]
   (wait-audit-count h/session-timeout-ms))
  ([timeout-ms]
   (try
     (h/wait-until "the audit trail's records" timeout-ms 4000
                   #(do (open-session-tab :audit)
                        (h/wait-any component-els/audit-verification component-els/audit-empty component-els/audit-error)
                        (open-session-tab :overview)
                        (not (string/includes? (h/text (session-els/tab :audit)) "(0)"))))
     (catch clojure.lang.ExceptionInfo _
       false))))

(defn- audit-settled!
  "Waits until the session's audit trail has stopped growing: the collector
   writes the last records a little after the agent has finished."
  [session-id]
  (let [seen
        (atom [])]
    (h/wait-until "the audit trail to settle" h/session-timeout-ms 3000
                  #(let [records (:audit-events (s/query :get-session :session-id (str "#" session-id)))]
                     (swap! seen conj records)
                     (and (pos? (or records 0))
                          (<= 5 (count @seen))
                          (apply = (take-last 5 @seen)))))))

(defn- conversation-figure
  "The agent's answer to the session's first prompt, in OpenCode."
  []
  (open-session-tab :conversations)
  (h/wait-visible component-els/conversations-list h/session-timeout-ms)
  (let [conversation
        (h/wait-until "a conversation" h/session-timeout-ms 2000
                      #(first (filter :id (s/query :list-conversations :session-id (str "#" (:session-id @state))))))

        ^Page popup
        (.waitForPopup (s/page) ^Runnable #(h/click (component-els/conversation-open (:id conversation))))]
    (try
      (.setViewportSize popup 1200 760)
      (.waitForLoadState popup)
      (h/wait-until "the agent's reply in OpenCode" h/session-timeout-ms 3000
                    #(let [text (str (.textContent (.locator popup "body")))]
                       (and (string/includes? text "could not push")
                            (not (string/includes? text "Starting your session")))))
      (Thread/sleep 8000)
      (binding [pw/*current-page* popup]
        (figure! "m19-opencode"
                 (str "The session in OpenCode, in a browser tab of its own: what the agent did for the "
                      "first prompt, and its answer. You go on talking to the agent here; files, terminal and "
                      "tools all stay inside the sandbox.")
                 {:full true :scroll false}))
      (finally
        (.close popup))))
  (open-session-tab :overview))

(defn- working-in-a-session
  []
  (chapter! "Working in a session"
            (str "A **session** is one sandbox: a locked-down container with OpenCode, your team's agent and "
                 "tools, and a copy of the repository. Runs create one; a **workspace** workflow gives you one "
                 "to work in yourself."))
  (steps! "Select **Start session** on a workspace workflow, or **Open session** on a run."
          "Give it a title, and a first prompt if the agent should start working at once."
          "Set **Shared with**: only you, your team, or everyone in the tenant."
          "Select **Start session** and wait until the status is **Running**; the first start takes a moment."
          "Select **Open** to work in OpenCode in a new browser tab."
          "Come back to the session's page to stop, share, rename or delete it.")
  (s/goto (str "/" team))
  (open-start-dialog (:session-workflow h/fixture))
  (h/fill (component-els/schema-field "title") session-name)
  (h/fill (component-els/schema-field "prompt") session-prompt)
  (h/select-option component-els/run-dialog-visibility (component-els/run-dialog-visibility-option :private))
  (s/settle)
  (figure! "m17-start-session"
           (str "Starting a workspace. **Shared with** decides, before it starts, whether the session is only "
                "yours, your team's or visible to the whole tenant.")
           {:dialog true
            :pad 24
            :marks [{:target component-els/run-dialog-visibility :label "Only you, your team or the tenant" :side :left :color :blue}
                    {:target (component-els/schema-field "prompt") :label "The agent's first task" :side :left :color :purple}
                    {:target component-els/run-dialog-submit :label "Starts the session" :side :right :color :green}]})
  (submit-run!)
  (let [session-id
        (session-id-of-run!)]
    (swap! state assoc :session-id session-id)
    (s/created! :sessions session-id)
    (h/wait-status session-els/status :running h/session-timeout-ms)
    (audit-settled! session-id)
    (wait-audit-count)
    (s/settle 1000)
    (figure! "m18-session"
             (str "A session's page: status and sharing under the title, the actions on the right, and tabs for "
                  "its conversations, live activity and audit trail.")
             {:ctx session-els/page
              :scroll false
              :max-height 640
              :marks [{:target (component-els/session-action :open session-id)
                       :label "Opens OpenCode" :side :bottom :color :blue}
                      {:around [(component-els/session-action :fork session-id)
                                (component-els/session-action :stop session-id)
                                (component-els/session-action :lock session-id)
                                (component-els/session-action :delete session-id)]
                       :label "Fork, stop, lock, delete" :side :bottom :color :orange :distance 70}
                      {:target session-els/sharing :label "Shared with" :side :left :color :purple}]})
    (conversation-figure)
    (section! "See what the agent does"
              (str "**Live activity** shows whether the agent is busy, the tools it has called and a timeline, "
                   "without opening OpenCode. **Conversations** lists the chats in the session and opens each "
                   "in OpenCode."))
    (open-session-tab :activity)
    (h/wait-visible component-els/activity-timeline h/session-timeout-ms)
    (h/wait-visible component-els/activity-tools h/session-timeout-ms)
    (s/settle 1000)
    (figure! "m20-live-activity"
             (str "Live activity: the agent's state, the tools it called and how long they took, and a timeline. "
                  "The warning names a call the platform blocked: the agent tried `git push`, which this "
                  "workflow's harness denies.")
             {:ctx session-els/page
              :scroll false
              :max-height 760
              :marks (cond-> [{:target component-els/activity-tools :label "Tools the agent called" :side :bottom :color :purple :distance 16}]
                       (s/present? component-els/activity-blocked 5000)
                       (conj {:target component-els/activity-blocked :label "A call the platform blocked" :side :top :color :red :distance 16}))})
    (p! (str "A harness can deny commands and paths. A blocked call never runs: the agent is told why, and the "
             "session's **Live activity** and audit trail record the attempt."))
    (section! "The audit trail"
              (str "Every prompt, answer, tool call and LLM request of a session is recorded in an **audit "
                   "trail**. Each record carries a hash of the one before, so a change or a gap would show; the "
                   "page checks the chain and says so."))
    (open-session-tab :audit)
    (h/wait-visible component-els/audit-verification h/session-timeout-ms)
    (s/settle 1000)
    (figure! "m21-audit"
             "The audit trail. The banner confirms the chain is intact; the download gives you the records as JSON lines."
             {:ctx session-els/page
              :scroll false
              :max-height 640
              :marks [{:target component-els/audit-verification :label "Nothing altered or missing" :side :bottom :color :green :distance 16}
                      {:target component-els/audit-download :label "Downloads the records" :side :left :color :blue}]})
    (section! "Rename, stop, start, delete"
              "The actions at the top right of a session's page, and in the Sessions list, are these:")
    (table! ["Action" "What happens"]
            ["**Rename**" "Changes name and description. Only the owner can."]
            ["**Open**" "Opens the session's OpenCode in a new tab."]
            ["**Fork**" "Makes a private copy for you; see [Forking](#forking-a-session-you-cant-work-in)."]
            ["Stop (square icon)" "Stops the container and frees a place in the team's limit of running sessions. Files and conversations are kept."]
            ["Start (triangle icon)" "Starts a stopped session again, with everything where you left it."]
            ["Lock (padlock icon)" "Freezes the session for good; see [Locking](#locking-a-session)."]
            ["Delete (bin icon)" "Removes the session and its files. The audit trail of what happened is kept."])
    (open-session-tab :overview)
    (h/click session-els/rename)
    (h/wait-visible session-els/rename-dialog)
    (h/fill session-els/rename-name renamed-session)
    (h/fill session-els/rename-description "Usage example for the README, waiting for review.")
    (s/settle)
    (figure! "m22-rename"
             "A name and description that say what the session is for make it easy to find in lists and in search."
             {:dialog true
              :pad 24
              :marks [{:target session-els/rename-name :label "Shown in lists and search" :side :right :color :blue}
                      {:target session-els/rename-save :label "Saves the new name" :side :right :color :green}]})
    (h/click session-els/rename-save)
    (h/wait-hidden session-els/rename-dialog)
    (h/wait-until "the new title" 15000 #(= renamed-session (h/text session-els/title)))
    (p! (str "Sessions nobody has used for a while are stopped automatically (your team sets after how many "
             "minutes). Nothing is lost: starting them again brings everything back."))))

(defn- sharing
  []
  (chapter! "Sharing a session"
            (str "You decide who sees a session you own. Seeing is not the same as working in it: what others "
                 "may do depends on whose credentials the session works with."))
  (steps! "Open the session and find **Shared with** on its **Overview**."
          "Select **Only me**, your team or everyone in the tenant."
          "Confirm when you share with the whole tenant."
          "The chip under the title shows the choice; it is saved at once.")
  (s/open-select session-els/sharing (session-els/sharing-option :team))
  (figure! "m23-sharing"
           "**Shared with** on a session's page, as its owner sees it."
           {:ctx session-els/sharing
            :pad [80 24 170 24]
            :marks [{:target session-els/sharing :label "Only you, your team or the tenant" :side :top :color :purple :distance 24}]})
  (h/click (session-els/sharing-option :tenant))
  (h/wait-visible component-els/confirm-dialog)
  (s/settle)
  (figure! "m24-share-tenant"
           "Sharing with the whole tenant asks first: everyone in the tenant will be able to read the conversations."
           {:dialog true
            :pad [24 24 0 24]
            :marks [{:target component-els/confirm-accept :label "Shares it with everyone" :side :right :color :orange}
                    {:target component-els/confirm-keep :label "Leaves it as it is" :side :left :color :blue}]})
  (h/click component-els/confirm-keep)
  (h/wait-hidden component-els/confirm-dialog)
  (h/select-option session-els/sharing (session-els/sharing-option :team))
  (h/wait-hidden session-els/sharing-saving)
  (table! ["Shared with" "Who sees it and may open it"]
          ["**Only me**" "You. Your team's admins see that it exists and may fork it."]
          ["**Team …**" "The members of the session's team."]
          ["**Everyone in …**" "Everyone in your tenant, whatever their team."])
  (note! (str "A session that uses **your own** credential (say, your Bitbucket token) is never opened by "
              "anybody else, however widely it is shared: they would act as you. They can read it and "
              "**fork** it; the fork works with their credentials.")))

(defn- locking-a-session
  []
  (let [session-id
        (:session-id @state)]
    (chapter! "Locking a session"
              (str "**Locking** turns a session into a read-only record: it is stopped, nobody can work in it "
                   "any more, you included, and its agent can no longer call models or tools. Use it when the work is done "
                   "and should stay exactly as it is, for a review or a handover. It can't be undone."))
    (steps! "Open the session."
            "Select the padlock icon in its actions."
            "Read the confirmation and select **Lock session**."
            "The session shows **Locked** under its title and stops within a minute. Anyone who can see it can still read it and fork it.")
    (h/goto (str "/" s/tenant "/sessions/" session-id))
    (h/wait-visible session-els/page)
    (h/click (component-els/session-action :lock session-id))
    (h/wait-visible component-els/confirm-dialog)
    (s/settle)
    (figure! "m25-lock"
             "Locking asks first, because it can't be undone."
             {:dialog true
              :pad [24 24 0 24]
              :marks [{:target component-els/confirm-accept :label "Freezes it for good" :side :right :color :red}]})
    (h/click component-els/confirm-accept)
    (h/wait-visible session-els/locked)
    (swap! state assoc :locked-status
           (try
             (h/wait-status session-els/status :stopped 90000)
             (catch Exception _
               (keyword (.getAttribute (h/raw-locator session-els/status) "data-status")))))
    (println "  [guide] a locked session's status after 90 s:" (:locked-status @state))))

(defn- team-session-row
  "A session the platform owns that the person can see but not work in."
  []
  (first (filter #(and (:team-session %) (not= team (:team-id %)))
                 (s/query :list-sessions))))

(defn- waited-team-session-row
  "On a fresh stack the first scheduled team session takes a few minutes to appear."
  []
  (try
    (h/wait-until "a team session of another team" (* 8 60 1000) 10000 team-session-row)
    (catch clojure.lang.ExceptionInfo _
      (println "  [guide] WARNING: no team session of another team; figure m26-team-session left out")
      nil)))

(defn- forking
  []
  (chapter! "Forking a session you can't work in"
            (str "A **fork** is your own private copy of a session: its files and conversations up to that "
                 "moment, in your name, in the same team. Use it to continue from somebody else's work, from a "
                 "team session or from a locked one."))
  (steps! "Open the session (from **Sessions**, a run, or the search)."
          "Select **Fork** in its actions, or **Fork to continue** in the banner."
          "Confirm with **Fork session**. Your copy opens; it is private until you share it."
          "Work in the copy with **Open**.")
  (when-let [session
             (waited-team-session-row)]
    (h/goto (str "/" s/tenant "/sessions/" (:id session)))
    (h/wait-visible session-els/page)
    (h/wait-visible session-els/blocked)
    (wait-audit-count 20000)
    (s/settle)
    (figure! "m26-team-session"
             (str "A team session of a team you are not in. The banner says why you can't work in it and offers "
                  "the fork.")
             {:ctx session-els/page
              :scroll false
              :max-height 290
              :marks [{:target session-els/fork-to-continue :label "Makes your own copy" :side :top :color :green :distance 30}]}))
  (let [session-id
        (:session-id @state)]
    (h/goto (str "/" s/tenant "/sessions/" session-id))
    (h/wait-visible session-els/page)
    (h/wait-visible session-els/fork-to-continue)
    (wait-audit-count 30000)
    (s/settle)
    (figure! "m27-locked"
             (str "A locked session, marked **Locked** under its title: nobody works in it any more, not even its "
                  "owner. **Fork to continue** makes a copy you can work in.")
             {:ctx session-els/page
              :scroll false
              :max-height 250
              :marks [{:target session-els/locked :label "Read-only from now on" :side :top :color :red :distance 60}
                      {:target session-els/fork-to-continue :label "Makes your own copy" :side :top :color :green :distance 30}]})
    (h/click session-els/fork-to-continue)
    (h/wait-visible component-els/confirm-dialog)
    (s/settle)
    (figure! "m28-fork-confirm"
             "The fork is private and yours; the original stays as it is."
             {:dialog true
              :pad [24 24 0 24]
              :marks [{:target component-els/confirm-accept :label "Creates your copy" :side :right :color :green}]})
    (h/click component-els/confirm-keep)
    (h/wait-hidden component-els/confirm-dialog)
    (p! (str "A fork counts as one of the team's running sessions, and it needs the credentials the team "
             "requires of you, like anything else you start."))))

(defn- lists
  []
  (chapter! "Runs and Sessions"
            "Two lists hold everything you may see, in every team or in the one the team switcher is set to.")
  (s/goto "/runs")
  (h/wait-visible component-els/runs-table)
  (s/settle)
  (figure! "m29-runs"
           (str "Runs, newest first: what was started, by whom or what, in which team, and how it ended. "
                "**Show** narrows the list by status; the team switcher by team.")
           {:ctx component-els/runs-table
            :pad [190 24 24 24]
            :scroll false
            :max-height 760
            :marks [{:target runs-els/filter-select :label "Filter by status" :side :left :color :blue}
                    {:target (component-els/run-link (:run-id @state)) :label "Opens the run" :side :right :color :green}]})
  (s/goto "/sessions")
  (h/wait-visible component-els/sessions-table)
  (s/settle)
  (figure! "m30-sessions"
           (str "Sessions. **Owner** and **Shared with** say whose a session is and who sees it; the actions on "
                "the right are the ones you may take.")
           {:ctx component-els/sessions-table
            :scroll false
            :max-height 560
            :marks [{:around [(sessions-els/filter-option :all) (sessions-els/filter-option :mine) (sessions-els/filter-option :running)]
                     :label "All, only yours, or only running" :side :left :color :blue}]})
  (bullets! "**All**: yours, your teams' and those shared with the whole tenant."
            "**Mine**: sessions you own."
            "**Running**: sessions that hold a place in a team's limit right now."
            "A session owned by **Team …** was started by a schedule, a trigger or a team admin; it works with team credentials.")
  (tip! "Stop sessions you no longer need: every team has a limit of sessions running at the same time."))

(defn- library
  []
  (chapter! "What an agent may use: harnesses and activities"
            (str "An agent only gets what its **harness** grants: a model, tools, MCP servers, skills, network "
                 "hosts and credentials. Nothing else leaves the sandbox."))
  (s/goto "/harnesses")
  (h/wait-visible harnesses-els/grid)
  (s/settle)
  (let [[ready-team ready-harness]
        (:ready-harness h/fixture)]
    (figure! "m31-harnesses"
             (str "A harness on the **Harnesses** page: network access, model, skills, the tool server it may "
                  "call and the account that is used. The chip reads **Ready for you in …** when every "
                  "credential it needs has a value for you, **Needs setup** otherwise.")
             {:pad 30
              :marks [{:target (harnesses-els/harness ready-team ready-harness)
                       :label "A harness: what its agents may use" :side :right :color :blue}
                      {:target (harnesses-els/ready ready-team ready-harness)
                       :label "Its credentials have values for you" :side :top :color :green}]}))
  (p! (str "**Activities** are the four kinds of work the platform knows: workspace, code review, implementation "
           "and analysis. Each of your team's workflows is one activity with the team's own inputs, agent and "
           "harness. The page describes what each activity does and delivers."))
  (s/goto "/activities")
  (h/wait-visible activities-els/catalog)
  (s/settle)
  (figure! "m32-activities"
           "The activity catalogue: what each kind of workflow does, needs and returns."
           {:ctx activities-els/catalog :pad 24}))

(defn clean-up!
  "Deletes the sessions the guides started, as a tenant admin (who manages
   every team's sessions); henry keeps placeholder values for what ops requires."
  []
  (s/as! (:tenant-admin h/fixture))
  (when-let [run-id
             (:run-id @state)]
    (h/command! :cancel-run run-id))
  (s/delete-created-run-sessions!)
  (s/delete-triggered-sessions!)
  (s/delete-guide-sessions!)
  (s/as! newcomer))

(defn guide!
  []
  (reset! state {})
  (s/as! member)
  (s/ensure-change-run!)
  (newcomer-without-jira!)
  (signing-in)
  (connect-accounts)
  (finding-your-way)
  (starting-a-workflow)
  (following-a-run)
  (working-in-a-session)
  (sharing)
  (locking-a-session)
  (forking)
  (lists)
  (library)
  (doc/fill! (:result-slot @state) run-result-figures))
