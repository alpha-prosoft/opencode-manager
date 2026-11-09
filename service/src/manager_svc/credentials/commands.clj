(ns manager-svc.credentials.commands
  "Setting and deleting credential values. Values arrive sealed by the edge
   (manager-svc.credentials.edge) for the credential and its owner, so no
   plaintext reaches edd's request log. A value is opened and checked against
   its service by the :check-credential query dependency before it is saved;
   the command fails with the check's message when it is rejected."
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
            [manager-svc.system :as system]
            [manager-svc.time :as time]))

(defn- declared!
  [platform-spec credential-id]
  (or (spec/credential platform-spec credential-id)
      (errors/not-found! "Unknown credential")))

(defn- platform-declaration
  [platform-spec credential-id]
  (or (spec/credential platform-spec credential-id)
      {:id credential-id :name credential-id :kind :token :provided-by :platform :test nil}))

(defn- allowed-for-user!
  [ctx credential]
  (let [teams
        (platform-api/teams-of (system/of ctx) (auth/user-id ctx))]
    (cond
      (not (spec/personal? credential))
      (errors/forbidden! (str (:name credential) " is managed by platform admins"))

      (not (spec/for-teams? (:teams credential) teams))
      (errors/forbidden! (str (:name credential) " is not used by your teams")))))

(defn- allowed-for-platform!
  [ctx credential]
  (platform-api/require-admin! ctx)
  (when (= :user (:provided-by credential))
    (errors/invalid! (str (:name credential) " is personal only: every user sets their own value"))))

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

(defn- owner-of
  [ctx scope]
  (if (= :platform scope)
    vault/platform-owner
    (auth/user-id ctx)))

(defn opened
  "The {:secret :username} sealed for credential-id and the scope's owner."
  [ctx scope credential-id sealed]
  (try
    (vault/unseal (system/settings ctx) credential-id (owner-of ctx scope) sealed)
    (catch Exception _
      (errors/invalid! "The value was not sealed for this credential"))))

(defn- declaration-for
  [ctx scope credential-id]
  (let [platform-spec
        (spec/spec (system/of ctx))]
    (if (= :platform scope)
      (let [credential
            (platform-declaration platform-spec credential-id)]
        (allowed-for-platform! ctx credential)
        credential)
      (let [credential
            (declared! platform-spec credential-id)]
        (allowed-for-user! ctx credential)
        credential))))

