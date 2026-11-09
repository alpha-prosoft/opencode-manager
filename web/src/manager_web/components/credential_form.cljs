(ns manager-web.components.credential-form
  "The form and dialog to set a credential. The secret is PUT to the edge,
   which seals it before it reaches edd, so it never enters the command log."
  (:require ["@mui/material/Button" :default Button]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogTitle" :default DialogTitle]
            ["@mui/material/TextField" :default TextField]
            [clojure.string :as string]
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
   :platform "/api/admin/credentials/"})

(defn run-id
  [scope credential-id]
  [::save scope credential-id])

(defn save-path
  [scope credential-id]
  (str (get paths scope) (js/encodeURIComponent (str credential-id))))

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

(defn credential-dialog
  "The dialog opened by [::edit scope credential]. describe gives the
   credential's :title, :help and :checks?."
  [{:keys [scope refetch describe]}]
  (let [editing
        @(rf/subscribe [::editing])

        close
        #(rf/dispatch [::close])]
    [:> Dialog {:open (some? editing) :on-close close :fullWidth true :maxWidth "sm"
                :data-testid (el/id els/credential-dialog)}
     (when editing
       (let [{:keys [title help checks?]}
             (describe editing)]
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
