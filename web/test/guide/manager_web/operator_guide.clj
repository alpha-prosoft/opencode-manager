(ns manager-web.operator-guide
  "The guide for platform operators: oscar looks after the tenants."
  (:require [manager-web.guide-doc :refer [chapter! p! steps! bullets! table! figure!]]
            [manager-web.guide-support :as s]
            [manager-web.helpers :as h]
            [manager-web.shell.elements :as shell-els]
            [manager-web.tenants.elements :as tenants-els]))

(def guide
  {:title "For platform operators"
   :file "operator.md"
   :prefix "op"
   :lead (str "This guide is for the people who run the platform itself. As an operator you decide which "
              "tenants exist and keep their configuration flowing in: you register tenants, watch and trigger "
              "their sync, and reload the server's own configuration. What happens inside a tenant (its "
              "teams, runs and sessions) belongs to that tenant and its admins. The screenshots show Oscar, "
              "operator of a platform with the tenants **acme** and **globex**.")})

(def operator
  (:operator h/fixture))

(def tenant
  (:tenant h/fixture))

(defn- tenants-page!
  []
  (h/goto "/platform/tenants")
  (h/wait-visible tenants-els/page)
  (h/wait-visible tenants-els/table)
  (s/settle))

(defn- tenants
  []
  (chapter! "The Tenants page"
            (str "A **tenant** is one organisation on the platform, with its own teams, people, credentials and "
                 "data. Operators see the list of tenants and the state of their configuration, nothing of "
                 "their work. After signing in you land on **Tenants**, the only page in an operator's sidebar."))
  (steps! "Open **Tenants**."
          "Find the tenant and read its state and version under **Last sync**."
          "Select **Sync now** in its row to read its repositories at once, for example after its admins fixed a broken file."
          "The row updates by itself when the sync has finished.")
  (tenants-page!)
  (figure! "op01-tenants"
           (str "**Tenants**: every tenant with its onboarding repository, the state and version of its last "
                "sync, its number of teams and where it was configured.")
           {:ctx tenants-els/table
            :pad [250 24 24 24]
            :marks [{:target (tenants-els/sync-status tenant) :label "How the last sync ended" :side :top :color :green :distance 60}
                    {:target (tenants-els/sync tenant) :label "Reads its repositories now" :side :top :color :blue :distance 24}]})
  (table! ["State" "Meaning"]
          ["**Synced**" "The onboarding repository and the team repositories were read; the tenant runs the version shown."]
          ["**Sync failed**" "The last sync failed; the reason is shown underneath. A tenant that synced before keeps working with its last good configuration."]
          ["**Not synced yet**" "The tenant is known but its repository has not been read so far."])
  (p! (str "**Configured in** says where the tenant comes from: **Server configuration** (the tenants file or "
           "setting the platform was started with) or **Registered here** (added on this page)."))
  (p! (str "Tenant admins can sync their own tenant from their Admin page. One tenant's failing sync never "
           "holds up another's: each has its own status.")))

