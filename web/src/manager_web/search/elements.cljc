(ns manager-web.search.elements)

(def open [:search :open])
(def open-mobile [:search :open-mobile])
(def dialog [:search :dialog])
(def input [:search :input])
(def results [:search :results])
(def empty-state [:search :empty])
(def hint [:search :hint])
(def error [:search :error])

(defn group
  [kind]
  [:search :group kind])

(defn result
  [kind id]
  [:search :result kind (str id)])
