(ns manager-web.session.core
  (:require [manager-web.session.events :as events]
            [manager-web.session.views :as views]))

(defn main
  [_ctx]
  {:init ::events/init
   :panel views/main-panel})
