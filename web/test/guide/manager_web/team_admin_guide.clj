(ns manager-web.team-admin-guide
  "The guide for team admins: frank administers the demo team and nothing else."
  (:require [clojure.string :as string]
            [manager-web.components.elements :as component-els]
            [manager-web.guide-doc :as doc :refer [chapter! section! p! steps! table! note! figure!]]
            [manager-web.guide-seed :as seed]
            [manager-web.guide-support :as s]
            [manager-web.helpers :as h]
            [manager-web.project.elements :as project-els]
            [manager-web.run.elements :as run-els]
            [manager-web.team.elements :as team-els]
            [manager-web.webhooks.elements :as webhook-els]))

(def guide
  {:title "For team admins"
   :file "team-admin.md"
   :prefix "ta"
   :lead (str "This guide is for the admins of a team. You look after what the team's agents may use and spend: "
              "its settings and limits, the credentials the team shares, team sessions, its projects and the "
              "webhooks that let other systems start its workflows. It assumes you know the "
              "[guide for team members](member.md). The screenshots show Frank, admin of the **Demo team** in the "
              "example tenant **acme**.")})

(def admin
  "frank")

(def team
  (:team h/fixture))

(def project
  "demo-service")

(def shared-credential
  (:admin-only-credential h/fixture))

(def webhook-name
  "Monitoring alerts")

(def example-secret
  "whsec_EXAMPLE_7f3a9c2e51d84b06")

(def good-body
  "{\"alert\": {\"summary\": \"Checkout latency has been above two seconds for 15 minutes\"}}")

(def state
  (atom {}))

(defn- team-tab!
  ([tab]
   (team-tab! team tab))
  ([team-id tab]
   (s/goto (str "/" team-id "?tab=" (name tab)))
   (h/wait-visible team-els/page)
   (h/wait-attribute (team-els/tab tab) "aria-selected" "true")
   (s/settle)))

(defn- clear-team-credential!
  []
  (h/command! :delete-team-credential nil
              (str "{\"team-id\":\"" team "\",\"credential-id\":\"" shared-credential "\"}")))

(defn- team-page
  []
  (chapter! "The team page"
            (str "Everything about a team is on its page: select **Teams** in the sidebar and then the team. The "
                 "chip at the top right says in which role you see it."))
  (team-tab! :workflows)
  (figure! "ta01-team-page"
           (str "The team's page. Under the name: the repository its definitions come from, the version in use "
                "and when it was last synced.")
           {:ctx team-els/page
            :scroll false
            :max-height 640
            :marks [{:target team-els/role :label "Your role in this team" :side :bottom :color :blue}
                    {:target (team-els/tab :settings) :label "Limits, credentials, requirements" :side :top :color :green :distance 30}
                    {:target (team-els/project-card team project) :label "A project of the team" :side :right :color :purple}]})
  (table! ["Tab" "What it shows"]
          ["**Workflows**" "The team's workflows, each with its start button. A workflow that can't be used shows why."]
          ["**Runs**" "The team's runs, newest first."]
          ["**Sessions**" "The team's sessions you may see, and **Start team session**."]
          ["**Agents & harnesses**" "Who does the work (agents) and what each sandbox may use (harnesses)."]
          ["**Usage**" "Tokens and cost by model."]
          ["**Webhooks**" "Endpoints other systems call to start the team's workflows."]
          ["**Team settings**" "Budget and limits, models, MCP servers, projects, required and team credentials."])
  (p! (str "Who is a member or an admin of the team is decided in your identity provider (its groups), not here. "
           "The platform reads it from each person's login.")))

