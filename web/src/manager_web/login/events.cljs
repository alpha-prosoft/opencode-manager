(ns manager-web.login.events
  (:require [clojure.string :as string]
            [manager-web.api :as api]
            [manager-web.data :as data]
            [manager-web.session :as session]
            [manager-web.urls :as urls]
            [re-frame.core :as rf]))

(defn sign-in-url
  "The edge's provider login (oidc mode), returning to next."
  [next-path]
  (str "/api/auth/oidc/login?next=" (js/encodeURIComponent next-path)))

(defn mode-of
  "The login the server offers: :login (test personas), :oidc (the identity
   provider's login page) or :jwt (people arrive with the provider's token)."
  [config]
  (case (some-> (:mode config) name)
    "oidc" :oidc
    "jwt" :jwt
    :login))

(defn init-effects
  "Entering the login page: learn the auth mode; back from the identity
   provider (complete=1) the login is finished like a password-free one."
  [db params]
  {:db (-> db
           (assoc ::username "" ::next (urls/safe-next (:next params)))
           (dissoc ::personas ::personas-error ::mode ::refused)
           (data/clear-error ::login))
   ::api/call {:path "/api/auth/config"
               :on-success [::config-loaded]
               :on-failure [::config-loaded {:mode "login"}]
               :on-not-found [::config-loaded {:mode "login"}]}
   :fx [(when (= "1" (:complete params))
          [:dispatch [::logged-in]])]})

(rf/reg-event-fx
 ::init
 (fn [{:keys [db]} [_ params]]
   (init-effects db params)))

(defn config-loaded
  [db config]
  (let [mode
        (mode-of config)]
    {:db (assoc db ::mode mode ::refused (:error config))
     :fx [(when (= :login mode)
            [:dispatch [::load-personas]])]}))

(rf/reg-event-fx
 ::config-loaded
 (fn [{:keys [db]} [_ config]]
   (config-loaded db config)))

(rf/reg-event-fx
 ::load-personas
 (fn [_ _]
   {::api/call {:path "/api/auth/personas"
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

(defn logged-in-effects
  "Once the login cookie is set: who the person is, so that setup can name
   their teams, and which credentials they have yet to provide."
  []
  {:fx [[:dispatch [::session/load-me]]
        [:dispatch [::data/run {:id ::login
                                :error ::credentials-check
                                :query {:query-id :get-my-credentials}
                                :on-success [::credentials-checked]
                                :on-failure [::credentials-unknown]}]]]})

(rf/reg-event-fx
 ::logged-in
 (fn [_ _]
   (logged-in-effects)))

(defn after-login
  "Where to go once logged in: setup first while credentials are pending."
  [next-path pending]
  (if (seq pending)
    [:edd.events/navigate {:url (urls/setup-url next-path) :replace? true}]
    [::session/continue-to next-path]))

(rf/reg-event-fx
 ::credentials-checked
 (fn [{:keys [db]} [_ {:keys [pending]}]]
   {:fx [[:dispatch (after-login (::next db "/") pending)]]}))

(rf/reg-event-fx
 ::credentials-unknown
 (fn [{:keys [db]} _]
   {:fx [[:dispatch (after-login (::next db "/") [])]]}))
