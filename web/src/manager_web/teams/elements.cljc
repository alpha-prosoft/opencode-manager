(ns manager-web.teams.elements)

(def page [:teams :page])
(def error [:teams :error])
(def empty-state [:teams :empty])

(defn team-card
  [team-id]
  [:teams :team team-id])

(def grid [:teams :grid])

(defn team-projects
  [team-id]
  [:teams :projects team-id])

(defn team-project
  [team-id project-id]
  [:teams :project team-id project-id])

(defn team-role
  [team-id]
  [:teams :role team-id])
