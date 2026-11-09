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

(def workflows-empty [:workflows :empty])

(defn workflow-start
  [team-id workflow-id]
  [:workflows :start team-id workflow-id])

(defn workflow-team-link
  [team-id]
  [:workflows :team team-id])

(def run-dialog [:run-dialog :dialog])
(def run-dialog-submit [:run-dialog :submit])
(def run-dialog-cancel [:run-dialog :cancel])
(def run-dialog-error [:run-dialog :error])
(def run-dialog-pull-requests-error [:run-dialog :pull-requests-error])

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
(def conversations-list [:conversations :list])

(defn conversation-open
  [conversation-id]
  [:conversations :open conversation-id])

(def activity-empty [:live-activity :empty])
(def activity-state [:live-activity :state])
(def activity-blocked [:live-activity :blocked])
(def activity-tools [:live-activity :tools])
(def activity-timeline [:live-activity :timeline])

(def audit-empty [:audit :empty])
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
