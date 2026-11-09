(ns manager-web.login.events
  (:require [clojure.string :as string]
            [manager-web.api :as api]
            [manager-web.data :as data]
            [manager-web.session :as session]
            [manager-web.urls :as urls]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::init
 (fn [{:keys [db]} [_ params]]
   {:db (-> db
            (assoc ::username "" ::next (urls/safe-next (:next params)))
            (dissoc ::personas ::personas-error)
            (data/clear-error ::login))
    ::api/call {:path "/api/auth/personas"
                :on-success [::personas-loaded]
                :on-failure [::personas-failed]
                :on-not-found [::personas-loaded []]}}))

(rf/reg-event-db
 ::personas-loaded
 (fn [db [_ personas]]
   (assoc db ::personas (if (sequential? personas) (vec personas) []))))

(rf/reg-event-db
 ::personas-failed
 (fn [db [_ message]]
   (assoc db ::personas-error message)))

(rf/reg-event-db
 ::set-username
 (fn [db [_ value]]
   (assoc db ::username value)))

(rf/reg-event-fx
 ::submit
 (fn [{:keys [db]} _]
   {:fx [[:dispatch [::data/run {:id ::login
                                 :method "POST"
                                 :path "/api/auth/login"
                                 :body {:username (string/trim (::username db))}
                                 :on-success [::logged-in]}]]]}))

(rf/reg-event-fx
 ::logged-in
 (fn [{:keys [db]} _]
   {:db (assoc db ::session/status :loading)
    :fx [[:dispatch [::data/run {:id ::login
                                 :error ::credentials-check
                                 :query {:query-id :get-my-credentials}
                                 :on-success [::credentials-checked]
                                 :on-failure [::credentials-unknown]}]]]}))

(defn after-login
  "Where to go once logged in: setup first while credentials are pending."
  [next-path pending]
  (if (seq pending)
    [:edd.events/navigate {:url (str "/setup?next=" (js/encodeURIComponent next-path)) :replace? true}]
    [::session/continue-to next-path]))

(rf/reg-event-fx
 ::credentials-checked
 (fn [{:keys [db]} [_ {:keys [pending]}]]
   {:fx [[:dispatch (after-login (::next db "/") pending)]]}))

(rf/reg-event-fx
 ::credentials-unknown
 (fn [{:keys [db]} _]
   {:fx [[:dispatch (after-login (::next db "/") [])]]}))
