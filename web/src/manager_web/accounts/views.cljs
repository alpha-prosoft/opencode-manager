(ns manager-web.accounts.views
  (:require ["@mui/material/Button" :default Button]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Typography" :default Typography]
            [manager-web.accounts.elements :as els]
            [manager-web.accounts.events :as events]
            [manager-web.components.credential-form :as credential-form]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [web.primitives.components :refer [RawBox RawStack RawTypography]]))

(defn- state-chip
  [{:keys [state required]}]
  (case (format/kw state)
    :own [:> Chip {:color "success" :label "Yours"}]
    :platform [:> Chip {:label "Shared account"}]
    [:> Chip {:color (if required "warning" "default")
              :label (if required "Needs your credential" "Not set")}]))

(defn- credential-row
  [{:keys [id name description hint updated-at verified-at state] :as credential}]
  (let [own?
        (= :own (format/kw state))]
    [layout/list-row {:el-id (els/credential id)}
     [RawBox {:sx (:grow theme/sx)}
      [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
       [RawTypography {:variant "h3"} name]
       [state-chip credential]]
      [RawTypography {:variant "body2" :color "text.secondary"} description]
      (when own?
        [:> Typography {:variant "caption" :color "text.secondary"}
         [layout/mono hint]
         (str " · saved " (format/relative-time updated-at) (when verified-at " · checked"))])]
     [RawStack {:direction "row" :spacing 1}
      [:> Button {:variant (if own? "text" "contained") :data-testid (el/id (els/edit id))
                  :on-click #(rf/dispatch [::credential-form/edit :mine credential])}
       (if own? "Replace" "Add yours")]
      (when own?
        [:> Button {:color "error" :data-testid (el/id (els/remove-credential id))
                    :on-click #(rf/dispatch [::events/remove credential])}
         "Remove"])]]))

(defn- describe
  [credential]
  {:title (str "Your " (:name credential) " credential")
   :help (:help credential)
   :checks? (some? (:test credential))})

(defn- credential-list
  [{:keys [credentials]}]
  (if (empty? credentials)
    [layout/empty-state {:title "Nothing to connect" :el-id els/empty-state}
     "None of your teams' tools need personal credentials."]
    (into [:> Paper {:data-testid (el/id els/credentials)}]
          (for [credential credentials]
            ^{:key (:id credential)}
            [credential-row credential]))))

(defn main-panel
  []
  (let [action-error
        @(rf/subscribe [::data/error ::events/action])]
    [layout/page-stack
     [layout/page-header {:title "Accounts"
                          :el-id els/page
                          :description "Credentials tools use on your behalf. They are stored encrypted on the platform and added to requests by the platform gateway; agents never see them."}]
     [layout/error-alert action-error els/action-error]
     [data/loaded {:key :my-credentials :query {:query-id :get-my-credentials} :error-el-id els/error}
      credential-list]
     [credential-form/credential-dialog {:scope :mine :refetch [:my-credentials] :describe describe}]]))
