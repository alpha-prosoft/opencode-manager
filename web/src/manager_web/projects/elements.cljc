(ns manager-web.projects.elements)

(def page [:projects :page])
(def error [:projects :error])
(def empty-state [:projects :empty])
(def grid [:projects :grid])

(defn card
  [team-id project-id]
  [:projects :card team-id project-id])
