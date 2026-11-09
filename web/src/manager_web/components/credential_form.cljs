(ns manager-web.components.credential-form
  "Setting and clearing a credential value of a scope (:mine, :tenant or
   [:team team-id]): the form, its dialog, the buttons of a row and the
   confirmation before a value goes. The secret is PUT to the edge, which
   seals it before it reaches edd, so it never enters the command log."
  (:require ["@mui/material/Button" :default Button]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogTitle" :default DialogTitle]
            ["@mui/material/TextField" :default TextField]
            [clojure.string :as string]
            [manager-web.components.confirm :as confirm]
            [manager-web.components.elements :as els]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawStack RawTypography]]))

(def secret-labels
  {:token "Token"
   :basic "Password or API token"
   :ssh-key "Private key"})

(defn credential-value
  [kind {:keys [secret username]}]
  (cond-> {:secret secret}
    (= :basic kind) (assoc :username username)))

(def ^:private paths
  {:mine "/api/credentials/"
   :tenant "/api/admin/credentials/"})

(defn- scope-path
  "scope is :mine, :tenant or [:team team-id]."
  [scope]
  (if (vector? scope)
    (str "/api/teams/" (js/encodeURIComponent (str (second scope))) "/credentials/")
    (get paths scope)))

(defn run-id
  [scope credential-id]
  [::save scope credential-id])

(defn save-path
  [scope credential-id]
  (str (scope-path scope) (js/encodeURIComponent (str credential-id))))

(defn save-spec
  "The ::data/run spec that stores value. refetch lists load keys to refresh
   and on-saved an event, both after a successful save."
  [{:keys [scope credential-id value refetch on-saved]}]
  {:id (run-id scope credential-id)
   :method "PUT"
   :path (save-path scope credential-id)
   :body value
   :edd? true
   :refetch refetch
   :on-success on-saved})

