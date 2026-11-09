(ns manager-web.accounts.events
  (:require [manager-web.components.confirm :as confirm]
            [manager-web.components.credential-form :as credential-form]
            [manager-web.data :as data]
            [manager-web.format :as format]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::init
 (fn [db _]
   (-> db
       (credential-form/close)
       (data/clear-error ::action))))

(defn remove-request
  [{:keys [id name provided-by]}]
  {:title (str "Remove your " name " credential?")
   :body (if (= :platform-or-user (format/kw provided-by))
           "Tools will use the shared account again."
           "Tools that need it stop working for you until you add it again.")
   :confirm "Remove"
   :run {:id [::remove id]
         :error ::action
         :command {:cmd-id :delete-my-credential :attrs {:credential-id id}}
         :refetch [:my-credentials]}})

(rf/reg-event-fx
 ::remove
 (fn [_ [_ credential]]
   {:fx [[:dispatch [::confirm/ask (remove-request credential)]]]}))
