(ns manager-web.session
  "Who is logged in. ::status is :loading, :anonymous, :error or :ready."
  (:require [manager-web.api :as api]
            [manager-web.data :as data]
            [manager-web.urls :as urls]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::load-me
 (fn [{:keys [db]} _]
   {:db (cond-> (dissoc db ::error)
          (not= :ready (::status db)) (assoc ::status :loading))
    ::api/call {:query {:query-id :get-me}
                :on-success [::me-loaded]
                :on-failure [::me-failed]}}))

(rf/reg-event-db
 ::me-loaded
 (fn [db [_ me]]
   (assoc db ::me me ::status :ready)))

(rf/reg-event-db
 ::me-failed
 (fn [db [_ message]]
   (assoc db ::status :error ::error message)))

(rf/reg-event-fx
 ::logout
 (fn [_ _]
   {:fx [[:dispatch [::data/run {:id ::logout
                                 :method "POST"
                                 :path "/api/auth/logout"
                                 :on-success [::logged-out]
                                 :on-failure [::logged-out]}]]]}))

(rf/reg-event-fx
 ::logged-out
 (fn [{:keys [db]} _]
   {:db (api/signed-out db)
    :fx [[:dispatch [:edd.events/navigate {:url "/login" :replace? true}]]]}))

(rf/reg-fx
 ::assign-location
 (fn [path]
   (.assign (.-location js/window) path)))

(rf/reg-event-fx
 ::continue-to
 (fn [_ [_ path]]
   (if (urls/session-entry? path)
     {::assign-location path}
     {:fx [[:dispatch [::load-me]]
           [:dispatch [:edd.events/navigate {:url path :replace? true}]]]})))

(rf/reg-sub ::me (fn [db _] (::me db)))
(rf/reg-sub ::status (fn [db _] (::status db)))
(rf/reg-sub ::error (fn [db _] (::error db)))

(defn display-name
  [me]
  (or (:name me) (:sub me) ""))

(defn role-label
  [{:keys [platform-admin teams]}]
  (cond
    platform-admin "Platform admin"
    (some #(= :admin (:role %)) teams) "Team admin"
    (seq teams) "Team member"
    :else "No team yet"))