(defn- team-settings
  []
  (chapter! "Team settings: budget, models and tools"
            (str "**Team settings** shows what the team has been given. The values come from the team's "
                 "`.platform/team.json` (see [Where the definitions live](#where-the-teams-definitions-live)); "
                 "the page shows them next to what is used right now."))
  (team-tab! :settings)
  (figure! "ta02-budget"
           (str "**Budget and limits**: tokens used today against the daily budget, sessions running against "
                "the limit, and after how many idle minutes a session is stopped.")
           {:ctx team-els/budget
            :pad [100 24 24 24]
            :marks [{:around [(team-els/limit :daily-tokens) (team-els/limit :running-sessions) (team-els/limit :idle-minutes)]
                     :label "What is used now, against each limit" :side :right :color :blue}]})
  (table! ["Limit" "What happens when it is reached"]
          ["**Tokens today**" "Agents of the team can't call the LLM any more until midnight UTC. Running sessions stay, but answer with an error."]
          ["**Running sessions**" "Nobody can start a run, a session or a fork in the team until one is stopped."]
          ["**Idle timeout**" "A session nobody used for this long is stopped. It can be started again."])
  (figure! "ta03-models-tools"
           "**Models** and **MCP servers** the team's harnesses may use, within what the tenant grants."
           {:ctx team-els/connections
            :pad 24
            :marks [{:target team-els/connections :label "LLM connections and models" :side :bottom :color :blue :distance 16}
                    {:target team-els/mcp-servers :label "Tool servers reached through the gateway" :side :bottom :color :green :distance 16}]})
  (section! "Credentials every member must add"
            (str "A team can require that each member adds their **own** value of a credential before working in "
                 "it, so that tools always act as the person. The tenant can require the same of everyone."))
  (steps! "Add the credential's id to `required_credentials` in the team's `.platform/team.json`."
          "Commit and push; the platform picks the change up with the next sync."
          "From then on a member who lacks their own value lands on setup at the next page they open.")
  (p! (str "Only credentials people may provide themselves can be required. A value of the team or the tenant "
           "doesn't satisfy the requirement: the point is that each person acts as themselves. Team settings "
           "lists what is required under **Required of every member**, and the member guide shows "
           "[what a member then sees](member.md#the-first-login-credentials-your-team-requires).")))

