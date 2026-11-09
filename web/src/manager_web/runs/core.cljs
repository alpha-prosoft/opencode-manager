(ns manager-web.runs.core
  (:require [manager-web.data :as data]
            [manager-web.runs.views :as views]))

(defn main
  [_ctx]
  {:init ::data/enter-page
   :panel views/main-panel})
