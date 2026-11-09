(ns manager-web.run.events
  (:require [manager-web.components.confirm :as confirm]
            [manager-web.data :as data]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::init
 (fn [db [_ {:keys [run-id]}]]
   (-> (data/enter db)
       (data/set-ui :run :run-id (data/route-id run-id)))))

(defn cancel-request
  [run-id]
  {:title "Cancel this run?"
   :body "The agent is stopped and no result is collected. You can run it again later with the same inputs."
   :confirm "Cancel run"
   :run {:id [::cancel run-id]
         :error ::action
         :command {:cmd-id :cancel-run :id run-id}
         :refetch [[:run run-id]]}})

(rf/reg-event-fx
 ::cancel
 (fn [_ [_ run-id]]
   {:fx [[:dispatch [::confirm/ask (cancel-request run-id)]]]}))

(defn retry-spec
  [run-id new-id]
  {:id [::retry run-id]
   :error ::action
   :command {:cmd-id :retry-run :id new-id :attrs {:source-id run-id}}
   :refetch-on-failure [:my-credentials]
   :navigate [:run {:run-id (str new-id)}]})

(rf/reg-event-fx
 ::retry
 (fn [_ [_ run-id]]
   {:fx [[:dispatch [::data/run (retry-spec run-id (random-uuid))]]]}))
