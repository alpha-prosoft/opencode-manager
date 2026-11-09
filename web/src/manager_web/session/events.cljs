(ns manager-web.session.events
  (:require [clojure.string :as string]
            [manager-web.data :as data]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::init
 (fn [db [_ {:keys [session-id]}]]
   (-> db
       (data/reset-ui :session)
       (data/set-ui :session :session-id (data/route-id session-id))
       (data/clear-error ::action))))

(defn visibility-spec
  [session-id visibility]
  {:id [::visibility session-id]
   :error ::action
   :command {:cmd-id :update-session :id session-id :attrs {:visibility visibility}}
   :refetch [[:session session-id]]})

(rf/reg-event-fx
 ::set-visibility
 (fn [_ [_ session-id visibility]]
   {:fx [[:dispatch [::data/run (visibility-spec session-id visibility)]]]}))

(defn rename-spec
  [session-id {:keys [name description]}]
  {:id [::rename session-id]
   :error ::rename
   :command {:cmd-id :update-session :id session-id :attrs {:name (string/trim (str name)) :description (str description)}}
   :refetch [[:session session-id]]
   :on-success [::close-rename]})

(rf/reg-event-db
 ::open-rename
 (fn [db [_ session]]
   (-> db
       (data/set-ui :session :rename (select-keys session [:name :description]))
       (data/clear-error ::rename))))

(rf/reg-event-db
 ::set-rename
 (fn [db [_ key value]]
   (data/set-ui db :session :rename (assoc (data/ui db :session :rename {}) key value))))

(rf/reg-event-db
 ::close-rename
 (fn [db _]
   (update-in db [::data/ui :session] dissoc :rename)))

(rf/reg-event-fx
 ::rename
 (fn [{:keys [db]} [_ session-id]]
   {:fx [[:dispatch [::data/run (rename-spec session-id (data/ui db :session :rename {}))]]]}))
