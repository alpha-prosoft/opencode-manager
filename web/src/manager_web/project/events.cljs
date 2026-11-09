(ns manager-web.project.events
  (:require [manager-web.data :as data]
            [manager-web.format :as format]
            [re-frame.core :as rf]))

(def tabs
  [[:workflows "Workflows"]
   [:runs "Runs"]
   [:sessions "Sessions"]
   [:changes "Open changes"]
   [:builds "Builds"]
   [:webhooks "Webhooks"]])

(rf/reg-event-db
 ::init
 (fn [db [_ {:keys [team-id project-id]}]]
   (-> (data/enter db :webhooks)
       (data/set-ui :project :ids {:team-id team-id :project-id project-id}))))

(defn workflow-items
  "The project's workflows as workflow list items of its team."
  [{:keys [team workflows]}]
  (for [workflow workflows]
    {:team-id (:id team)
     :team-name (:name team)
     :workflow workflow}))

(defn builds
  "Runs of the project whose result has a CI build, newest first."
  [runs]
  (filterv #(get-in % [:outcome :build]) runs))

(defn change-label
  [provider {:keys [id title]}]
  (str (format/change-name provider id) " · " title))
