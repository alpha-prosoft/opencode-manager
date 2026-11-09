(ns manager-web.el
  "Element ids shared by views and integration tests. Views pass the same
   value to a primitive's :el-id (or as :data-testid (el/id ...) on MUI and
   plain elements) that a test resolves with `sel`; nobody writes id strings
   by hand."
  (:require [web.primitives.testing.el-id :as el-id]))

(defn id
  [& parts]
  (apply el-id/el-id parts))

(defn sel
  "CSS selector matching an element by its el-id, as :id or :data-testid."
  [& parts]
  (let [value
        (apply id parts)]
    (str "[id='" value "'],[data-testid='" value "']")))
