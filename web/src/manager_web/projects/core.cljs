(ns manager-web.projects.core
  (:require [manager-web.projects.views :as views]
            [manager-web.data :as data]))

(defn main
  [_ctx]
  {:init ::data/enter-page
   :panel views/main-panel})
