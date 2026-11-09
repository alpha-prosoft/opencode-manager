(ns manager-web.setup.subs
  (:require [manager-web.setup.events :as events]
            [re-frame.core :as rf]))

(rf/reg-sub ::step (fn [db _] (::events/step db 0)))
(rf/reg-sub ::saved (fn [db _] (::events/saved db #{})))
