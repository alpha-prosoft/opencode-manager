(ns manager-web.tenants.elements
  (:refer-clojure :exclude [sync]))

(def page [:tenants :page])
(def error [:tenants :error])
(def forbidden [:tenants :forbidden])
(def table [:tenants :table])

(defn row
  [tenant-name]
  [:tenants :row tenant-name])

(defn sync-status
  [tenant-name]
  [:tenants :sync-status tenant-name])

(defn sync
  [tenant-name]
  [:tenants :sync tenant-name])

(def action-error [:tenants :action-error])

(def register-name [:tenants :register-name])
(def register-onboarding [:tenants :register-onboarding])
(def register-submit [:tenants :register-submit])
(def register-error [:tenants :register-error])
(def registered [:tenants :registered])
(def scope [:tenants :scope])

(def reload [:tenants :reload])
(def reload-error [:tenants :reload-error])
(def reload-result [:tenants :reload-result])

(defn reload-part
  [part]
  [:tenants :reload-part part])
