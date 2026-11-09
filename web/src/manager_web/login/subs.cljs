(ns manager-web.login.subs
  (:require [manager-web.login.events :as events]
            [re-frame.core :as rf]))

(rf/reg-sub ::personas (fn [db _] (::events/personas db [])))
(rf/reg-sub ::personas-error (fn [db _] (::events/personas-error db)))
(rf/reg-sub ::username (fn [db _] (::events/username db "")))
(rf/reg-sub ::mode (fn [db _] (::events/mode db)))
(rf/reg-sub ::refused (fn [db _] (::events/refused db)))
(rf/reg-sub ::next (fn [db _] (::events/next db "/")))