(defn credential-form
  [{:keys [name kind help checks? scope credential-id refetch on-saved extra-actions]}]
  (r/with-let [state
               (r/atom {:secret "" :username ""})]
    (let [kind
          (format/kw kind)

          busy?
          @(rf/subscribe [::data/busy? (run-id scope credential-id)])

          error
          @(rf/subscribe [::data/error (run-id scope credential-id)])

          ssh?
          (= :ssh-key kind)]
      [:form {:on-submit (fn [e]
                           (.preventDefault e)
                           (rf/dispatch [::data/run (save-spec {:scope scope
                                                                :credential-id credential-id
                                                                :value (credential-value kind @state)
                                                                :refetch refetch
                                                                :on-saved on-saved})]))}
       [RawStack {:spacing 2 :sx (:form-top theme/sx)}
        (when-not (string/blank? help)
          [RawTypography {:variant "body2" :color "text.secondary"} help])
        (when (= :basic kind)
          [:> TextField {:label "Username" :autoComplete "off" :required true :id (el/id els/credential-username)
                         :on-change #(swap! state assoc :username (.. % -target -value))}])
        [:> TextField {:label (get secret-labels kind "Secret")
                       :type (if ssh? "text" "password")
                       :multiline ssh?
                       :minRows (when ssh? 4)
                       :autoComplete "off"
                       :required true
                       :id (el/id els/credential-secret)
                       :helperText "Stored encrypted. It is never shown again and never reaches agent sandboxes."
                       :on-change #(swap! state assoc :secret (.. % -target -value))}]
        (when error
          [RawAlert {:severity "error" :el-id els/credential-error} error])
        (into [RawStack {:direction "row" :spacing 1}
               [:> Button {:type "submit" :variant "contained" :loading busy?
                           :disabled (string/blank? (:secret @state))
                           :data-testid (el/id els/credential-submit)}
                (if checks? (str "Check and save " name) (str "Save " name))]]
              extra-actions)]])))

(rf/reg-event-db
 ::edit
 (fn [db [_ scope credential]]
   (-> db
       (assoc ::editing credential)
       (data/clear-error (run-id scope (:id credential))))))

(defn close
  [db]
  (dissoc db ::editing))

(rf/reg-event-db
 ::close
 (fn [db _]
   (close db)))

(rf/reg-sub ::editing (fn [db _] (::editing db)))

(defn- scope-kind
  [scope]
  (if (vector? scope) :team scope))

(defn tenant-help
  "Who a tenant value serves, from who else may provide the credential."
  [{:keys [provided-by]}]
  (let [allowed
        (format/scopes provided-by)]
    (cond
      (and (allowed :team) (allowed :user))
      "Used by everyone in the tenant unless their team or they themselves set their own, and by automated runs."

      (allowed :team)
      "Used by every team that hasn't set its own value, and by automated runs."

      (allowed :user)
      "Used by everyone who hasn't added their own, and by automated runs."

      :else
      "Used by everyone in the tenant and by automated runs.")))

(defn describe
  "The dialog's :title, :help and :checks? for a credential value of scope."
  [scope {:keys [name help test] :as credential}]
  (case (scope-kind scope)
    :mine {:title (str "Your " name " credential")
           :help help
           :checks? (some? test)}
    :team {:title (str "Team value of " name)
           :help (or help "Used by this team's sessions and runs instead of the tenant value; people may still use their own where allowed.")
           :checks? (some? test)}
    :tenant {:title (str "Tenant value of " name)
             :help (tenant-help credential)}))

(defn credential-dialog
  "The dialog opened by [::edit scope credential]. refetch lists the load
   keys to refresh after a save."
  [{:keys [scope refetch]}]
  (let [editing
        @(rf/subscribe [::editing])

        close
        #(rf/dispatch [::close])]
    [:> Dialog {:open (some? editing) :on-close close :fullWidth true :maxWidth "sm"
                :data-testid (el/id els/credential-dialog)}
     (when editing
       (let [{:keys [title help checks?]}
             (describe scope editing)]
         [:<>
          [:> DialogTitle title]
          [:> DialogContent
           ^{:key (:id editing)}
           [credential-form
            {:name (:name editing)
             :kind (:kind editing)
             :help help
             :checks? checks?
             :scope scope
             :credential-id (:id editing)
             :refetch refetch
             :on-saved [::close]
             :extra-actions [[:> Button {:data-testid (el/id els/credential-cancel) :on-click close}
                              "Cancel"]]}]]]))]))

(defn clear-command
  [scope credential-id]
  (case (scope-kind scope)
    :mine {:cmd-id :delete-my-credential :attrs {:credential-id credential-id}}
    :team {:cmd-id :delete-team-credential :attrs {:team-id (second scope) :credential-id credential-id}}
    :tenant {:cmd-id :delete-tenant-credential :attrs {:credential-id credential-id}}))

(defn- clear-texts
  [scope {:keys [name provided-by required]}]
  (case (scope-kind scope)
    :mine {:title (str "Remove your " name " credential?")
           :body (cond
                   required "Your teams require your own value, so the platform stays closed to you until you add it again."
                   (some #{:tenant :team} (format/scopes provided-by)) "Tools will use the shared team or tenant account again, where one is set."
                   :else "Tools that need it stop working for you until you add it again.")
           :confirm "Remove"}
    :team {:title (str "Clear the team value of " name "?")
           :body "The team's tools fall back to the tenant value, where one is set. People who added their own keep using theirs."
           :confirm "Clear"}
    :tenant {:title (str "Clear the tenant value of " name "?")
             :body "Anything using the shared value stops working until it is set again. Values from secret files stay in place."
             :confirm "Clear"}))

(defn clear-request
  "The confirmation before the value of credential in scope goes. error is
   where a failure shows, refetch the load keys to refresh after."
  [scope credential {:keys [error refetch]}]
  (assoc (clear-texts scope credential)
         :run {:id [::clear scope (:id credential)]
               :error error
               :command (clear-command scope (:id credential))
               :refetch refetch}))

(rf/reg-event-fx
 ::clear
 (fn [_ [_ scope credential opts]]
   {:fx [[:dispatch [::confirm/ask (clear-request scope credential opts)]]]}))

(defn actions
  "The buttons of a credential's row: set or replace its value in scope
   and, when clear is given ({:label :el-id :error :refetch}), clear it.
   dense? is for table rows."
  [{:keys [scope credential has-value? set-label set-el-id clear dense?]}]
  [RawStack {:direction "row" :spacing 1 :sx (:end-aligned-row theme/sx)}
   [:> Button (cond-> {:data-testid (el/id set-el-id)
                       :on-click #(rf/dispatch [::edit scope credential])}
                dense? (assoc :size "small")
                (not dense?) (assoc :variant (if has-value? "text" "contained")))
    (if has-value? "Replace" set-label)]
   (when clear
     [:> Button (cond-> {:color "error" :data-testid (el/id (:el-id clear))
                         :on-click #(rf/dispatch [::clear scope credential (select-keys clear [:error :refetch])])}
                  dense? (assoc :size "small"))
      (:label clear)])])