(defn- team-credentials
  []
  (chapter! "Team credentials"
            (str "A **team credential** is a value the whole team shares, used instead of the tenant's: a "
                 "service account for the team's repositories, for example. A member's own value still wins "
                 "over it."))
  (steps! "Open the team's **Team settings** and scroll to **Team credentials**."
          "Select **Set** next to the credential, enter the value and save."
          "To replace it, select **Replace**; **Clear** removes it after a confirmation.")
  (team-tab! :settings)
  (h/wait-visible (team-els/credential shared-credential))
  (figure! "ta04-team-credentials"
           (str "**Team credentials** at the bottom of Team settings. Each row says whose value the team uses "
                "now and who may provide one.")
           {:ctx team-els/credentials
            :pad [100 24 24 24]
            :marks [{:target (team-els/credential-state "BITBUCKET_TOKEN") :label "Falls back to the tenant" :side :right :color :blue}
                    {:target (team-els/credential-state shared-credential) :label "Nobody set one" :side :right :color :orange}
                    {:target (team-els/set-credential shared-credential) :label "Opens the dialog below" :side :left :color :green}]})
  (h/click (team-els/set-credential shared-credential))
  (h/wait-visible component-els/credential-dialog)
  (h/fill component-els/credential-secret "guide-placeholder-value")
  (s/settle)
  (figure! "ta05-set-team-credential"
           "Setting the team's value. It is stored encrypted for the team and never shown again."
           {:dialog true
            :pad 24
            :marks [{:target component-els/credential-secret :label "The shared token" :side :left :color :blue}
                    {:target component-els/credential-submit :label "Stores it for the team" :side :right :color :green}]})
  (h/click component-els/credential-submit)
  (h/wait-hidden component-els/credential-dialog 60000)
  (h/wait-until "the team value" 60000 250
                #(= "team" (s/state-of (team-els/credential-state shared-credential))))
  (s/settle)
  (figure! "ta06-team-value-set"
           "With a team value set, the row shows it and offers **Clear** to go back to the tenant's value."
           {:ctx (team-els/credential shared-credential)
            :pad 24
            :marks [{:target (team-els/credential-state shared-credential) :label "The team's value is used" :side :right :color :green}
                    {:target (team-els/clear-credential shared-credential) :label "Removes it again" :side :bottom :color :red :distance 24}]})
  (table! ["Provided by" "Meaning"]
          ["**Tenant admins**" "The tenant may hold a value for everyone."]
          ["**Team admins**" "You may set a value for your team."]
          ["**Each person**" "Members may add their own under **Accounts**; theirs wins."])
  (note! (str "Members see only whether a team value is set, never the value or its hint. Workflows started by a "
              "schedule, a trigger or a webhook run as the platform: they use team and tenant values, never "
              "anybody's personal one."))
  (h/click (team-els/clear-credential shared-credential))
  (s/confirm!)
  (h/wait-until "the team value to be gone" 60000 250
                #(not= "team" (s/state-of (team-els/credential-state shared-credential)))))

(defn- team-sessions
  []
  (chapter! "Starting a team session"
            (str "A **team session** belongs to the team, not to a person. It works with the team's credentials, "
                 "so every member may work in it and it survives people leaving. Use it for a shared workspace, "
                 "or for work that should not depend on one person's accounts."))
  (steps! "Open the team's **Sessions** tab."
          "Select **Start team session** and choose a workspace workflow."
          "Fill in the inputs, check **Shared with**, and select **Start team session**."
          "Members find it under **Sessions**, owned by **Team …**, and work in it with **Open**.")
  (team-tab! :sessions)
  (h/click team-els/start-team-session)
  (let [workflow
        (team-els/team-session-workflow (:session-workflow h/fixture))]
    (h/wait-visible workflow)
    (Thread/sleep 300)
    (figure! "ta07-start-team-session"
             "**Start team session** on the team's **Sessions** tab lists the workspace workflows of the team."
             {:pad [60 30 40 420]
              :marks [{:target team-els/start-team-session :label "Shown to team admins" :side :left :color :blue}
                      {:target workflow :label "The workspace to start" :side :left :color :green}]})
    (h/click workflow))
  (h/wait-visible component-els/run-dialog)
  (h/fill (component-els/schema-field "title") "Release check")
  (s/settle)
  (figure! "ta08-team-session-dialog"
           (str "The start dialog of a team session. **Runs as** names the team: the session uses the team's "
                "and the tenant's credentials, nobody's personal ones.")
           {:dialog true
            :pad 24
            :marks [{:target component-els/run-dialog-runs-as :label "The team's credentials, never yours" :side :left :color :blue :distance 24}
                    {:target component-els/run-dialog-submit :label "Starts the team session" :side :right :color :green}]})
  (h/click component-els/run-dialog-cancel)
  (h/wait-hidden component-els/run-dialog)
  (p! (str "People outside the team who can see a team session can't work in it; they fork it and continue in "
           "their own copy. Sessions started by schedules and triggers are team sessions too.")))

(defn- project-tab!
  [tab]
  (s/goto (str "/" team "/" project "?tab=" (name tab)))
  (h/wait-visible project-els/page)
  (h/wait-attribute (project-els/tab tab) "aria-selected" "true")
  (s/settle 1200))

(defn- wait-for-build!
  "Waits until the change run was followed to its build."
  []
  (let [run-id
        (s/ensure-change-run!)]
    (h/wait-until "the build of the delivered change" (* 25 60 1000) 10000
                  #(let [run (s/query :get-run :run-id (str "#" run-id))]
                     (cond
                       (contains? #{:failed :cancelled} (:status run))
                       (throw (ex-info (str "The change run ended " (name (:status run))) {}))

                       :else
                       (and (= :completed (:status run)) (some? (get-in run [:result :ci]))))))))

(defn- open-run!
  [run-id]
  (h/goto (str "/" s/tenant "/runs/" run-id))
  (h/wait-visible run-els/page)
  (h/wait-visible run-els/result 30000)
  (s/settle 1500))

(defn- failed-build
  []
  (section! "When a build fails"
            (str "A workflow that delivers changes can follow their build: `ci` in its options names how long "
                 "to wait and, with `on_failure`, the workflow to start when the build fails. The run of the "
                 "change then shows the build with its stages and links to the analysis it started."))
  (steps! "Open the project's **Builds** tab and select the run of the failed build."
          "Read the build's stages on the run's page; the build number opens it in the build server."
          "Select the link to the analysis to read what the agent found.")
  (open-run! seed/failing-change)
  (h/wait-visible component-els/build-panel)
  (figure! "ta12-failed-build"
           (str "The run of a change whose build failed: the stages of the build, and the analysis run the "
                "platform started because the workflow names one in `on_failure`.")
           {:ctx component-els/build-panel
            :pad [100 24 40 24]
            :marks [{:target component-els/build-stages :label "Where the build stopped" :side :top :color :red :distance 24}
                    {:target component-els/follow-up-link :label "The analysis it started" :side :bottom :color :blue :distance 24}]})
  (h/click component-els/follow-up-link)
  (h/wait-visible run-els/started-by)
  (h/wait-visible run-els/result 30000)
  (s/settle 1500)
  (figure! "ta13-build-analysis"
           (str "The analysis of the failed build: what failed, why, and a proposed fix. It was started for "
                "the person whose change failed and runs with the team's read-only harness.")
           {:ctx run-els/result
            :pad [250 24 24 24]
            :max-height 900}))

(defn- projects
  []
  (chapter! "Projects"
            (str "A **project** ties a repository to the team: where its code lives, which branch is the "
                 "baseline, which credential reaches it and which builds check it. Workflows name a project "
                 "instead of repeating all that, and the project's page gathers what happens around it."))
  (steps! "Describe the project under `projects` in `.platform/team.json`: id, name, branch, `scm` and, if builds should be followed, `ci`."
          "Name it in a workflow with `\"project\": \"<id>\"`; the workflow then takes its repository, credential and build job from it."
          "Push. After the next sync the project appears on the team's page and under **Projects**.")
  (wait-for-build!)
  (project-tab! :workflows)
  (figure! "ta09-project"
           "A project's page: repository, branch and build job under the name, and a tab for each view."
           {:ctx project-els/page
            :scroll false
            :max-height 520
            :marks [{:target project-els/ci :label "The build that checks its changes" :side :right :color :green}
                    {:around [(project-els/tab :workflows) (project-els/tab :webhooks)]
                     :label "One tab per view of the project" :side :right :color :blue}]})
  (table! ["Tab" "What it shows"]
          ["**Workflows**" "The team's workflows made for this project, ready to start."]
          ["**Runs**, **Sessions**" "Runs and sessions of those workflows."]
          ["**Open changes**" "Pull requests (or Gerrit changes) open in the repository, read live from it."]
          ["**Builds**" "Builds the platform followed for changes its agents delivered, with their result."]
          ["**Webhooks**" "Webhooks that start this project's workflows."])
  (project-tab! :changes)
  (h/wait-visible project-els/changes 60000)
  (s/settle)
  (figure! "ta10-open-changes"
           (str "**Open changes**: what waits for review in the repository, here a change an agent pushed to "
                "Gerrit. The list is read live from the code host.")
           {:ctx project-els/changes
            :pad [110 24 24 24]
            :max-height 420})
  (project-tab! :builds)
  (h/wait-visible project-els/builds 60000)
  (s/settle)
  (figure! "ta11-builds"
           (str "**Builds**: each change an agent delivered, with the build that checked it and its result. A "
                "failed build can start an analysis workflow that looks for the cause.")
           {:ctx project-els/builds
            :pad [110 24 24 24]
            :max-height 420})
  (when (s/query :get-run :run-id (str "#" seed/failing-change))
    (failed-build)))

(defn- open-team-webhooks!
  []
  (team-tab! :webhooks)
  (h/wait-visible webhook-els/section))

(def webhook
  "The guide's webhook, under a fixed id so that its address is the same in
   every figure."
  (seed/id "webhook:monitoring-alerts"))

(defn- save-webhook!
  "Saves what the open dialog describes under the fixed id, as the dialog's
   Create webhook would under a random one, and opens the saved webhook."
  []
  (h/click webhook-els/dialog-cancel)
  (h/wait-hidden webhook-els/dialog)
  (when (not= 200 (s/put-json! (str "/api/webhooks/" webhook)
                               {"name" webhook-name
                                "scope" {"kind" "team" "team-id" team}
                                "target" {"team-id" team "workflow-id" (:analysis-workflow h/fixture)}
                                "auth" {"type" "header" "header-name" "X-Webhook-Token" "secret" example-secret}
                                "mapping" [{"input" "brief" "source" "body" "value" "$.alert.summary"}]
                                "rate-limit-per-minute" 60
                                "enabled" true}))
    (throw (ex-info "The guide's webhook was not saved" {})))
  (swap! state assoc :webhook-id webhook)
  (s/created! :webhooks webhook)
  (open-team-webhooks!)
  (h/click (webhook-els/open webhook))
  (h/wait-visible webhook-els/detail))

(defn- create-webhook
  []
  (steps! "Open the team's **Webhooks** tab (or a project's, to bind the webhook to that project) and select **New webhook**."
          "Give it a name that says who calls it."
          "Under **Starts workflow**, select the workflow it starts."
          (str "Select the **Authentication**: a secret in a header, basic authentication, or an HMAC signature "
               "of the body. Enter a secret or select **Generate**, and copy it now: it is never shown again.")
          (str "Under **Inputs**, map each input of the workflow: a fixed value, or a path into the request's "
               "JSON body such as `$.alert.summary`, a header or a query parameter.")
          "Optionally limit the caller's addresses and the **Requests a minute**, then select **Create webhook**.")
  (open-team-webhooks!)
  (h/click webhook-els/create)
  (h/wait-visible webhook-els/dialog)
  (h/fill webhook-els/dialog-name webhook-name)
  (h/select-option webhook-els/dialog-workflow (webhook-els/option :workflow (:analysis-workflow h/fixture)))
  (h/select-option (webhook-els/mapping-source "brief") (webhook-els/option [:source "brief"] :body))
  (h/fill (webhook-els/mapping-value "brief") "$.alert.summary")
  (h/fill webhook-els/dialog-secret example-secret)
  (.setViewportSize (s/page) 1440 1500)
  (s/settle)
  (figure! "ta14-new-webhook"
           (str "A new webhook: what it starts, how callers prove who they are, how the request is turned into "
                "the workflow's inputs, and the limits on callers.")
           {:dialog true
            :pad 24
            :marks [{:target webhook-els/dialog-workflow :label "The workflow it starts" :side :left :color :blue}
                    {:target webhook-els/dialog-auth-type :label "How callers authenticate" :side :left :color :purple}
                    {:target webhook-els/dialog-generate :label "Makes a secret for you" :side :right :color :orange}
                    {:target (webhook-els/mapping-value "brief") :label "Where the input comes from" :side :right :color :green}
                    {:target webhook-els/dialog-rate-limit :label "Calls allowed per minute" :side :left :color :red}
                    {:target webhook-els/dialog-save :label "Creates the webhook" :side :right :color :green}]})
  (.setViewportSize (s/page) 1440 900)
  (save-webhook!)
  (s/settle)
  (figure! "ta15-webhook-detail"
           (str "The saved webhook. Give the caller the address and the secret; the example `curl` call shows "
                "exactly what to send.")
           {:pad 40
            :marks [{:target webhook-els/endpoint :label "The address to call" :side :top :color :blue :distance 20}
                    {:target webhook-els/rotate :label "Replaces the secret" :side :top :color :orange :distance 20}
                    {:target webhook-els/curl :label "A ready-made example call" :side :bottom :color :green :distance 20}]}))

(defn- preview-state
  []
  (h/wait-visible webhook-els/preview-result)
  (s/state-of (webhook-els/preview-input "brief")))

(defn- try-webhook
  []
  (section! "Try it before you hand it out"
            (str "The test panel under the webhook lets you paste a sample request. **Preview inputs** shows "
                 "what it would turn into without starting anything; **Send test** makes a real, signed call "
                 "to the public address."))
  (h/fill webhook-els/test-body good-body)
  (h/click webhook-els/preview)
  (h/wait-until "the preview" h/default-timeout-ms #(= "ok" (preview-state)))
  (s/settle)
  (figure! "ta16-webhook-preview"
           "**Preview inputs**: the sample body, and the inputs the workflow would get from it. Nothing is started."
           {:ctx webhook-els/test-panel
            :pad 24
            :marks [{:target webhook-els/test-body :label "A sample request body" :side :top :color :blue :distance 20}
                    {:target webhook-els/preview-result :label "The inputs it maps to" :side :bottom :color :green :distance 20}]})
  (h/click webhook-els/send-test)
  (h/wait-attribute webhook-els/test-status "data-status" "202")
  (h/wait-visible webhook-els/test-run-link)
  (s/settle)
  (figure! "ta17-webhook-test"
           (str "**Send test** with the same body: the address answers 202, as the caller would see it, and "
                "the panel links to the run the call started.")
           {:ctx webhook-els/test-panel
            :pad 24
            :marks [{:target webhook-els/send-test :label "Makes a real call" :side :top :color :blue :distance 20}
                    {:target webhook-els/test-result :label "What the caller gets back" :side :bottom :color :orange :distance 20}
                    {:target webhook-els/test-run-link :label "The run it started" :side :right :color :green}]})
  (table! ["Answer" "Meaning"]
          ["**202**" "Accepted: a run was started. The answer carries its id."]
          ["**401**" "The secret or signature is wrong."]
          ["**403**" "The caller's address is not allowed."]
          ["**404**" "The webhook doesn't exist or is switched off."]
          ["**422**" "The request doesn't give the workflow valid inputs; the answer lists the fields."]
          ["**429**" "Too many calls per minute."])
  (note! (str "A test that answers 202 starts a real run, which uses the team's token budget. Runs started by a "
              "webhook run as the platform with the team's credentials; people fork their sessions to continue.")))

(defn- replace-secret
  []
  (section! "Replace the secret"
            (str "If a secret may have leaked, or the person who knew it has left, select **Regenerate secret**. "
                 "The old one stops working at once."))
  (.evaluate (s/page) "() => window.scrollTo(0, 0)")
  (h/click webhook-els/rotate)
  (h/wait-visible component-els/confirm-dialog)
  (s/settle)
  (figure! "ta18-regenerate-confirm"
           "Regenerating asks first: every caller still using the old secret is refused from that moment."
           {:dialog true
            :pad 24
            :marks [{:target component-els/confirm-accept :label "Makes a new secret" :side :right :color :orange}]})
  (h/click component-els/confirm-accept)
  (h/wait-visible webhook-els/revealed-secret 30000)
  (s/settle)
  (figure! "ta19-new-secret"
           "The new secret is shown once. Copy it and give it to the caller; after **Done** it can't be read again."
           {:ctx webhook-els/revealed-secret
            :pad [12 30 30 30]
            :mask [[webhook-els/revealed-secret example-secret]]
            :marks [{:target webhook-els/copy-secret :label "Copies it" :side :right :color :green}]})
  (h/click webhook-els/revealed-dismiss)
  (h/wait-hidden webhook-els/revealed-secret)
  (h/click webhook-els/detail-close)
  (h/wait-hidden webhook-els/detail))

(defn- recent-calls
  "The calls of the webhook the seeded service desk has been using."
  []
  (section! "Recent calls"
            (str "Every call is recorded with its time, answer, where it came from and how it ended. Select a "
                 "webhook's name in the list to see its calls."))
  (open-team-webhooks!)
  (h/click (webhook-els/open seed/webhook))
  (h/wait-visible webhook-els/detail)
  (h/wait-visible webhook-els/deliveries)
  (s/settle)
  (figure! "ta20-deliveries"
           (str "**Recent calls** of a webhook in use: the last 50 calls with their answers. An accepted call "
                "links to the run it started; a refused one says why. **Request** shows what was sent; secret "
                "headers are never recorded.")
           {:ctx webhook-els/deliveries
            :pad [90 24 24 24]})
  (h/click webhook-els/detail-close)
  (h/wait-hidden webhook-els/detail))

(defn- switch-off-and-delete
  []
  (section! "Switch off or delete"
            (str "A webhook that is switched off answers 404 to everybody but keeps its settings and its recent "
                 "calls. Deleting removes it for good."))
  (steps! "Select **Edit** next to the webhook."
          "Turn **Enabled** off and select **Save** to stop it without losing it."
          "Select **Delete** and confirm to remove it.")
  (let [id
        (:webhook-id @state)]
    (open-team-webhooks!)
    (h/wait-visible (webhook-els/row id))
    (h/wait-until "the last call in the list" 30000 1000
                  #(string/includes? (h/text (webhook-els/last-status id)) "202"))
    (s/settle)
    (figure! "ta21-webhook-row"
             "The team's webhooks: what each starts, its authentication, whether it is enabled and how the last call ended."
             {:ctx webhook-els/table
              :pad [80 24 24 24]
              :marks [{:around [(webhook-els/edit id) (webhook-els/delete id)]
                       :label "Change it or remove it" :side :top :color :blue :distance 24}]})
    (h/click (webhook-els/delete id))
    (s/confirm!)
    (h/wait-hidden (webhook-els/row id))
    (swap! state dissoc :webhook-id)))

(defn- webhooks
  []
  (chapter! "Webhooks"
            (str "A **webhook** is an address another system calls to start one of the team's workflows: an "
                 "issue tracker when a ticket is filed, a monitoring tool when an alert fires, a script. Team "
                 "admins manage the webhooks of their team and its projects."))
  (create-webhook)
  (try-webhook)
  (replace-secret)
  (when (s/query :get-webhook :webhook-id (str "#" seed/webhook))
    (recent-calls))
  (switch-off-and-delete))

(defn- definitions
  []
  (chapter! "Where the team's definitions live"
            (str "Nothing about a team is edited in the platform. Workflows, agents, harnesses and settings are "
                 "files in the **team repository**, under `.platform/`. You change them like code (commit, "
                 "review, push) and the platform reads them at the next sync. The team page shows which "
                 "version it uses."))
  (table! ["File" "What it defines"]
          ["`.platform/team.json`" "Budget and limits, models, MCP servers, projects, required credentials."]
          ["`.platform/workflows/*.json`" "One workflow each: the activity, its agent and harness, inputs, triggers and schedule."]
          ["`.platform/agents/*.json`" "Who does the work: the instructions given to the model."]
          ["`.platform/harnesses/*.json`" "What a sandbox may use: models, tools, network, resources, credentials."]
          ["`.platform/prompts/`, `skills/`, `context/`" "Texts the agents and workflows refer to."])
  (steps! "Change the file in the team repository and push it to the branch the tenant configured."
          (str "Wait for the next sync, or ask a tenant admin to "
               "[sync the tenant's repositories](tenant-admin.md#sync-the-configuration-now) at once.")
          "Check the version under the team's name, and the **Workflows** tab for problems.")
  (team-tab! :setup)
  (figure! "ta22-agents-harnesses"
           (str "**Agents & harnesses** shows what the platform read from the repository: each agent with the "
                "file holding its instructions, each harness with its models, tools and limits.")
           {:pad [90 24 24 24]
            :marks [{:target (team-els/agent-card "analyst") :label "An agent and its instructions" :side :bottom :color :blue :distance 4}
                    {:target (team-els/harness-card "read-only") :label "A harness: what the sandbox may use" :side :top :color :green :distance 16}]})
  (section! "When a workflow can't be used"
            (str "The platform checks every workflow against what the team has been granted: its agent and "
                 "harness must exist, and its models, MCP servers and credentials must be ones the tenant "
                 "gives the team. A workflow that fails the check is not hidden from you: the team page says "
                 "why, and the others keep working. An invalid `team.json` fails the team's sync instead, and "
                 "the last good configuration stays in use."))
  (team-tab! :workflows)
  (let [broken
        (component-els/workflow-start team "github-review")]
    (when (s/present? broken 5000)
      (figure! "ta23-invalid-workflow"
               (str "A workflow that can't be used: the **Workflows** tab names its file and the reason, here a "
                    "credential the tenant has not granted the team. **Run** stays disabled until that is fixed "
                    "and synced.")
               {:pad [150 30 170 1120]
                :marks [{:target broken :label "Disabled until the problem is fixed" :side :top :color :orange :distance 30}]})))
  (p! (str "The reference pages describe every field: [team.json](../reference/team-json.md) and "
           "[workflows, agents and harnesses](../reference/definitions.md).")))

(defn clean-up!
  []
  (s/as! admin)
  (s/delete-guide-webhooks!)
  (clear-team-credential!))

(defn guide!
  []
  (reset! state {})
  (s/as! admin)
  (s/delete-guide-webhooks!)
  (clear-team-credential!)
  (team-page)
  (team-settings)
  (team-credentials)
  (team-sessions)
  (projects)
  (webhooks)
  (definitions))
