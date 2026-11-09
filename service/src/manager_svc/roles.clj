(ns manager-svc.roles
  "What the caller of an edd request may do, from the claims their token
   carried at login ([:meta :user], see manager-svc.auth):

     {:id \"bob\" :tenant \"acme\" :teams {\"demo\" :admin} :tenant-admin false :operator false}

   Roles:
     operator      server config (MANAGER_OPERATORS) or the claims parser:
                   registers tenants and sees every tenant's sync state
     tenant admin  administers their own tenant, and is admin of its teams
     team admin    manages their team (its credentials and sessions)
     member        works in their teams

   A person only ever reads or changes data of their own tenant. The
   platform itself (:non-interactive) passes every check."
  (:require [manager-svc.auth :as auth]
            [manager-svc.errors :as errors]))

(def people
  {:role [:user]})

(def platform-only
  {:role [:non-interactive]})

(def people-and-platform
  {:role [:user :non-interactive]})

(def operators-only
  "Platform operators only")

(defn caller
  [ctx]
  (get-in ctx [:meta :user]))

(defn tenant-of
  "The caller's tenant, nil for the platform and people without one."
  [ctx]
  (:tenant (caller ctx)))

(defn same-tenant?
  [caller tenant]
  (and (some? tenant) (= tenant (:tenant caller))))

(defn operator?
  [caller]
  (true? (:operator caller)))

(defn tenant-admin?
  [caller tenant]
  (and (same-tenant? caller tenant) (true? (:tenant-admin caller))))

(defn claimed-team-ids
  "Teams of tenant the caller's claims name."
  [caller tenant]
  (if (same-tenant? caller tenant)
    (set (map name (keys (:teams caller))))
    #{}))

(defn- claimed-role
  "The role the claims give in team-id. Teams may be keyed by strings or
   keywords, in a sorted map that can't be looked up with the other kind."
  [caller team-id]
  (some (fn [[claimed role]]
          (when (= (name team-id) (name claimed))
            (keyword role)))
        (:teams caller)))

(defn claimed-team-role
  ":admin, :member or nil: the role the caller's claims give in team-id,
   without what being a tenant admin adds."
  [caller tenant team-id]
  (when (same-tenant? caller tenant)
    (claimed-role caller team-id)))

(defn team-role
  ":admin, :member or nil. Tenant admins are admins of every team of their
   tenant."
  [caller tenant team-id]
  (cond
    (not (same-tenant? caller tenant)) nil
    (tenant-admin? caller tenant) :admin
    :else (claimed-role caller team-id)))

(defn member?
  [caller tenant team-id]
  (some? (team-role caller tenant team-id)))

(defn team-admin?
  [caller tenant team-id]
  (= :admin (team-role caller tenant team-id)))

(defn require-tenant!
  "Fails with 404 unless the caller belongs to tenant: other tenants' data
   does not exist for them."
  ([ctx tenant]
   (require-tenant! ctx tenant "Not found"))
  ([ctx tenant message]
   (when-not (or (auth/platform? ctx) (same-tenant? (caller ctx) tenant))
     (errors/not-found! message))))

(defn require-tenant-admin!
  ([ctx]
   (require-tenant-admin! ctx (tenant-of ctx)))
  ([ctx tenant]
   (when-not (or (auth/platform? ctx) (tenant-admin? (caller ctx) tenant))
     (errors/forbidden! "Tenant administrators only"))))

(defn require-team-admin!
  [ctx tenant team-id]
  (when-not (or (auth/platform? ctx) (team-admin? (caller ctx) tenant team-id))
    (errors/forbidden! (str "Only admins of team " team-id " can do this"))))

(defn require-operator!
  [ctx]
  (when-not (or (auth/platform? ctx) (operator? (caller ctx)))
    (errors/forbidden! operators-only)))
