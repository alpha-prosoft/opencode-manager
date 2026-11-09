(ns manager-web.sessions.core
  (:require [manager-web.data :as data]
            [manager-web.sessions.views :as views]))

(defn main
  [_ctx]
  {:init ::data/enter-page
   :panel views/main-panel})
