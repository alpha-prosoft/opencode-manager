(ns manager-web.components.required-credentials
  "Credentials people must provide themselves: tenant.json requires some of
   everyone in the teams they apply to, a team's team.json of its members.
   Only the person's own value counts; team and tenant values don't. Until
   they add them the app is closed to them: every page sends them to setup."
  (:require ["@mui/material/Chip" :default Chip]
            [clojure.string :as string]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]))

(def query
  {:query-id :get-my-credentials})

(defn own?
  [credential]
  (= :own (format/kw (:state credential))))

(defn required?
  [credential]
  (true? (:required credential)))

(defn missing-required
  "Required credentials the person has no own value of, in the order the
   service lists them."
  [{:keys [credentials missing-required]}]
  (let [missing
        (set (map str missing-required))]
    (filterv #(contains? missing (str (:id %))) credentials)))

(defn join-names
  [names]
  (if (< (count names) 2)
    (str (first names))
    (str (string/join ", " (butlast names)) " and " (last names))))

(defn required-by-text
  "Who requires the person's own value: \"Required by acme and Operations\"."
  [me {:keys [required-by]}]
  (let [requirers
        (concat (when (:tenant required-by) [(or (:tenant me) "the tenant")])
                (map #(roles/team-name me %) (:teams required-by)))]
    (when (seq requirers)
      (str "Required by " (join-names requirers) ". Only your own value counts, not a team or tenant value."))))

(defn required-chip
  [el-id]
  [:> Chip {:color "warning" :size "small" :label "Required" :data-testid (el/id el-id)}])

(defn administers?
  "True for the pages that stay open to those who run them, whatever they
   owe: a tenant's Admin to its admins, the platform's Tenants to operators.
   A requirement nobody can meet is repaired from there."
  [me panel]
  (case panel
    :admin (roles/tenant-admin? me)
    :tenants (roles/operator? me)
    false))

(defn gate
  "What a person may see of the app on panel, from the load of their
   credentials: :open, :loading while that isn't known, :failed when it
   couldn't be read, or :setup while they lack a required credential, which
   holds them on the setup page."
  [me panel {:keys [data error loading?]}]
  (cond
    (not (:tenant me)) :open
    (administers? me panel) :open
    (and (nil? data) loading?) :loading
    (and (nil? data) error) :failed
    (nil? data) :loading
    (empty? (missing-required data)) :open
    loading? :loading
    :else :setup))

(defn source-labels
  "[[source label]]: where a team's requirement comes from, tenant.json (the
   tenant admins) and/or the team's own team.json (its admins)."
  [me team-name {:keys [tenant team]}]
  (cond-> []
    tenant (conj [:tenant (str "Required by " (or (:tenant me) "the tenant") " (tenant.json)")])
    team (conj [:team (str "Required by " team-name " (team.json)")])))

(defn- required-in-team?
  [credential team-id]
  (boolean (some #(and (= (str team-id) (str (:team-id %))) (true? (:required %)))
                 (:teams credential))))

(defn own-state
  "What a credential team-id requires means for the viewer: :own when they
   added their value, :missing when the team requires it of them and they
   haven't, :not-required when it is required of the team's members and
   they aren't one (a tenant admin looking in), nil when their credentials
   don't list it."
  [my-credentials team-id credential-id]
  (when-let [credential
             (some #(when (= (str credential-id) (str (:id %))) %) (:credentials my-credentials))]
    (cond
      (own? credential) :own
      (required-in-team? credential team-id) :missing
      :else :not-required)))

(def own-state-chips
  {:own ["success" "You added yours"]
   :missing ["warning" "Missing for you"]
   :not-required ["default" "Required of members, not of you"]})
