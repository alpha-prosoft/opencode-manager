(ns manager-svc.credentials.commands
  "Setting and deleting credential values of the three scopes: a person's
   own (:user), a team's (:team, its admins) and the tenant's (:tenant, its
   admins). Values arrive sealed by the edge (manager-svc.credentials.edge)
   for the credential and its owner, so no plaintext reaches edd's request
   log. A value is opened and checked against its service by the
   :check-credential query dependency before it is saved; the command fails
   with the check's message when it is rejected. Every value belongs to the
   caller's tenant."
  (:require [clojure.string :as string]
            [edd.core :as edd]
            [edd.el.ctx :as el-ctx]
            [manager-svc.auth :as auth]
            [manager-svc.credentials.check :as check]
            [manager-svc.credentials.spec :as spec]
            [manager-svc.credentials.values :as values]
            [manager-svc.credentials.vault :as vault]
            [manager-svc.directory :as directory]
            [manager-svc.errors :as errors]
            [manager-svc.platform.api :as platform-api]
            [manager-svc.roles :as roles]
            [manager-svc.system :as system]
            [manager-svc.time :as time]))

(defn- declared!
  [tenant-spec credential-id]
  (or (spec/credential tenant-spec credential-id)
      (errors/not-found! "Unknown credential")))

(defn owner-of
  "{:tenant :team-id :user} of the value the caller sets in scope."
  [ctx scope team-id]
  (case scope
    :user {:tenant (roles/tenant-of ctx) :user (auth/user-id ctx)}
    :team {:tenant (roles/tenant-of ctx) :team-id team-id}
    :tenant {:tenant (roles/tenant-of ctx)}))

(defn- allowed-for-user!
  [ctx credential]
  (let [teams
        (platform-api/teams-of (system/of ctx) (roles/caller ctx))]
    (cond
      (not (spec/personal? credential))
      (errors/forbidden! (str (:name credential) " is managed by your tenant or team admins"))

      (not (spec/for-teams? (:teams credential) teams))
      (errors/forbidden! (str (:name credential) " is not used by your teams")))))

(defn- allowed-for-team!
  [ctx credential team-id]
  (let [tenant
        (roles/tenant-of ctx)]
    (roles/require-team-admin! ctx tenant team-id)
    (when-not (platform-api/team (system/of ctx) tenant team-id)
      (errors/not-found! "Team not found"))
    (cond
      (not (spec/provides? credential :team))
      (errors/invalid! (str (:name credential) " has no team values (see its provided_by)"))

      (not (spec/for-teams? (:teams credential) [team-id]))
      (errors/forbidden! (str (:name credential) " is not used by team " team-id)))))

(defn- allowed-for-tenant!
  [ctx credential]
  (roles/require-tenant-admin! ctx)
  (cond
    (spec/personal-only? credential)
    (errors/invalid! (str (:name credential) " is personal only: every user sets their own value"))

    (not (spec/provides? credential :tenant))
    (errors/invalid! (str (:name credential) " has no tenant value (see its provided_by)"))))

(defn- declaration-for
  "The credential the caller may set in scope; fails when they may not."
  [ctx scope credential-id team-id]
  (let [tenant-spec
        (spec/spec (system/of ctx) (roles/tenant-of ctx))]
    (case scope
      :tenant (let [credential
                    (or (spec/credential tenant-spec credential-id) (spec/undeclared credential-id))]
                (allowed-for-tenant! ctx credential)
                credential)
      :team (let [credential
                  (declared! tenant-spec credential-id)]
              (allowed-for-team! ctx credential team-id)
              credential)
      :user (let [credential
                  (declared! tenant-spec credential-id)]
              (allowed-for-user! ctx credential)
              credential))))

(defn value-of
  [credential {:keys [secret username]}]
  (cond
    (string/blank? secret)
    (errors/invalid! "A secret is required")

    (> (count secret) 16384)
    (errors/invalid! "The secret is too long")

    (and (= :basic (:kind credential)) (string/blank? username))
    (errors/invalid! (str (:name credential) " needs a username as well"))

    :else
    (cond-> {:secret (if (= :ssh-key (:kind credential)) secret (string/trim secret))}
      (not (string/blank? username)) (assoc :username (string/trim username)))))

