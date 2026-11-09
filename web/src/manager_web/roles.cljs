(ns manager-web.roles
  "What the logged-in person may do and how their place in the tenant reads,
   from :get-me ({:tenant :tenant-admin :operator :teams [{:id :name :role
   :claimed-role}]}). :role is the effective role (tenant admins are admins
   of every team), :claimed-role the one the identity provider gives. The
   service decides; the UI only hides what it would refuse."
  (:require [clojure.string :as string]
            [manager-web.format :as format]))

(defn operator?
  [me]
  (true? (:operator me)))

(defn tenant-admin?
  [me]
  (and (some? (:tenant me)) (true? (:tenant-admin me))))

(defn platform-only?
  "An operator who belongs to no tenant."
  [me]
  (and (operator? me) (nil? (:tenant me))))

(defn team-ids
  [me]
  (mapv :id (:teams me)))

(defn team-of
  [me team-id]
  (some #(when (= (str team-id) (str (:id %))) %) (:teams me)))

(defn team-name
  "The display name of a team of the person's tenant, else the id."
  [me team-id]
  (or (:name (team-of me team-id))
      (some #(when (= (str team-id) (str (:id %))) (:name %)) (:tenant-teams me))
      team-id))

(defn team-role
  ":admin, :member or nil. Tenant admins are admins of every team of their tenant."
  [me team-id]
  (cond
    (tenant-admin? me) :admin
    :else (some-> (team-of me team-id) :role format/kw)))

(defn claimed-role
  ":admin, :member or nil: the role the identity provider gives in the team,
   without what being a tenant admin adds."
  [me team-id]
  (when-let [team
             (team-of me team-id)]
    (if (contains? team :claimed-role)
      (format/kw (:claimed-role team))
      (when-not (tenant-admin? me) (format/kw (:role team))))))

(defn member-teams
  "The teams the identity provider puts the person in."
  [me]
  (filterv #(claimed-role me (:id %)) (:teams me)))

(defn can-open-team?
  [me team-id]
  (some? (team-role me team-id)))

(defn team-admin?
  [me team-id]
  (= :admin (team-role me team-id)))

(defn admin-of-a-team?
  [me]
  (boolean (some #(= :admin (claimed-role me (:id %))) (:teams me))))

(defn role-in-team
  ":admin, :member, :tenant-admin (an admin through the tenant, not a member) or nil."
  [me team-id]
  (let [claimed
        (claimed-role me team-id)]
    (cond
      (= :admin claimed) :admin
      (= :member claimed) :member
      (tenant-admin? me) :tenant-admin
      :else nil)))

(def role-in-team-labels
  {:admin "admin"
   :member "member"
   :tenant-admin "tenant admin, not a member"})

(defn role-in-team-label
  [me team-id]
  (role-in-team-labels (role-in-team me team-id)))

(defn- names
  [me teams]
  (if (< 2 (count teams))
    (str (count teams) " teams")
    (string/join ", " (map #(team-name me (:id %)) teams))))

(defn role-label
  "Who the person is in their tenant, in a few words."
  [me]
  (let [teams
        (member-teams me)

        admin-of
        (filterv #(= :admin (claimed-role me (:id %))) teams)

        member-of
        (filterv #(= :member (claimed-role me (:id %))) teams)]
    (cond
      (platform-only? me) "Platform operator"
      (tenant-admin? me) "Tenant admin"
      (seq admin-of) (str "Admin of " (names me admin-of)
                          (when (seq member-of) (str " · member of " (names me member-of))))
      (seq member-of) (str "Member of " (names me member-of))
      :else "Not in a team yet")))

(defn team-role-lines
  "One line per team of the person: \"Admin · Operations\"."
  [me]
  (for [{:keys [id]} (:teams me)
        :let [role (role-in-team me id)]
        :when role]
    (str (string/capitalize (role-in-team-labels role)) " · " (team-name me id))))

(defn tenant-label
  [me]
  (or (:tenant me) (when (operator? me) "Platform") "No tenant"))

(defn visibility-label
  "Who sees something, from the viewer's side. Takes the tenant, or opts
   :tenant, :team-name, :owner (whose private thing it is) and :owner? (the
   viewer owns it)."
  [visibility tenant-or-opts]
  (let [{:keys [tenant team-name owner owner?]}
        (if (map? tenant-or-opts) tenant-or-opts {:tenant tenant-or-opts :owner? true})]
    (case (format/kw visibility)
      :private (if (or owner? (string/blank? owner)) "Only me" (str "Private to " owner))
      :team (if (string/blank? team-name) "Team" (str "Team " team-name))
      :tenant (str "Everyone in " (or tenant "the tenant"))
      (some-> visibility name))))

(defn visibility-options
  "Who can be chosen to see a session: a team session has no owner to keep
   it private."
  [team-session?]
  (if team-session? [:team :tenant] [:private :team :tenant]))

(defn credential-order
  "Whose credentials work in team-id runs with, most specific first:
   \"your own where set, else Operations', else acme's\". whose is \"your\"
   or \"their\" for a person's work, nil for the team's own."
  [me team-id whose]
  (str (when whose (str whose " own where set, else "))
       (format/possessive (team-name me team-id))
       ", else "
       (format/possessive (or (:tenant me) "the tenant"))))

(defn session-owner
  "Who owns a session, as the viewer reads it."
  [me {:keys [is-owner team-session team-id sub]}]
  (cond
    is-owner "You"
    team-session (str "Team " (team-name me team-id))
    :else sub))

(defn session-visibility-label
  [me {:keys [visibility team-id is-owner team-session sub]}]
  (visibility-label visibility {:tenant (:tenant me)
                                :team-name (team-name me team-id)
                                :owner (if team-session (str "the admins of " (team-name me team-id)) sub)
                                :owner? is-owner}))

(defn admin-access
  "Why the viewer sees a session only through an admin role (\"Visible to
   you as Operations admin\"), or nil when they would see it anyway."
  [me {:keys [is-owner visibility team-id]}]
  (let [claimed
        (claimed-role me team-id)

        visibility
        (format/kw visibility)]
    (when (and (not is-owner)
               (or (= :private visibility)
                   (and (= :team visibility) (nil? claimed))))
      (cond
        (= :admin claimed) (str "Visible to you as " (team-name me team-id) " admin")
        (tenant-admin? me) "Visible to you as tenant admin"
        :else nil))))
