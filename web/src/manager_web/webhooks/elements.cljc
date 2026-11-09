(ns manager-web.webhooks.elements)

(def page [:webhooks :page])
(def title [:webhooks :title])
(def scope [:webhooks :scope])
(def section [:webhooks :section])
(def create [:webhooks :create])
(def list-error [:webhooks :list-error])
(def empty-list [:webhooks :empty])
(def table [:webhooks :table])
(def project-filter [:webhooks :project-filter])
(def action-error [:webhooks :action-error])

(defn project-option
  [project-id]
  [:webhooks :project-option project-id])

(defn row
  [webhook-id]
  [:webhooks :row (str webhook-id)])

(defn open
  [webhook-id]
  [:webhooks :open (str webhook-id)])

(defn edit
  [webhook-id]
  [:webhooks :edit (str webhook-id)])

(defn delete
  [webhook-id]
  [:webhooks :delete (str webhook-id)])

(defn last-status
  [webhook-id]
  [:webhooks :last-status (str webhook-id)])

(defn enabled
  [webhook-id]
  [:webhooks :enabled (str webhook-id)])

(def dialog [:webhooks :dialog])
(def dialog-name [:webhooks :dialog-name])
(def dialog-description [:webhooks :dialog-description])
(def dialog-scope [:webhooks :dialog-scope])
(def dialog-team [:webhooks :dialog-team])
(def dialog-project [:webhooks :dialog-project])
(def dialog-workflow [:webhooks :dialog-workflow])
(def dialog-auth-type [:webhooks :dialog-auth-type])
(def dialog-header-name [:webhooks :dialog-header-name])
(def dialog-username [:webhooks :dialog-username])
(def dialog-secret [:webhooks :dialog-secret])
(def dialog-generate [:webhooks :dialog-generate])
(def dialog-algorithm [:webhooks :dialog-algorithm])
(def dialog-timestamp-header [:webhooks :dialog-timestamp-header])
(def dialog-tolerance [:webhooks :dialog-tolerance])
(def dialog-none-warning [:webhooks :dialog-none-warning])
(def dialog-allowed-ips [:webhooks :dialog-allowed-ips])
(def dialog-allow-get [:webhooks :dialog-allow-get])
(def dialog-rate-limit [:webhooks :dialog-rate-limit])
(def dialog-enabled [:webhooks :dialog-enabled])
(def dialog-error [:webhooks :dialog-error])
(def dialog-save [:webhooks :dialog-save])
(def dialog-cancel [:webhooks :dialog-cancel])
(def dialog-curl [:webhooks :dialog-curl])

(defn option
  [field value]
  [:webhooks :option field (str value)])

(defn mapping-source
  [input]
  [:webhooks :mapping-source (name input)])

(defn mapping-value
  [input]
  [:webhooks :mapping-value (name input)])

(def detail [:webhooks :detail])
(def detail-title [:webhooks :detail-title])
(def detail-close [:webhooks :detail-close])
(def endpoint [:webhooks :endpoint])
(def copy-endpoint [:webhooks :copy-endpoint])
(def curl [:webhooks :curl])
(def copy-curl [:webhooks :copy-curl])
(def auth-summary [:webhooks :auth-summary])
(def rotate [:webhooks :rotate])
(def revealed-secret [:webhooks :revealed-secret])
(def revealed-dismiss [:webhooks :revealed-dismiss])
(def detail-error [:webhooks :detail-error])

(def test-panel [:webhooks :test-panel])
(def test-method [:webhooks :test-method])
(def test-body [:webhooks :test-body])
(def test-headers [:webhooks :test-headers])
(def test-query [:webhooks :test-query])
(def preview [:webhooks :preview])
(def preview-result [:webhooks :preview-result])
(def preview-error [:webhooks :preview-error])

(defn preview-input
  [input]
  [:webhooks :preview-input (name input)])

(def send-test [:webhooks :send-test])
(def test-result [:webhooks :test-result])
(def test-status [:webhooks :test-status])
(def test-run-link [:webhooks :test-run-link])
(def test-error [:webhooks :test-error])

(def deliveries [:webhooks :deliveries])
(def deliveries-empty [:webhooks :deliveries-empty])

(defn delivery
  [delivery-id]
  [:webhooks :delivery (str delivery-id)])

(defn delivery-status
  [delivery-id]
  [:webhooks :delivery-status (str delivery-id)])

(defn delivery-run
  [delivery-id]
  [:webhooks :delivery-run (str delivery-id)])

(defn delivery-toggle
  [delivery-id]
  [:webhooks :delivery-toggle (str delivery-id)])

(defn delivery-details
  [delivery-id]
  [:webhooks :delivery-details (str delivery-id)])

(def dialog-copy-curl [:webhooks :dialog-copy-curl])
(def dialog-problems [:webhooks :dialog-problems])
(def copy-secret [:webhooks :copy-secret])
(def rotate-error [:webhooks :rotate-error])
(def targets-error [:webhooks :targets-error])
(def team-hint [:webhooks :team-hint])
