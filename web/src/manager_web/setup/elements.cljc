(ns manager-web.setup.elements)

(def page [:setup :page])
(def error [:setup :error])
(def all-set [:setup :all-set])
(def continue [:setup :continue])
(def later [:setup :later])

(defn step
  [credential-id]
  [:setup :step credential-id])

(defn skip
  [credential-id]
  [:setup :skip credential-id])
