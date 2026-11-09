(ns manager-web.accounts.views
  (:require ["@mui/material/Chip" :default Chip]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Typography" :default Typography]
            [manager-web.accounts.elements :as els]
            [manager-web.accounts.events :as events]
            [manager-web.components.credential-form :as credential-form]
            [manager-web.components.credential-scope :as scope]
            [manager-web.components.layout :as layout]
            [manager-web.components.required-credentials :as required-credentials]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [clojure.string :as string]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [web.primitives.components :refer [RawBox RawStack RawTypography]]))

(defn names
  "Display names for a credential's explanation: the team when it applies to
   just one of the person's teams, and the tenant."
  [me credential]
  (let [teams
        (scope/applies-to credential)]
    (cond-> {:tenant (:tenant me)}
      (= 1 (count teams)) (assoc :team (roles/team-name me (ffirst teams))))))

(defn link-label
  [me {:keys [kind team-id tenant]}]
  (if (= :team kind)
    (str "Set for " (roles/team-name me team-id))
    (str "Set for " tenant)))

(defn- breakdown
  [me {:keys [id] :as credential}]
  (when-let [items
             (seq (scope/team-breakdown #(roles/team-name me %) credential))]
    [RawTypography {:variant "caption" :color "text.secondary" :component "div" :el-id (els/breakdown id)}
     (string/join " · " (map second items))]))

(defn- admin-actions
  [me {:keys [id] :as credential} links]
  [:> Typography {:variant "body2" :component "div" :data-testid (el/id (els/explanation id))}
   (scope/admin-explanation credential (names me credential)) " "
   (into [:<>]
         (interpose " · "
                    (for [{:keys [kind team-id] :as link} links]
                      ^{:key (str kind team-id)}
                      [layout/link (assoc link :el-id (els/admin-link id (or team-id :tenant)))
                       (link-label me link)])))])

(defn- credential-row
  [me {:keys [id name description hint updated-at verified-at personal-only] :as credential}]
  (let [own?
        (= :own (scope/resolution credential))

        override?
        (scope/may-override? credential)

        links
        (when-not override?
          (scope/admin-links me credential {:team-admin? #(roles/team-admin? me %)
                                            :tenant-admin? (roles/tenant-admin? me)}))]
    [layout/list-row {:el-id (els/credential id)}
     [RawBox {:sx (:grow theme/sx)}
      [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
       [RawTypography {:variant "h3"} name]
       [scope/resolution-chip credential (els/resolution id)]
       (when (required-credentials/required? credential)
         [required-credentials/required-chip (els/required id)])
       (when (and personal-only (not own?) (not (required-credentials/required? credential)))
         [:> Chip {:color "warning" :label "Needs your credential" :data-testid (el/id (els/needs-own id))}])
       (when-not override?
         [:> Chip {:variant "outlined" :label "Set by admins" :data-testid (el/id (els/read-only id))}])]
      [RawTypography {:variant "body2" :color "text.secondary"} description]
      (when-let [text
                 (required-credentials/required-by-text me credential)]
        [RawTypography {:variant "body2" :color (if own? "text.secondary" "warning.main") :el-id (els/required-by id)}
         text])
      (if (seq links)
        [admin-actions me credential links]
        [RawTypography {:variant "body2" :el-id (els/explanation id)} (scope/explanation credential (names me credential))])
      [breakdown me credential]
      (when own?
        [:> Typography {:variant "caption" :color "text.secondary"}
         [layout/mono hint]
         (str " · saved " (format/relative-time updated-at) (when verified-at " · checked"))])]
     (when override?
       [credential-form/actions {:scope :mine
                                 :credential credential
                                 :has-value? own?
                                 :set-label "Add yours"
                                 :set-el-id (els/edit id)
                                 :clear (when own?
                                          {:label "Remove" :el-id (els/remove-credential id)
                                           :error ::events/action :refetch [:my-credentials]})}])]))

(defn- credential-list
  [me {:keys [credentials]}]
  (if (empty? credentials)
    [layout/empty-state {:title "Nothing to connect" :el-id els/empty-state}
     (if (empty? (:teams me))
       (str "You're in " (:tenant me) " but not in a team yet. Credentials belong to your teams' tools; ask your tenant admin to add you to a team.")
       "None of your teams' tools use credentials.")]
    (into [:> Paper {:data-testid (el/id els/credentials)}]
          (for [credential credentials]
            ^{:key (:id credential)}
            [credential-row me credential]))))

(defn main-panel
  []
  (let [action-error
        @(rf/subscribe [::data/error ::events/action])

        me
        @(rf/subscribe [::session/me])]
    [layout/page-stack
     [layout/page-header {:title "Accounts"
                          :el-id els/page
                          :description (str "Credentials tools use on your behalf. Used in this order: yours, then the team's, then "
                                            (format/possessive (:tenant me)) ". Yours are stored encrypted and added to requests by the platform gateway; agents never see them.")}]
     [layout/error-alert action-error els/action-error]
     [data/loaded {:key :my-credentials :query required-credentials/query :error-el-id els/error}
      #(vector credential-list me %)]
     [credential-form/credential-dialog {:scope :mine :refetch [:my-credentials]}]]))
