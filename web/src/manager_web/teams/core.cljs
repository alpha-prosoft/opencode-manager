(ns manager-web.teams.core
  (:require [manager-web.data :as data]
            [manager-web.teams.views :as views]))

(defn main
  [_ctx]
  {:init ::data/enter-page
   :panel views/main-panel})
