(ns manager-web.activities.elements)

(def page [:activities :page])
(def error [:activities :error])

(defn activity
  [activity-id]
  [:activities :activity activity-id])

(defn example
  [activity-id]
  [:activities :example activity-id])

(def catalog [:activities :catalog])
