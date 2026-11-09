(ns manager-web.activities.core
  (:require [manager-web.data :as data]
            [manager-web.activities.views :as views]))

(defn main
  [_ctx]
  {:init ::data/enter-page
   :panel views/main-panel})
