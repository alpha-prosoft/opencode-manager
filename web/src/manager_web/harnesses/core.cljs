(ns manager-web.harnesses.core
  (:require [manager-web.data :as data]
            [manager-web.harnesses.views :as views]))

(defn main
  [_ctx]
  {:init ::data/enter-page
   :panel views/main-panel})
