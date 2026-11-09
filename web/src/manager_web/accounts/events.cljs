(ns manager-web.accounts.events
  (:require [manager-web.components.credential-form :as credential-form]
            [manager-web.data :as data]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::init
 (fn [db _]
   (credential-form/close (data/enter db))))
