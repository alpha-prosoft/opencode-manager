(ns manager-web.run.core
  (:require [manager-web.run.events :as events]
            [manager-web.run.views :as views]))

(defn main
  [_ctx]
  {:init ::events/init
   :panel views/main-panel})
