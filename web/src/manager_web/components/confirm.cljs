(ns manager-web.components.confirm
  "One confirmation dialog for the whole app. Ask with

     [::confirm/ask {:title .. :body .. :confirm \"Delete session\" :run run-spec}]

   where run-spec is a manager-web.data/::run spec. The dialog stays open,
   disabled, until the command finishes; the spec's own busy flag says so,
   to the dialog and to the page alike."
  (:require ["@mui/material/Button" :default Button]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/DialogActions" :default DialogActions]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogContentText" :default DialogContentText]
            ["@mui/material/DialogTitle" :default DialogTitle]
            [manager-web.components.elements :as els]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::ask
 (fn [db [_ request]]
   (assoc db ::request request)))

(rf/reg-event-db
 ::close
 (fn [db _]
   (dissoc db ::request)))

(rf/reg-event-fx
 ::accept
 (fn [{:keys [db]} _]
   (when-let [spec (get-in db [::request :run])]
     {:fx [[:dispatch [::data/run (assoc spec :finally [::close])]]]})))

(rf/reg-sub ::request (fn [db _] (::request db)))

(defn dialog
  []
  (let [request
        @(rf/subscribe [::request])

        busy?
        @(rf/subscribe [::data/busy? (data/busy-key (:run request))])

        close
        #(when-not busy? (rf/dispatch [::close]))]
    [:> Dialog {:open (some? request) :on-close close :maxWidth "xs" :fullWidth true
                :data-testid (el/id els/confirm-dialog)}
     [:> DialogTitle (:title request)]
     [:> DialogContent
      [:> DialogContentText (:body request)]]
     [:> DialogActions
      [:> Button {:on-click close :disabled busy? :data-testid (el/id els/confirm-keep)} "Keep"]
      [:> Button {:on-click #(rf/dispatch [::accept]) :color (or (:color request) "error") :variant "contained" :loading busy?
                  :data-testid (el/id els/confirm-accept)}
       (:confirm request)]]]))
