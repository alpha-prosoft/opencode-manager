(ns manager-web.components.elements
  "Element ids of the shared components.")

(def confirm-dialog [:confirm :dialog])
(def confirm-accept [:confirm :accept])
(def confirm-keep [:confirm :keep])

(defn session-action
  [action session-id]
  [:session-action action session-id])

(def runs-table [:runs-table :table])
(def runs-empty [:runs-table :empty])
(def runs-cut [:runs-table :cut])

(defn run-link
  [run-id]
  [:runs-table :run run-id])

(defn run-pull-request
  [run-id]
  [:runs-table :pull-request run-id])

(def sessions-table [:sessions-table :table])
(def sessions-empty [:sessions-table :empty])

(defn session-link
  [session-id]
  [:sessions-table :session session-id])

(defn session-team
  [session-id]
  [:sessions-table :team session-id])

(defn session-owner
  [session-id]
  [:sessions-table :owner session-id])

(defn session-sharing
  [session-id]
  [:sessions-table :sharing session-id])

(defn session-context
  [session-id]
  [:sessions-table :context session-id])

(defn run-context
  [run-id]
  [:runs-table :context run-id])

(defn run-team
  [run-id]
  [:runs-table :team run-id])

(def workflows-empty [:workflows :empty])

(defn workflow-start
  [team-id workflow-id]
  [:workflows :start team-id workflow-id])

(defn workflow-team-session
  [team-id workflow-id]
  [:workflows :team-session team-id workflow-id])

(defn workflow-team-link
  [team-id]
  [:workflows :team team-id])

(defn workflow-project
  [team-id workflow-id]
  [:workflows :project team-id workflow-id])

(defn run-project
  [run-id]
  [:runs-table :project run-id])

(defn session-project
  [session-id]
  [:sessions-table :project session-id])

(defn workflow-team-role
  [team-id]
  [:workflows :team-role team-id])

(def run-dialog [:run-dialog :dialog])
(def run-dialog-submit [:run-dialog :submit])
(def run-dialog-cancel [:run-dialog :cancel])
(def run-dialog-error [:run-dialog :error])
(def run-dialog-pull-requests-error [:run-dialog :pull-requests-error])
(def run-dialog-visibility [:run-dialog :visibility])
(def run-dialog-context [:run-dialog :context])
(def run-dialog-team [:run-dialog :team])
(def run-dialog-runs-as [:run-dialog :runs-as])
(def run-dialog-missing-credentials [:run-dialog :missing-credentials])
(def run-dialog-add-credential [:run-dialog :add-credential])

(defn run-dialog-visibility-option
  [visibility]
  [:run-dialog :visibility-option visibility])

(def team-context [:team-context :select])
(def team-context-value [:team-context :value])
(def team-context-none [:team-context :none])
(def team-context-box [:team-context :box])
(def team-context-manage [:team-context :manage])

(defn team-context-option
  [team-id]
  [:team-context :option team-id])

(defn schema-field
  [field]
  [:schema-form :field field])

(def credential-username [:credential-form :username])
(def credential-secret [:credential-form :secret])
(def credential-submit [:credential-form :submit])
(def credential-error [:credential-form :error])
(def credential-dialog [:credential-form :dialog])
(def credential-cancel [:credential-form :cancel])

(def patch-download [:patch :download])
(def patch-view [:patch :view])
(def patch-empty [:patch :empty])

(def usage-stats [:usage :stats])

(def conversations-empty [:conversations :empty])
(def conversations-error [:conversations :error])
(def conversations-list [:conversations :list])

(defn conversation-open
  [conversation-id]
  [:conversations :open conversation-id])

(def activity-empty [:live-activity :empty])
(def activity-error [:live-activity :error])
(def activity-state [:live-activity :state])
(def activity-blocked [:live-activity :blocked])
(def activity-tools [:live-activity :tools])
(def activity-timeline [:live-activity :timeline])

(def audit-empty [:audit :empty])
(def audit-error [:audit :error])
(def audit-verification [:audit :verification])
(def audit-download [:audit :download])
(def audit-verify-error [:audit :verify-error])
(def audit-truncated [:audit :truncated])

(defn audit-record
  [record-seq]
  [:audit :record record-seq])

(def review-verdict [:review :verdict])
(def build-panel [:build :panel])
(def build-link [:build :link])
(def build-stages [:build :stages])

(defn build-stage
  [stage-name]
  [:build :stage stage-name])
(def follow-up-link [:build :follow-up])