(defn opened
  "The {:secret :username} sealed for credential-id and owner."
  [ctx owner credential-id sealed]
  (vault/open (system/settings ctx) credential-id owner sealed "The value was not sealed for this credential"))

(defn check-credential
  "{:ok :message :status} for a value about to be saved; the dependency of
   the set commands."
  [ctx {:keys [credential-id scope team-id sealed]}]
  (try
    (let [credential
          (declaration-for ctx scope credential-id team-id)

          owner
          (values/owner scope (owner-of ctx scope team-id))

          value
          (value-of credential (opened ctx owner credential-id sealed))

          {:keys [ok message]}
          (check/test-value credential value (spec/mcp-servers (spec/spec (system/of ctx) (roles/tenant-of ctx))))]
      (if ok
        {:ok true :message message}
        {:ok false :status 422 :message (str "Not saved. " message)}))
    (catch clojure.lang.ExceptionInfo e
      (if-let [status
               (:status (ex-data e))]
        {:ok false :status status :message (ex-message e)}
        (throw e)))))

(defn- check-dep
  [scope]
  (fn [_ctx {:keys [attrs]}]
    (cond-> {:query-id :check-credential
             :scope scope
             :credential-id (:credential-id attrs)
             :sealed (:sealed attrs)}
      (:team-id attrs) (assoc :team-id (:team-id attrs)))))

(defn- passed!
  [ctx]
  (let [{:keys [ok status message]}
        (:check ctx)]
    (when-not ok
      (errors/fail! (or status 422) (or message "The credential could not be checked")))))

(defn- value-set
  [ctx scope credential team-id attrs]
  (let [settings
        (system/settings ctx)

        owner-attrs
        (owner-of ctx scope team-id)

        owner
        (values/owner scope owner-attrs)

        value
        (value-of credential (opened ctx owner (:id credential) (:sealed attrs)))

        now
        (time/now)]
    {:event-id :credential-value-set
     :attrs {:credential-id (:id credential)
             :owner owner
             :scope scope
             :tenant (:tenant owner-attrs)
             :team-id (:team-id owner-attrs)
             :username (:user owner-attrs)
             :sealed (vault/seal settings (:id credential) owner value)
             :hint (vault/hint value)
             :updated-at now
             :updated-by (auth/user-id ctx)
             :verified-at (when (check/verifiable? credential) now)}}))

(defn- setter
  [scope]
  (fn [ctx {:keys [attrs]}]
    (passed! ctx)
    (let [credential
          (declaration-for ctx scope (:credential-id attrs) (:team-id attrs))]
      (value-set ctx scope credential (:team-id attrs) attrs))))

(def set-my-credential
  (setter :user))

(def set-team-credential
  (setter :team))

(def set-tenant-credential
  (setter :tenant))

(defn- value-deleted
  [ctx credential-id]
  (let [current
        (el-ctx/get-aggregate ctx)]
    (when (values/present? current)
      {:event-id :credential-value-deleted
       :attrs {:credential-id credential-id
               :owner (:owner current)
               :updated-at (time/now)
               :updated-by (auth/user-id ctx)}})))

(defn delete-my-credential
  [ctx {:keys [attrs]}]
  (value-deleted ctx (:credential-id attrs)))

(defn delete-team-credential
  [ctx {:keys [attrs]}]
  (roles/require-team-admin! ctx (roles/tenant-of ctx) (:team-id attrs))
  (value-deleted ctx (:credential-id attrs)))

(defn delete-tenant-credential
  [ctx {:keys [attrs]}]
  (roles/require-tenant-admin! ctx)
  (value-deleted ctx (:credential-id attrs)))

(defn- prompts-owner
  "{:tenant :username} of the person whose prompts a command marks: the
   caller, or the one the platform names."
  [ctx attrs]
  (if (auth/platform? ctx)
    (select-keys attrs [:tenant :username])
    {:tenant (roles/tenant-of ctx) :username (auth/user-id ctx)}))

