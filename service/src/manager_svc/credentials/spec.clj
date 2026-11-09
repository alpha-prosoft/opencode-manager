(ns manager-svc.credentials.spec
  "Credential and MCP server declarations of the onboarding spec. The
   platform decodes the spec with every default applied, so these only read
   it (platform.api)."
  (:require [manager-svc.platform.api :as platform-api]))

(defn spec
  [system]
  (or (platform-api/platform-spec system) {}))

(defn for-teams?
  "True when a declaration limited to teams applies to one of team-ids."
  [teams team-ids]
  (boolean (some #(platform-api/in-scope? teams %) team-ids)))

(defn credentials
  [platform-spec]
  (vec (:credentials platform-spec)))

(defn credential
  [platform-spec credential-id]
  (platform-api/credential-of platform-spec (name credential-id)))

(defn mcp-servers
  [platform-spec]
  (vec (:mcp-servers platform-spec)))

(defn mcp-server
  [platform-spec server-id]
  (platform-api/mcp-server-of platform-spec (name server-id)))

(def personal?
  platform-api/personal?)

(def credential-hosts
  platform-api/credential-hosts)
