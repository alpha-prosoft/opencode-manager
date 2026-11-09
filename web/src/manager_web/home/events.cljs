(ns manager-web.home.events
  (:require [manager-web.data :as data]
            [re-frame.core :as rf]))

(def intro-key "intro-dismissed")

(defn- intro-dismissed?
  []
  (try
    (= "1" (.getItem js/localStorage intro-key))
    (catch :default _ false)))

(rf/reg-event-db
 ::init
 (fn [db _]
   (assoc db ::intro-hidden? (intro-dismissed?))))

(rf/reg-fx
 ::remember-intro-dismissed
 (fn [_]
   (try
     (.setItem js/localStorage intro-key "1")
     (catch :default _ nil))))

(rf/reg-event-fx
 ::hide-intro
 (fn [{:keys [db]} _]
   {:db (assoc db ::intro-hidden? true)
    ::remember-intro-dismissed true}))

(rf/reg-event-fx
 ::teams-loaded
 (fn [_ [_ teams]]
   {:fx (for [{:keys [id]} teams]
          [:dispatch [::data/fetch [:team id] {:query {:query-id :get-team :team-id id}}]])}))
