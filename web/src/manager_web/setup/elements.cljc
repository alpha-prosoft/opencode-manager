(ns manager-web.setup.elements)

(def page [:setup :page])
(def error [:setup :error])
(def all-set [:setup :all-set])
(def continue [:setup :continue])
(def later [:setup :later])
(def required-note [:setup :required-note])
(def signed-in [:setup :signed-in])
(def logout [:setup :logout])
(def leave-error [:setup :leave-error])
(def leave-retry [:setup :leave-retry])
(def admin-link [:setup :admin-link])
(def tenants-link [:setup :tenants-link])

(defn step
  [credential-id]
  [:setup :step credential-id])

(defn skip
  [credential-id]
  [:setup :skip credential-id])
