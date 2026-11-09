(ns manager-web.team.elements)

(def page [:team :page])
(def title [:team :title])
(def back [:team :back])
(def error [:team :error])
(def sync-error [:team :sync-error])

(defn tab
  [tab-id]
  [:team :tab tab-id])

(def budget [:team :budget])
(def configuration [:team :configuration])
(def limits [:team :limits])
(def connections [:team :connections])
(def mcp-servers [:team :mcp-servers])
(def projects [:team :projects])

(defn limit
  [limit-id]
  [:team :limit limit-id])

(defn project
  [project-id]
  [:team :project project-id])

(def credentials [:team :credentials])
(def credentials-empty [:team :credentials-empty])
(def credentials-error [:team :credentials-error])
(def credentials-action-error [:team :credentials-action-error])

(defn credential
  [credential-id]
  [:team :credential credential-id])

(defn credential-state
  [credential-id]
  [:team :credential-state credential-id])

(defn set-credential
  [credential-id]
  [:team :set-credential credential-id])

(defn clear-credential
  [credential-id]
  [:team :clear-credential credential-id])

(def own-value-link [:team :own-value-link])

(defn agent-card
  [agent-id]
  [:team :agent agent-id])

(defn harness-card
  [harness-id]
  [:team :harness harness-id])

(def runs-error [:team :runs-error])
(def usage-by-model [:team :usage-by-model])

(def sessions-error [:team :sessions-error])
(def sessions-action-error [:team :sessions-action-error])
(def start-team-session [:team :start-team-session])
(def team-session-menu [:team :team-session-menu])

(defn team-session-workflow
  [workflow-id]
  [:team :team-session-workflow workflow-id])

(def crumbs [:team :crumbs])
(def scope [:team :scope])
(def role [:team :role])

(def required-credentials [:team :required-credentials])
(def required-credentials-empty [:team :required-credentials-empty])

(defn required-credential
  [credential-id]
  [:team :required-credential credential-id])

(defn required-source
  [credential-id source]
  [:team :required-source credential-id source])

(defn required-own-state
  [credential-id]
  [:team :required-own-state credential-id])

(def crumb-tenant [:team :crumb-tenant])
(def project-cards [:team :project-cards])
(def project-grid [:team :project-grid])

(defn project-card
  [team-id project-id]
  [:team :project-card team-id project-id])

(defn required-add
  [credential-id]
  [:team :required-add credential-id])

(def agents-empty [:team :agents-empty])
(def harnesses-empty [:team :harnesses-empty])
(def back-to-list [:team :back-to-list])
