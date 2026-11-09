(ns manager-web.tenant-admin-guide
  "The guide for tenant admins: alice looks after the tenant acme."
  (:require [manager-web.admin.elements :as admin-els]
            [manager-web.components.elements :as component-els]
            [manager-web.guide-doc :refer [chapter! section! p! steps! table! note! figure!]]
            [manager-web.guide-seed :as seed]
            [manager-web.guide-support :as s]
            [manager-web.helpers :as h]
            [manager-web.team.elements :as team-els]
            [manager-web.webhooks.elements :as webhook-els]))

(def guide
  {:title "For tenant admins"
   :file "tenant-admin.md"
   :prefix "tn"
   :lead (str "This guide is for the admins of a tenant: the organisation's own space on the platform, with its "
              "teams, people, credentials and LLM connections. You watch its health and spend, keep its shared "
              "credentials working and decide what its teams may use. It assumes you know the guides for "
              "[team members](member.md) and [team admins](team-admin.md): as tenant admin you are an admin of "
              "every team of the tenant. The screenshots show Alice, tenant admin of **acme**.")})

(def admin
  (:tenant-admin h/fixture))

(def shared-credential
  (:admin-only-credential h/fixture))

(def overseen-team
  (:required-team h/fixture))

(defn- admin-tab!
  [tab]
  (s/goto (str "/admin?tab=" (name tab)))
  (h/wait-visible admin-els/page)
  (h/wait-attribute (admin-els/tab tab) "aria-selected" "true")
  (s/settle 800))

(defn- overview
  []
  (chapter! "The Admin page"
            (str "**Admin** in the sidebar (under **Tenant**) is your view of the whole tenant. It shows the "
                 "effect of the tenant's configuration; the configuration itself is a file, `tenant.json`, in "
                 "the tenant's onboarding repository."))
  (admin-tab! :overview)
  (h/wait-visible admin-els/overview-stats)
  (figure! "tn01-admin-overview"
           (str "The Admin page on **Overview**: teams and people, sessions running now, today's runs, tokens "
                "and estimated cost, and the state of the tenant's configuration.")
           {:ctx admin-els/page
            :scroll false
            :max-height 640
            :marks [{:around [(admin-els/tab :overview) (admin-els/tab :jobs)] :label "One tab per question" :side :right :color :blue}
                    {:around [admin-els/sync admin-els/stop-idle] :label "Actions on the whole tenant" :side :right :color :green}
                    {:target admin-els/overview-stats :label "Today at a glance" :side :bottom :color :purple :distance 16}]})
  (table! ["Tab" "What it answers"]
          ["**Overview**" "Is the configuration in sync? How much is running and what does today cost?"]
          ["**People**" "Who has used the platform, in which teams and roles?"]
          ["**Usage & cost**" "Which team and model used how many tokens, and what did it cost?"]
          ["**Teams**" "Which teams exist, where do their definitions come from, are they in sync?"]
          ["**LLM connections**" "Which models may teams use, and does the connection work?"]
          ["**Credentials**" "Which credentials exist, who provides them, which are required?"]
          ["**MCP servers**" "Which tool servers do sandboxes reach, and with which credential?"]
          ["**Background jobs**" "Which recurring jobs does the platform run for the tenant?"]))

