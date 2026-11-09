(ns manager-web.team.events
  (:require [manager-web.data :as data]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::init
 (fn [db [_ {:keys [team-id]}]]
   (-> db
       (data/reset-ui :team)
       (data/set-ui :team :team-id team-id))))
