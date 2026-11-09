(ns manager-svc.credentials.spec
  "Credential and MCP server declarations of a tenant's tenant.json. The
   platform decodes the spec with every default applied, so these only read
   it (platform.api)."
  (:require [manager-svc.platform.api :as platform-api]))

(defn spec
  [system tenant]
  (platform-api/tenant-spec system tenant))

(defn for-teams?
  "True when a declaration limited to teams applies to one of team-ids."
  [teams team-ids]
  (boolean (some #(platform-api/in-scope? teams %) team-ids)))

(defn credentials
  [tenant-spec]
  (vec (:credentials tenant-spec)))

(defn credential
  [tenant-spec credential-id]
  (platform-api/credential-of tenant-spec (name credential-id)))

(defn undeclared
  "What a secret no credential declares is: a tenant value."
  [credential-id]
  {:id (name credential-id) :name (name credential-id) :kind :token :provided-by [:tenant] :test nil})

(defn mcp-servers
  [tenant-spec]
  (vec (:mcp-servers tenant-spec)))

(defn mcp-server
  [tenant-spec server-id]
  (platform-api/mcp-server-of tenant-spec (name server-id)))

(def provides?
  platform-api/provides?)

(def personal?
  platform-api/personal?)

(defn personal-only?
  [credential]
  (= [:user] (vec (:provided-by credential))))

(def credential-hosts
  platform-api/credential-hosts)