(defn mark-credentials-seen
  [ctx {:keys [attrs]}]
  (let [{:keys [tenant username]}
        (prompts-owner ctx attrs)

        already
        (set (:seen (el-ctx/get-aggregate ctx)))

        fresh
        (vec (remove already (map name (:credentials attrs))))]
    (when (and username (seq fresh))
      {:event-id :credentials-seen
       :attrs {:tenant tenant
               :username username
               :credentials fresh
               :seen-at (time/now)}})))

(defn seen-after-set
  "Saving your own value counts as having seen its prompt."
  [_ctx {:keys [attrs]}]
  (when (and (= :user (keyword (:scope attrs))) (:username attrs))
    {:cmd-id :mark-credentials-seen
     :id (values/prompts-id (:tenant attrs) (:username attrs))
     :attrs {:tenant (:tenant attrs)
             :username (:username attrs)
             :credentials [(:credential-id attrs)]}}))

(defn- value-id-of
  [scope]
  (fn [ctx {:keys [attrs]}]
    (values/value-id (:credential-id attrs)
                     (values/owner scope (owner-of ctx scope (:team-id attrs))))))

(defn- prompts-id
  [ctx {:keys [attrs]}]
  (let [{:keys [tenant username]}
        (prompts-owner ctx attrs)]
    (values/prompts-id tenant username)))

(def ^:private CredentialAttrs
  [:map [:credential-id :string]])

(def ^:private TeamCredentialAttrs
  [:map [:credential-id :string] [:team-id :string]])

(def ^:private ValueAttrs
  [:map {:closed true}
   [:credential-id :string]
   [:sealed :string]])

(def ^:private TeamValueAttrs
  [:map {:closed true}
   [:credential-id :string]
   [:team-id :string]
   [:sealed :string]])

(defn- consumes
  [attrs]
  [:map [:attrs attrs]])

(def ^:private users
  {:role [:user]})

(def ^:private value-commands
  [[:set-my-credential set-my-credential :user ValueAttrs true]
   [:delete-my-credential delete-my-credential :user CredentialAttrs false]
   [:set-team-credential set-team-credential :team TeamValueAttrs true]
   [:delete-team-credential delete-team-credential :team TeamCredentialAttrs false]
   [:set-tenant-credential set-tenant-credential :tenant ValueAttrs true]
   [:delete-tenant-credential delete-tenant-credential :tenant CredentialAttrs false]])

(defn- reg-value-command
  [ctx [cmd-id handler scope attrs checked?]]
  (apply edd/reg-cmd ctx cmd-id handler
         (concat [:consumes (consumes attrs)
                  :id-fn (value-id-of scope)
                  :auth users]
                 (when checked?
                   [:deps [:check (check-dep scope)]]))))

(defn register
  [ctx]
  (let [with-check
        (edd/reg-query ctx :check-credential check-credential
                       :consumes [:map
                                  [:credential-id :string]
                                  [:scope [:enum :user :team :tenant]]
                                  [:team-id {:optional true} :string]
                                  [:sealed [:maybe :string]]]
                       :auth {:role [:non-interactive]})

        with-values
        (reduce reg-value-command with-check value-commands)

        with-seen
        (edd/reg-cmd with-values :mark-credentials-seen mark-credentials-seen
                     :consumes (consumes [:map
                                          [:credentials [:vector :string]]
                                          [:tenant {:optional true} :string]
                                          [:username {:optional true} :string]])
                     :id-fn prompts-id
                     :auth {:role [:user :non-interactive]})

        with-events
        (-> with-seen
            (edd/reg-event :credential-value-set values/apply-value-set)
            (edd/reg-event :credential-value-deleted values/apply-value-deleted)
            (edd/reg-event :credentials-seen values/apply-seen))]
    (directory/reg-rows (edd/reg-event-fx with-events :credential-value-set seen-after-set)
                        #{:credential-value-set :credential-value-deleted}
                        values/directory-rows)))
