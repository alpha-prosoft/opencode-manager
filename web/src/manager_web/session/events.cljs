(ns manager-web.session.events
  (:require [clojure.string :as string]
            [manager-web.components.confirm :as confirm]
            [manager-web.data :as data]
            [manager-web.routes :as routes]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::init
 (fn [db [_ {:keys [session-id]}]]
   (-> (data/enter db)
       (data/set-ui :session :session-id (data/route-id session-id)))))

(defn visibility-spec
  [session-id visibility]
  {:id [::visibility session-id]
   :error ::action
   :command {:cmd-id :update-session :id session-id :attrs {:visibility visibility}}
   :refetch [[:session session-id]]})

(defn share-request
  "Asked before a session is shown to the whole tenant."
  [session-id tenant]
  {:title (str "Share with everyone in " tenant "?")
   :body (str "Everyone in " tenant " will be able to read this session, its conversations and its audit trail, and to fork it.")
   :confirm "Share"
   :color "primary"
   :run (visibility-spec session-id :tenant)})

(defn visibility-event
  "What choosing a visibility does: widening to the tenant asks first."
  [session-id visibility tenant]
  (if (= :tenant visibility)
    [::confirm/ask (share-request session-id tenant)]
    [::data/run (visibility-spec session-id visibility)]))

(rf/reg-event-fx
 ::set-visibility
 (fn [{:keys [db]} [_ session-id visibility]]
   {:fx [[:dispatch (visibility-event session-id visibility (routes/tenant-of-db db))]]}))

(def tabs
  [[:overview "Overview"]
   [:conversations "Conversations"]
   [:activity "Live activity"]
   [:audit "Audit trail"]])

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
