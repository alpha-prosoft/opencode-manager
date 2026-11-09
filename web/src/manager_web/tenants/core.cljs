(ns manager-web.tenants.core
  (:require [manager-web.data :as data]
            [manager-web.tenants.views :as views]))

(defn main
  [_ctx]
  {:init ::data/enter-page
   :panel views/main-panel})
