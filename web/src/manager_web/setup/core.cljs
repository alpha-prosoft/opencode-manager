(ns manager-web.setup.core
  (:require [manager-web.setup.events :as events]
            [manager-web.setup.subs]
            [manager-web.setup.views :as views]))

(defn main
  [_ctx]
  {:init ::events/init
   :panel views/main-panel
   :url-params [:map [:next {:optional true} :string]]})
