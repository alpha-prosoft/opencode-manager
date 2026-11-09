(ns manager-web.admin.elements
  (:refer-clojure :exclude [sync]))

(def page [:admin :page])

(defn tab
  [tab-id]
  [:admin :tab tab-id])

(defn panel-error
  [tab-id]
  [:admin :error tab-id])

(def sync [:admin :sync])
(def stop-idle [:admin :stop-idle])
(def report [:admin :report])
(def sync-outcome [:admin :sync-outcome])
(def sync-outcome-teams [:admin :sync-outcome-teams])
(def sync-error [:admin :sync-error])
(def sync-status [:admin :sync-status])
(def forbidden [:admin :forbidden])
(def overview-stats [:admin :overview-stats])

(def users-table [:admin :users])
(def users-empty [:admin :users-empty])

(def usage-days [:admin :usage-days])

(defn usage-days-option
  [days]
  [:admin :usage-days days])

(def usage-table [:admin :usage-table])
(def usage-empty [:admin :usage-empty])
(def team-usage-table [:admin :team-usage-table])

(defn team-usage
  [team-id]
  [:admin :team-usage team-id])

(def teams-table [:admin :teams-table])

(defn team-link
  [team-id]
  [:admin :team team-id])

(def connections [:admin :connections])
(def connection-list [:admin :connection-list])

(defn test-connection
  [connection-id]
  [:admin :test-connection connection-id])

(defn test-result
  [connection-id]
  [:admin :test-result connection-id])

(def credentials-table [:admin :credentials])

(defn set-credential
  [credential-id]
  [:admin :set-credential credential-id])

(defn clear-credential
  [credential-id]
  [:admin :clear-credential credential-id])

(def credentials-action-error [:admin :credentials-action-error])

(defn required-credential
  [credential-id]
  [:admin :required-credential credential-id])

(def mcp-empty [:admin :mcp-empty])
(def mcp-list [:admin :mcp-list])

(defn mcp-server
  [server-id]
  [:admin :mcp-server server-id])

(def schedules-table [:admin :schedules])
(def scope [:admin :scope])