(defn- sync-now
  []
  (chapter! "Sync the configuration now"
            (str "The platform reads the onboarding repository and every team repository regularly. After a "
                 "change you don't want to wait for, sync by hand."))
  (steps! "Push the change to the onboarding repository or a team repository."
          "Open **Admin** and select **Sync …'s repositories**."
          (str "Wait for the outcome under the buttons: **Synced** with the number of team repositories that "
               "are up to date, or what failed.")
          "Select **See each team** to open the **Teams** tab with every team's source and last sync.")
  (admin-tab! :overview)
  (h/click admin-els/sync)
  (h/wait-visible admin-els/sync-outcome 120000)
  (h/wait-until "the sync's outcome" 180000 1000
                #(contains? #{"synced" "failed"} (s/state-of admin-els/sync-outcome)))
  (s/settle 800)
  (figure! "tn02-sync"
           (str "After **Sync …'s repositories**: the page first confirms the request, then shows the outcome "
                "with a link to the teams.")
           {:ctx admin-els/sync-outcome
            :pad [150 24 24 24]
            :marks [{:target admin-els/sync :label "Reads every repository now" :side :right :color :blue :distance 330}
                    {:target admin-els/sync-outcome-teams :label "Opens the Teams tab" :side :right :color :green}]})
  (admin-tab! :teams)
  (h/wait-visible admin-els/teams-table)
  (figure! "tn03-teams"
           (str "**Teams**: each team with the source of its definitions, when it was last synced and how many "
                "definitions were read. The name opens the team's page.")
           {:ctx admin-els/teams-table
            :pad [90 24 24 24]
            :marks [{:target (admin-els/team-link (:team h/fixture)) :label "Opens the team's page" :side :right :color :blue}]})
  (p! (str "A team whose files are invalid keeps its last good version; its row says what is wrong. Teams are "
           "added and removed in `tenant.json` (`teams`: id, name, repository). Who belongs to a team is "
           "decided in your identity provider.")))

(defn- people-and-usage
  []
  (chapter! "People, usage and cost"
            (str "Two tabs answer who uses the platform and what it costs. Both read what the platform's gateway "
                 "measured: every LLM call passes through it."))
  (admin-tab! :people)
  (h/wait-any admin-els/users-table admin-els/users-empty)
  (figure! "tn04-people"
           (str "**People**: everyone of the tenant who ran a workflow or called a model in the last 30 days, "
                "with their number of runs and the tokens they used.")
           {:ctx admin-els/users-table
            :pad [90 24 24 24]})
  (steps! "Open **Admin** and select the **Usage & cost** tab."
          "Select the period at the top right."
          "Compare teams in the first table; the second splits each team by model, with cached and failed calls.")
  (admin-tab! :usage)
  (h/wait-any admin-els/team-usage-table admin-els/usage-empty)
  (figure! "tn05-usage"
           (str "**Usage & cost** by team, then by team and model. The cost is an estimate from the model "
                "prices in `tenant.json`; a model without a price shows none.")
           {:ctx admin-els/usage-table
            :pad [24 24 24 24]
            :marks [{:target admin-els/usage-days :label "The period shown" :side :left :color :blue}
                    {:target admin-els/team-usage-table :label "Per team" :side :top :color :green :distance 16}]})
  (p! (str "A team's own **Usage** tab shows the same numbers for that team, and **Team settings** how close it "
           "is to its daily token budget.")))

(defn- connections
  []
  (chapter! "LLM connections"
            (str "An **LLM connection** is a model provider the tenant has an account with. `tenant.json` names "
                 "the connections, their models and prices; teams pick from them. Agents never get the "
                 "provider's key: their calls go through the platform's gateway, which adds it."))
  (steps! "Open **Admin** and select the **LLM connections** tab."
          "Select **Test connection**."
          "Read the answer under the connection's models.")
  (admin-tab! :connections)
  (h/wait-visible admin-els/connection-list)
  (h/click (admin-els/test-connection (:connection h/fixture)))
  (h/wait-visible (admin-els/test-result (:connection h/fixture)) 120000)
  (s/settle 800)
  (figure! "tn06-llm-connections"
           (str "**LLM connections**. **Test connection** sends a tiny prompt to the connection's first model "
                "and shows whether address and key work.")
           {:ctx admin-els/connections
            :pad 24
            :marks [{:target (admin-els/test-connection (:connection h/fixture)) :label "Sends a tiny prompt" :side :top :color :blue :distance 24}
                    {:target (admin-els/test-result (:connection h/fixture)) :label "The provider's answer" :side :bottom :color :green :distance 24}]})
  (p! (str "If the test fails, check the connection's secret under **Credentials** and its address in "
           "`tenant.json`.")))

(defn- credentials
  []
  (chapter! "Tenant credentials"
            (str "`tenant.json` declares every credential the tenant's tools use: its name, the hosts it may be "
                 "sent to, who may provide a value and whether each person must add their own. The "
                 "**Credentials** tab shows them and holds the tenant's values."))
  (admin-tab! :credentials)
  (h/wait-visible admin-els/credentials-table)
  (figure! "tn07-credentials"
           (str "**Credentials**: who may provide each one, whether the tenant has a value, how many teams and "
                "people added their own, and what uses it.")
           {:ctx admin-els/credentials-table
            :pad [100 24 24 24]
            :marks [{:target (admin-els/required-credential (:tenant-required-credential h/fixture))
                     :label "Required by the tenant of each person" :side :right :color :orange}
                    {:target (admin-els/set-credential shared-credential)
                     :label "Opens the dialog below" :side :left :color :green}]})
  (table! ["Tenant value" "Meaning"]
          ["**Set**" "A value stored encrypted on the platform, set on this page."]
          ["**From secret file**" "A value the platform operator provides as a file on the server."]
          ["**Missing**" "No tenant value. Teams or people have to provide their own, or tools needing it fail."]
          ["A dash" "The credential is provided by people only; the tenant can't hold one."])
  (steps! "Open **Admin** and select the **Credentials** tab."
          "Select **Set** (or **Replace**) in the credential's row."
          "Enter the value, and the user name where the credential has one, and save."
          "A credential with a test is checked against its service first; a wrong value is refused and not stored.")
  (h/click (admin-els/set-credential shared-credential))
  (h/wait-visible component-els/credential-dialog)
  (h/fill component-els/credential-secret "guide-placeholder-value")
  (s/settle)
  (figure! "tn08-set-tenant-credential"
           "Setting the tenant's value. It is stored encrypted and never shown again, only a short hint."
           {:dialog true
            :pad 24
            :marks [{:target component-els/credential-secret :label "The value for everyone in the tenant" :side :left :color :blue :distance 24}
                    {:target component-els/credential-submit :label "Stores it for the tenant" :side :right :color :green}]})
  (h/click component-els/credential-cancel)
  (h/wait-hidden component-els/credential-dialog)
  (section! "Require a credential of everyone"
            (str "Mark a credential `\"required\": true` in `tenant.json` when every person in the teams it "
                 "applies to must work with their own account, as PagerDuty is in the figure above. The row "
                 "then shows **Required** and who requires it; people without their own value are held on "
                 "the setup page at their next visit. Teams can require credentials of their own members in "
                 "their `team.json`."))
  (s/goto (str "/" overseen-team "?tab=settings"))
  (h/wait-visible team-els/required-credentials)
  (s/settle)
  (figure! "tn09-required"
           (str "**Required of every member** on a team's **Team settings**: here one credential is required by "
                "the team itself (`team.json`) and one by the tenant (`tenant.json`).")
           {:ctx team-els/required-credentials
            :pad [130 24 24 24]})
  (note! (str "LLM connection secrets are tenant values only: neither teams nor people can replace them. "
              "Values never travel into a sandbox; the gateway adds them to requests for the declared hosts.")))

(defn- tools-and-jobs
  []
  (chapter! "MCP servers and background jobs"
            (str "**MCP servers** are the tool servers agents may call: issue trackers, code hosts, build "
                 "servers. They are declared in `tenant.json` with the credential they use and, optionally, "
                 "the teams and tools they are limited to."))
  (admin-tab! :mcp)
  (h/wait-any admin-els/mcp-list admin-els/mcp-empty)
  (figure! "tn10-mcp-servers"
           (str "**MCP servers**: each server with its address, the credential the gateway adds and the tools "
                "agents may call.")
           {:ctx admin-els/mcp-list
            :pad [90 24 24 24]
            :marks [{:target (admin-els/mcp-server (:mcp-server h/fixture)) :label "A tool server and its credential" :side :bottom :color :blue :distance 16}]})
  (admin-tab! :jobs)
  (h/wait-visible admin-els/schedules-table)
  (figure! "tn11-background-jobs"
           (str "**Background jobs**: the recurring jobs behind workflows that start by themselves, on a "
                "schedule or when a new pull request appears, with their last and next run.")
           {:ctx admin-els/schedules-table
            :pad [90 24 24 24]})
  (p! (str "Sandboxes keep running until somebody stops them or their idle timeout passes. **Stop idle sessions "
           "in …** on the **Overview** stops, at once, every session of the tenant that has been idle longer "
           "than its limit, and reports how many it stopped. Nothing is lost: owners start them again.")))

(defn- tenant-webhooks
  []
  (chapter! "Tenant webhooks"
            (str "The **Webhooks** page lists every webhook you may see across the tenant. Only tenant admins "
                 "create webhooks that **belong to the tenant** rather than to one team: use them for callers "
                 "that serve the whole organisation, such as a central issue tracker. A tenant webhook still "
                 "starts one workflow of one team."))
  (steps! "Open **Webhooks** in the sidebar and select **New webhook**."
          "Set **Belongs to** to the tenant."
          "Select the team and the workflow to start, the authentication and the input mapping."
          "Select **Save**, then test it as described in the [team admins' guide](team-admin.md#webhooks).")
  (s/goto "/webhooks")
  (h/wait-visible webhook-els/page)
  (h/click webhook-els/create)
  (h/wait-visible webhook-els/dialog)
  (h/fill webhook-els/dialog-name "Incident hotline")
  (h/select-option webhook-els/dialog-scope (webhook-els/option :scope :tenant))
  (h/select-option webhook-els/dialog-team (webhook-els/option :target-team (:team h/fixture)))
  (h/select-option webhook-els/dialog-workflow (webhook-els/option :workflow (:analysis-workflow h/fixture)))
  (s/settle)
  (figure! "tn12-tenant-webhook"
           (str "A webhook that belongs to the tenant: **Belongs to** decides who manages it and where it is "
                "listed, **Team whose workflow it starts** and **Starts workflow** what it starts.")
           {:dialog true
            :pad 24
            :max-height 640
            :marks [{:target webhook-els/dialog-scope :label "Managed by tenant admins" :side :left :color :orange :distance 24}
                    {:target webhook-els/dialog-workflow :label "The team's workflow it starts" :side :left :color :blue :distance 24}]})
  (h/click webhook-els/dialog-cancel)
  (h/wait-hidden webhook-els/dialog)
  (p! "Only tenant admins can change a webhook that belongs to the tenant."))

(defn- overseeing-teams
  []
  (chapter! "Teams you oversee without being a member"
            (str "As tenant admin you are an admin of every team, also of those your account isn't a member "
                 "of. The platform says so wherever it matters, because some things depend on membership."))
  (s/goto (str "/" overseen-team))
  (h/wait-visible team-els/page)
  (s/settle)
  (figure! "tn13-not-a-member"
           (str "A team seen as tenant admin. The chip says **Tenant admin, not a member**: you manage the team, "
                "but you are not one of its people.")
           {:pad [40 24 70 420]
            :marks [{:target team-els/role :label "Admin through the tenant" :side :bottom :color :orange :distance 24}]})
  (table! ["You can" "You can't, or needn't"]
          ["See the team's page, runs and sessions" "Work in a member's private session; you fork it instead"]
          ["Set team credentials, start team sessions, manage webhooks" "Be asked for the credentials the team requires of its members"]
          ["Stop, lock or delete the team's sessions" "Appear in the team's member counts"])
  (p! (str "The sidebar shows your own role under your name. Every team of the tenant is reached through "
           "**Teams** in the sidebar or the **Teams** tab of the Admin page.")))

(defn guide!
  []
  (s/as! admin)
  (s/delete-guide-webhooks!)
  (seed/quiet!)
  (overview)
  (sync-now)
  (people-and-usage)
  (connections)
  (credentials)
  (tools-and-jobs)
  (tenant-webhooks)
  (overseeing-teams))
