(ns manager-web.team.core
  (:require [manager-web.team.events :as events]
            [manager-web.team.views :as views]))

(defn main
  [_ctx]
  {:init ::events/init
   :panel views/main-panel})
