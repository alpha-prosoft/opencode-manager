(ns manager-web.login.core
  (:require [manager-web.login.events :as events]
            [manager-web.login.views :as views]))

(defn main
  [_ctx]
  {:init ::events/init
   :panel views/main-panel
   :url-params [:map
                [:next {:optional true} :string]
                [:complete {:optional true} :string]]})
