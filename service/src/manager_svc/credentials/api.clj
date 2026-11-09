(ns manager-svc.credentials.api
  "Facade for secrets and credentials. Mirrors platform/secrets.py and
   platform/credentials.py. Values never leave the platform side."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.client :as client]
            [manager-svc.credentials.mcp :as mcp]
            [manager-svc.credentials.spec :as spec]
            [manager-svc.credentials.values :as values]
            [manager-svc.credentials.vault :as vault]))

(defn stored-record
  "The sealed record of a credential value, read from edd as the platform."
  [system credential-id owner]
  (client/query (:settings system) {:query-id :get-stored-credential
                                    :credential-id (name credential-id)
                                    :owner owner}))

(defn- lookup
  [system]
  (values/opener (:settings system) (partial stored-record system)))

(defn secret
  "A platform secret by name (secrets dir file or admin-set value), or nil."
  [system secret-name]
  (let [settings
        (:settings system)

        stored
        (try
          ((lookup system) (name secret-name) vault/platform-owner)
          (catch Exception e
            (log/warn e "Stored platform secret unavailable, using the secrets dir" secret-name)
            nil))

        value
        (or (:secret stored) (values/file-value settings secret-name))]
    (when-not (string/blank? value)
      value)))

(defn resolve-credential
  "{:value {:secret :username} :source :user|:platform} for credential-id as
   seen by user (nil user = platform only), or nil."
  [system credential-id user]
  (values/resolve-value (spec/spec system)
                        (:settings system)
                        (lookup system)
                        (name credential-id)
                        user))

(defn uses-personal?
  "True when any of the MCP servers needs a personal credential of owner."
  [system owner mcp-servers]
  (let [platform-spec
        (spec/spec system)]
    (boolean
     (some (fn [server-id]
             (let [credential
                   (some->> (spec/mcp-server platform-spec server-id)
                            :credential
                            (spec/credential platform-spec))]
               (and credential
                    (spec/personal? credential)
                    (some? (stored-record system (:id credential) owner)))))
           mcp-servers))))

(defn call-mcp-tool
  "Result of tool on an MCP server {:name :url :auth} with credential
   {:secret :username} (or nil): parsed JSON or text. Throws an ex-info
   for which mcp-tool-failed? is true on any failure."
  [server credential tool arguments timeout-seconds]
  (mcp/call-tool server credential tool arguments timeout-seconds))

(defn mcp-tool-failed?
  [e]
  (mcp/tool-failed? e))
