(ns manager-web.project.core
  (:require [manager-web.project.events :as events]
            [manager-web.project.views :as views]))

(defn main
  [_ctx]
  {:init ::events/init
   :panel views/main-panel})