(defn check-credential
  "{:ok :message :status} for a value about to be saved; the dependency of
   :set-my-credential and :set-platform-credential."
  [ctx {:keys [credential-id scope sealed]}]
  (try
    (let [credential
          (declaration-for ctx scope credential-id)

          value
          (value-of credential (opened ctx scope credential-id sealed))

          {:keys [ok message]}
          (check/test-value credential value (spec/mcp-servers (spec/spec (system/of ctx))))]
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
    {:query-id :check-credential
     :scope scope
     :credential-id (:credential-id attrs)
     :sealed (:sealed attrs)}))

(defn- passed!
  [ctx]
  (let [{:keys [ok status message]}
        (:check ctx)]
    (when-not ok
      (errors/fail! (or status 422) (or message "The credential could not be checked")))))

(defn- value-set
  [ctx scope credential owner attrs]
  (let [settings
        (system/settings ctx)

        value
        (value-of credential (opened ctx scope (:id credential) (:sealed attrs)))

        now
        (time/now)]
    {:event-id :credential-value-set
     :attrs {:credential-id (:id credential)
             :owner owner
             :sealed (vault/seal settings (:id credential) owner value)
             :hint (vault/hint value)
             :updated-at now
             :updated-by (auth/user-id ctx)
             :verified-at (when (check/verifiable? credential) now)}}))

(defn set-my-credential
  [ctx {:keys [attrs]}]
  (passed! ctx)
  (let [credential
        (declaration-for ctx :user (:credential-id attrs))]
    (value-set ctx :user credential (auth/user-id ctx) attrs)))

(defn set-platform-credential
  [ctx {:keys [attrs]}]
  (passed! ctx)
  (let [credential
        (declaration-for ctx :platform (:credential-id attrs))]
    (value-set ctx :platform credential vault/platform-owner attrs)))

(defn- value-deleted
  [ctx credential-id owner]
  (when (values/present? (el-ctx/get-aggregate ctx))
    {:event-id :credential-value-deleted
     :attrs {:credential-id credential-id
             :owner owner
             :updated-at (time/now)
             :updated-by (auth/user-id ctx)}}))

(defn delete-my-credential
  [ctx {:keys [attrs]}]
  (declared! (spec/spec (system/of ctx)) (:credential-id attrs))
  (value-deleted ctx (:credential-id attrs) (auth/user-id ctx)))

(defn delete-platform-credential
  [ctx {:keys [attrs]}]
  (platform-api/require-admin! ctx)
  (value-deleted ctx (:credential-id attrs) vault/platform-owner))

(defn- prompts-owner
  [ctx attrs]
  (if (auth/platform? ctx)
    (:username attrs)
    (auth/user-id ctx)))

(defn mark-credentials-seen
  [ctx {:keys [attrs]}]
  (let [username
        (prompts-owner ctx attrs)

        already
        (set (:seen (el-ctx/get-aggregate ctx)))

        fresh
        (vec (remove already (map name (:credentials attrs))))]
    (when (and username (seq fresh))
      {:event-id :credentials-seen
       :attrs {:username username
               :credentials fresh
               :seen-at (time/now)}})))

(defn seen-after-set
  "Saving your own value counts as having seen its prompt."
  [_ctx {:keys [attrs]}]
  (when-not (= vault/platform-owner (:owner attrs))
    {:cmd-id :mark-credentials-seen
     :id (values/prompts-id (:owner attrs))
     :attrs {:username (:owner attrs)
             :credentials [(:credential-id attrs)]}}))

(defn- own-value-id
  [ctx {:keys [attrs]}]
  (values/value-id (:credential-id attrs) (auth/user-id ctx)))

(defn- platform-value-id
  [_ctx {:keys [attrs]}]
  (values/value-id (:credential-id attrs) vault/platform-owner))

(defn- prompts-id
  [ctx {:keys [attrs]}]
  (values/prompts-id (prompts-owner ctx attrs)))

(def ^:private CredentialAttrs
  [:map [:credential-id :string]])

(def ^:private ValueAttrs
  [:map {:closed true}
   [:credential-id :string]
   [:sealed :string]])

(defn- consumes
  [attrs]
  [:map [:attrs attrs]])

(def ^:private users
  {:role [:user]})

(defn register
  [ctx]
  (let [with-check
        (edd/reg-query ctx :check-credential check-credential
                       :consumes [:map
                                  [:credential-id :string]
                                  [:scope [:enum :user :platform]]
                                  [:sealed [:maybe :string]]]
                       :auth {:role [:non-interactive]})

        with-set-mine
        (edd/reg-cmd with-check :set-my-credential set-my-credential
                     :consumes (consumes ValueAttrs)
                     :deps [:check (check-dep :user)]
                     :id-fn own-value-id
                     :auth users)

        with-delete-mine
        (edd/reg-cmd with-set-mine :delete-my-credential delete-my-credential
                     :consumes (consumes CredentialAttrs)
                     :id-fn own-value-id
                     :auth users)

        with-seen
        (edd/reg-cmd with-delete-mine :mark-credentials-seen mark-credentials-seen
                     :consumes (consumes [:map
                                          [:credentials [:vector :string]]
                                          [:username {:optional true} :string]])
                     :id-fn prompts-id
                     :auth {:role [:user :non-interactive]})

        with-set-platform
        (edd/reg-cmd with-seen :set-platform-credential set-platform-credential
                     :consumes (consumes ValueAttrs)
                     :deps [:check (check-dep :platform)]
                     :id-fn platform-value-id
                     :auth users)

        with-delete-platform
        (edd/reg-cmd with-set-platform :delete-platform-credential delete-platform-credential
                     :consumes (consumes CredentialAttrs)
                     :id-fn platform-value-id
                     :auth users)

        with-events
        (-> with-delete-platform
            (edd/reg-event :credential-value-set values/apply-value-set)
            (edd/reg-event :credential-value-deleted values/apply-value-deleted)
            (edd/reg-event :credentials-seen values/apply-seen))]
    (directory/reg-rows (edd/reg-event-fx with-events :credential-value-set seen-after-set)
                        #{:credential-value-set :credential-value-deleted}
                        values/directory-rows)))
