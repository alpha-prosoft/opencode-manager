(ns manager-svc.observability.json
  "Plain JSON for data from outside edd (LLM providers, OpenCode, the sandbox
   monitor plugin). edd's own mapper rewrites strings starting with : or #,
   which would corrupt foreign payloads."
  (:require [jsonista.core :as json]))

(defn read-str
  "Parses JSON with string keys, or nil when it is not JSON."
  [text]
  (try
    (json/read-value text)
    (catch Exception _
      nil)))

(defn write-str
  ^String [value]
  (json/write-value-as-string value))

(defn- key->str
  [k]
  (if (keyword? k)
    (subs (str k) 1)
    (str k)))

(defn- sorted-tree
  [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[k v]] [(key->str k) (sorted-tree v)]))
                       value)
    (sequential? value) (mapv sorted-tree value)
    (keyword? value) (key->str value)
    :else value))

(defn canonical
  "JSON with sorted keys and no whitespace: the form audit hashes are taken over."
  ^String [value]
  (write-str (sorted-tree value)))
