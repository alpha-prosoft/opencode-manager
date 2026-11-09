(ns manager-web.login.subs
  (:require [manager-web.login.events :as events]
            [re-frame.core :as rf]))

(rf/reg-sub ::personas (fn [db _] (::events/personas db [])))
(rf/reg-sub ::personas-error (fn [db _] (::events/personas-error db)))
(rf/reg-sub ::username (fn [db _] (::events/username db "")))
