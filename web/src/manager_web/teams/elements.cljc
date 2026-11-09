(ns manager-web.teams.elements)

(def page [:teams :page])
(def error [:teams :error])
(def empty-state [:teams :empty])

(defn team-card
  [team-id]
  [:teams :team team-id])

(def grid [:teams :grid])
