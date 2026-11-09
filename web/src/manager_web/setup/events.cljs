(ns manager-web.setup.events
  (:require [manager-web.components.credential-scope :as scope]
            [manager-web.components.required-credentials :as required-credentials]
            [manager-web.data :as data]
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
       (data/clear-error ::finish)
       (data/clear-error ::leave))))

(defn pending-credentials
  "Pending credentials the person may provide, in the order the service
   offers them: the required ones they lack first. Those only admins set are
   shown read-only under Accounts, never in setup."
  [{:keys [credentials pending]}]
  (let [by-id
        (into {} (map (juxt (comp str :id) identity)) credentials)]
    (filterv scope/may-override? (keep #(by-id (str %)) pending))))

(defn owed?
  "True for a step setup can't skip: a required credential the person
   hasn't added."
  [credential saved]
  (and (required-credentials/required? credential)
       (not (required-credentials/own? credential))
       (not (contains? saved (:id credential)))))

(defn owes-required?
  "True while setup can't be left for later."
  [credentials saved]
  (boolean (some #(owed? % saved) credentials)))

(defn- pending
  [db]
  (pending-credentials (data/loaded-data db :my-credentials)))

(def leave-spec
  "Reads the credentials again before leaving, so the app opens on what the
   service has now."
  {:id ::leave
   :query required-credentials/query
   :on-success [::left]})

(rf/reg-event-fx
 ::go-next
 (fn [_ _]
   {:fx [[:dispatch [::data/run leave-spec]]]}))

(defn left
  "Leaving setup with the credentials as the service has them: on to where
   the person was going, or the steps again while a required one is owed."
  [db my-credentials]
  (let [stored
        (data/store db :my-credentials my-credentials)]
    (if (seq (required-credentials/missing-required my-credentials))
      {:db (assoc stored ::step 0 ::saved #{})}
      {:db stored
       :fx [[:dispatch [::session/continue-to (::next db "/")]]]})))

(rf/reg-event-fx
 ::left
 (fn [{:keys [db]} [_ my-credentials]]
   (left db my-credentials)))

(defn finish-spec
  "Marks the pending credentials seen, then goes on to leave setup whether
   or not that worked: it only stops setup from being offered again."
  [credential-ids]
  {:id ::finish
   :command {:cmd-id :mark-credentials-seen
             :attrs {:credentials credential-ids}}
   :on-success [::go-next]
   :on-failure [::go-next]})

(rf/reg-event-fx
 ::finish
 (fn [{:keys [db]} _]
   (when-not (owes-required? (pending db) (::saved db #{}))
     {:fx [[:dispatch [::data/run (finish-spec (mapv :id (pending db)))]]]})))

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
