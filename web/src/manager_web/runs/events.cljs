(ns manager-web.runs.events
  (:require [manager-web.format :as format]))

(defn matches?
  [filter-value {:keys [status]}]
  (case filter-value
    :all true
    :active (format/run-active? status)
    (= filter-value (format/kw status))))
