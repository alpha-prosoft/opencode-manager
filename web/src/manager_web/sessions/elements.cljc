(ns manager-web.sessions.elements)

(def page [:sessions :page])
(def error [:sessions :error])

(defn filter-option
  [value]
  [:sessions :filter value])
(def action-error [:sessions :action-error])
