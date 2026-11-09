(ns manager-web.accounts.core
  (:require [manager-web.accounts.events :as events]
            [manager-web.accounts.views :as views]))

(defn main
  [_ctx]
  {:init ::events/init
   :panel views/main-panel})
