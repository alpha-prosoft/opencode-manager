(ns manager-svc.credentials.api
  "Facade for secrets and credentials of a tenant. Values never leave the
   platform side."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.auth :as auth]
            [manager-svc.client :as client]
            [manager-svc.credentials.mcp :as mcp]
            [manager-svc.credentials.required :as required]
            [manager-svc.credentials.spec :as spec]
            [manager-svc.credentials.values :as values]
            [manager-svc.credentials.vault :as vault]
            [manager-svc.errors :as errors]
            [manager-svc.roles :as roles]))

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
  "A tenant secret by name: the value tenant admins set, else the
   operator's secrets file (<secrets-dir>/<tenant>/<name>, then
   <secrets-dir>/<name>), or nil."
  [system tenant secret-name]
  (let [settings
        (:settings system)

        stored
        (try
          ((lookup system) (name secret-name) (values/owner :tenant {:tenant tenant}))
          (catch Exception e
            (log/warn e "Stored tenant secret unavailable, using the secrets dir" tenant secret-name)
            nil))

        value
        (or (:secret stored) (values/file-value settings tenant secret-name))]
    (when-not (string/blank? value)
      value)))

(defn resolve-credential
  "{:value {:secret :username} :source :user|:team|:tenant} for
   credential-id as seen from {:tenant :team-id :user} (team-id and user
   optional): the person's own value, then the team's, then the tenant's,
   each only where the credential's provided_by allows it; or nil."
  [system {:keys [tenant] :as owner-of} credential-id]
  (values/resolve-value (spec/spec system tenant)
                        (:settings system)
                        (lookup system)
                        (name credential-id)
                        owner-of))

(defn uses-personal?
  "True when any of the MCP servers needs a credential the person of
   {:tenant :user} provides themselves and has a value for."
  [system {:keys [tenant user]} mcp-servers]
  (let [tenant-spec
        (spec/spec system tenant)]
    (boolean
     (when user
       (some (fn [server-id]
               (let [credential
                     (some->> (spec/mcp-server tenant-spec server-id)
                              :credential
                              (spec/credential tenant-spec))]
                 (and credential
                      (spec/personal? credential)
                      (some? (stored-record system (:id credential)
                                            (values/owner :user {:tenant tenant :user user}))))))
             mcp-servers)))))

(defn call-mcp-tool
  "Result of tool on an MCP server {:name :url :auth} with credential
   {:secret :username} (or nil): parsed JSON or text. Throws an ex-info
   for which mcp-tool-failed? is true on any failure."
  [server credential tool arguments timeout-seconds]
  (mcp/call-tool server credential tool arguments timeout-seconds))

(defn mcp-tool-failed?
  [e]
  (mcp/tool-failed? e))

(defn required-credentials-dep
  "The :deps query of a command by which the caller starts work in team-id
   of tenant: which credentials the team requires that the caller has no
   own value of. nil for the platform and for people whose claims don't
   name the team: someone forking a session from outside, or a tenant admin
   who isn't its member, owes it nothing, as :get-my-credentials lists."
  [ctx tenant team-id]
  (when (and tenant
             team-id
             (not (auth/platform? ctx))
             (= tenant (roles/tenant-of ctx))
             (contains? (roles/claimed-team-ids (roles/caller ctx) tenant) team-id))
    {:query-id :get-missing-required-credentials
     :tenant tenant
     :team-id team-id
     :user (auth/user-id ctx)}))

(defn require-credentials!
  "Fails with 422, naming the team and the credentials, when the result of
   required-credentials-dep lists missing ones."
  [{:keys [team-id team-name missing]}]
  (when (seq missing)
    (errors/invalid! (required/missing-message (or team-name team-id) (mapv :name missing)))))

(defn seal-secret
  "Seals value ({:secret :username}) for another domain's secret, bound to
   purpose (\"webhook:<id>\") and owner (\"tenant:<tenant>\") like a
   credential value, so it opens for nothing else."
  [settings purpose owner value]
  (vault/seal settings purpose owner value))

(defn open-secret
  "The {:secret :username} sealed for purpose and owner; 422 with message
   when it was sealed for anything else."
  [settings purpose owner sealed message]
  (vault/open settings purpose owner sealed message))

(defn authorization
  "The Authorization header value of scheme (:basic, :raw, else bearer) for
   value ({:secret :username})."
  [scheme value]
  (mcp/authorization scheme value))

(defn secret-hint
  [value]
  (vault/hint value))