(defn- register
  []
  (chapter! "Register a tenant"
            (str "A tenant starts as a name and an **onboarding repository**: a Git repository (or directory) "
                 "the tenant's admins own, with `tenant.json` at its root. It lists the tenant's LLM "
                 "connections, credentials, MCP servers and teams. Registering makes the tenant known; the "
                 "platform reads the repository at once."))
  (steps! "Agree a name with the tenant: lower case, and the same value the identity provider puts into the `tenant` claim of its people."
          "Enter the name and the location of the onboarding repository: a Git URL, an https archive or a directory on the server."
          "Select **Register tenant**. The tenant appears in the list and its first sync starts."
          "Watch the state. **Sync failed** shows the reason, typically an unreachable repository or an invalid `tenant.json`.")
  (tenants-page!)
  (h/fill tenants-els/register-name "umbrella")
  (h/fill tenants-els/register-onboarding "https://git.example.com/umbrella/agent-onboarding.git")
  (s/settle)
  (figure! "op02-register-tenant"
           "The registration form under the list, filled in for a new tenant."
           {:pad [150 40 40 40]
            :marks [{:target tenants-els/register-name :label "The name its people's logins carry" :side :top :color :blue :distance 70}
                    {:target tenants-els/register-onboarding :label "Where its tenant.json lives" :side :bottom :color :purple :distance 24}
                    {:target tenants-els/register-submit :label "Registers it and starts its first sync" :side :right :color :green}]})
  (h/fill tenants-els/register-name "")
  (h/fill tenants-els/register-onboarding "")
  (p! (str "Tenants can also be listed in the server's configuration (`MANAGER_TENANTS` or "
           "`MANAGER_TENANTS_FILE`), which suits set-ups kept in version control. People of the tenant can "
           "sign in as soon as their identity provider sends the tenant's name with their login. A private "
           "repository needs a credential: give the onboarding source as a map with `url`, `branch` and "
           "`credentials` in the tenants file instead of a plain URL.")))

(defn- reload
  []
  (chapter! "Reload the configuration"
            (str "The platform re-reads its own configuration files by itself: the tenants file, the claims "
                 "parser that turns logins into tenants, teams and roles, and the identity provider's keys. "
                 "**Reload configuration** does it now and tells you, part by part, whether it worked."))
  (steps! "Change the file on the server (or rotate the keys at the identity provider)."
          "Open **Tenants** and select **Reload configuration**."
          "Check that every part says **Reloaded**; fix what failed and reload again.")
  (tenants-page!)
  (h/click tenants-els/reload)
  (h/wait-visible tenants-els/reload-result 60000)
  (s/settle)
  (figure! "op03-reload"
           (str "After **Reload configuration**: one line per part. A part that failed keeps its last good "
                "value and shows why.")
           {:ctx tenants-els/reload-result
            :pad [24 24 24 24]
            :marks [{:target tenants-els/reload :label "Reads the files again now" :side :left :color :blue}]})
  (table! ["Part" "What is reloaded"]
          ["**Claims parser**" "The rules that read a person's tenant, teams and roles from their login."]
          ["**Tenants file**" "The list of tenants the server is configured with."]
          ["**Identity provider discovery**, **keys**" "The provider's discovery document and its signing keys."]
          ["**Sync of new tenants**" "Whether tenants new in the file were synced, or that none was added."])
  (p! (str "Nothing breaks while a file is wrong: the platform keeps the last version that loaded and logs the "
           "error.")))

(defn- boundaries
  []
  (chapter! "What an operator does not see"
            (str "Operating the platform is not the same as being in a tenant. An operator account that "
                 "belongs to no tenant has no teams, runs, sessions or credentials, and the pages of a tenant "
                 "stay closed to it."))
  (h/goto (str "/" tenant "/runs"))
  (h/wait-any shell-els/foreign-tenant shell-els/not-found tenants-els/page)
  (s/settle)
  (when (h/visible? shell-els/foreign-tenant-message)
    (figure! "op04-not-your-tenant"
             "A tenant's address opened as an operator: the page says whose it is and loads none of its data."
             {:ctx shell-els/foreign-tenant-message
              :pad [110 24 24 24]}))
  (bullets! "You **can** register tenants, see every tenant's sync state, sync any tenant and reload the server's configuration."
            "You **can** read the platform's metrics endpoint (`/metrics`) for monitoring."
            "You **can't** open a tenant's teams, runs, sessions, audit trails, usage or credentials."
            "You **can't** set a tenant's credential values in the UI; operators provide secrets as files on the server instead.")
  (p! (str "If you also work in a tenant, use an account that the identity provider places in that tenant. "
           "The operator role alone opens nothing inside it.")))

(defn guide!
  []
  (s/as! operator)
  (tenants)
  (register)
  (reload)
  (boundaries))
