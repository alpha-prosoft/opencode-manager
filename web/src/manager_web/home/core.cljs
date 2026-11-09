(ns manager-web.home.core
  (:require [manager-web.home.events :as events]
            [manager-web.home.subs]
            [manager-web.home.views :as views]))

(defn main
  [_ctx]
  {:init ::events/init
   :panel views/main-panel})
