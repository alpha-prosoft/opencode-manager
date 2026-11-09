(ns manager-web.tenants.events
  (:require [clojure.string :as string]
            [manager-web.data :as data]
            [re-frame.core :as rf]))

(defn sync-state
  "How a tenant's last sync went: :failed, :synced or :never."
  [{:keys [error-message synced-at]}]
  (cond
    (not (string/blank? error-message)) :failed
    (not (string/blank? synced-at)) :synced
    :else :never))

(defn sync-spec
  [tenant-name]
  {:id [::sync tenant-name]
   :error ::action
   :command {:cmd-id :sync-tenant :attrs {:tenant tenant-name}}
   :refetch [:tenants]})

(defn register-spec
  [{:keys [name onboarding]}]
  {:id ::register
   :command {:cmd-id :register-tenant :attrs {:name (string/trim (str name))
                                              :onboarding (string/trim (str onboarding))}}
   :refetch [:tenants]
   :on-success [::registered (string/trim (str name))]})

(def empty-form
  {:name "" :onboarding ""})

(rf/reg-event-db
 ::set-field
 (fn [db [_ field value]]
   (data/set-ui db :tenants :register (assoc (data/ui db :tenants :register empty-form) field value))))

(rf/reg-event-fx
 ::register
 (fn [_ [_ values]]
   {:fx [[:dispatch [::data/run (register-spec values)]]]}))

(rf/reg-event-db
 ::registered
 (fn [db [_ tenant-name]]
   (-> db
       (data/set-ui :tenants :register empty-form)
       (data/set-ui :tenants :registered tenant-name))))

(def reload-spec
  "Reads the server's configuration files and the identity provider's keys
   again, now."
  {:id ::reload
   :method "POST"
   :path "/api/admin/reload-config"
   :body {}
   :refetch [:tenants]
   :on-success [::reloaded]})

(defn reload-parts
  "[[id label part]] of a reload's result, each part {:status :error}. The
   identity provider's parts are there only where the server has one."
  [{:keys [claims-parser tenants identity-provider new-tenants]}]
  (vec (remove nil?
               [[:claims-parser "Claims parser" claims-parser]
                [:tenants "Tenants file" tenants]
                (when identity-provider
                  [:discovery "Identity provider discovery" (:discovery identity-provider)])
                (when identity-provider
                  [:keys "Identity provider keys" (:keys identity-provider)])
                [:new-tenants "Sync of new tenants" new-tenants]])))

(defn part-failed?
  [part]
  (= "failed" (some-> (:status part) name)))

(defn part-text
  [part]
  (cond
    (part-failed? part) (str "Failed" (when-not (string/blank? (:error part)) (str ": " (:error part))))
    (sequential? part) (if (seq part) (str "Synced " (string/join ", " part)) "None added")
    (nil? part) "Nothing to do"
    :else (string/capitalize (name (or (:status part) "done")))))

(rf/reg-event-db
 ::reloaded
 (fn [db [_ result]]
   (data/set-ui db :tenants :reload (if (map? result) result {}))))
