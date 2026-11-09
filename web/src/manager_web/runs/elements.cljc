(ns manager-web.runs.elements)

(def page [:runs :page])
(def filter-select [:runs :filter])
(def error [:runs :error])

(defn filter-option
  [value]
  [:runs :filter-option value])
(def filter-value [:runs :filter-value])
