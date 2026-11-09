(ns manager-web.sessions.events
  (:require [manager-web.format :as format]))

(defn matches?
  [filter-value session]
  (case filter-value
    :mine (boolean (:is-owner session))
    :running (format/session-live? (:status session))
    true))
