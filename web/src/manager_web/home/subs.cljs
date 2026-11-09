(ns manager-web.home.subs
  (:require [clojure.string :as string]
            [manager-web.components.workflows :as workflows]
            [manager-web.data :as data]
            [manager-web.home.events :as events]
            [re-frame.core :as rf]))

(rf/reg-sub ::intro-hidden? (fn [db _] (::events/intro-hidden? db)))

(defn workflows-load
  "The workflows of all teams as a load ({:data :error}): data once every
   team's details have arrived or failed, the failures as error."
  [teams details]
  (let [team-loads
        (concat [teams] details)

        settled?
        (and (some? (:data teams))
             (every? #(or (some? (:data %)) (some? (:error %))) details))]
    {:data (when settled?
             (vec (mapcat workflows/team-workflows (keep :data details))))
     :error (some->> (seq (keep :error team-loads))
                     distinct
                     (string/join " "))}))

(rf/reg-sub
 ::workflows
 (fn [db _]
   (let [teams
         (data/load-of db :teams)]
     (workflows-load teams (for [{:keys [id]} (:data teams)]
                             (data/load-of db [:team id]))))))
