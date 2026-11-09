(ns manager-web.admin.core
  (:require [manager-web.admin.events :as events]
            [manager-web.admin.views :as views]))

(defn main
  [_ctx]
  {:init ::events/init
   :panel views/main-panel})
