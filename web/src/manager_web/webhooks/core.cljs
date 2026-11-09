(ns manager-web.webhooks.core
  (:require [manager-web.webhooks.events :as events]
            [manager-web.webhooks.views :as views]))

(defn main
  [_ctx]
  {:init ::events/init
   :panel views/main-panel})
