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
(def sync-error [:admin :sync-error])
(def overview-stats [:admin :overview-stats])

(def users-table [:admin :users])

(def usage-days [:admin :usage-days])

(defn usage-days-option
  [days]
  [:admin :usage-days days])

(def usage-table [:admin :usage-table])
(def usage-empty [:admin :usage-empty])

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

(def mcp-empty [:admin :mcp-empty])
(def mcp-list [:admin :mcp-list])

(defn mcp-server
  [server-id]
  [:admin :mcp-server server-id])

(def schedules-table [:admin :schedules])
