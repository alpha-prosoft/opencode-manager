(ns manager-web.setup.events
  (:require [manager-web.data :as data]
            [manager-web.session :as session]
            [manager-web.urls :as urls]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::init
 (fn [db [_ params]]
   (-> db
       (assoc ::next (urls/safe-next (:next params))
              ::step 0
              ::saved #{})
       (data/clear-error ::finish))))

(defn pending-credentials
  [{:keys [credentials pending]}]
  (let [pending-ids
        (set (map str pending))]
    (filterv #(contains? pending-ids (str (:id %))) credentials)))

(defn- pending
  [db]
  (pending-credentials (data/loaded-data db :my-credentials)))

(rf/reg-event-fx
 ::go-next
 (fn [{:keys [db]} _]
   {:fx [[:dispatch [::session/continue-to (::next db "/")]]]}))

(defn finish-spec
  "Marks the pending credentials seen, then leaves setup whether or not
   that worked: it only stops setup from being offered again."
  [credential-ids]
  {:id ::finish
   :command {:cmd-id :mark-credentials-seen
             :attrs {:credentials credential-ids}}
   :on-success [::go-next]
   :on-failure [::go-next]})

(rf/reg-event-fx
 ::finish
 (fn [{:keys [db]} _]
   {:fx [[:dispatch [::data/run (finish-spec (mapv :id (pending db)))]]]}))

(rf/reg-event-fx
 ::advance
 (fn [{:keys [db]} _]
   (if (< (inc (::step db)) (count (pending db)))
     {:db (update db ::step inc)}
     {:fx [[:dispatch [::finish]]]})))

(rf/reg-event-fx
 ::saved
 (fn [{:keys [db]} [_ credential-id]]
   {:db (update db ::saved conj credential-id)
    :fx [[:dispatch [::advance]]]}))
