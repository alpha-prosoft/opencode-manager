(ns manager-web.accounts.elements)

(def page [:accounts :page])
(def error [:accounts :error])
(def empty-state [:accounts :empty])
(def action-error [:accounts :action-error])

(defn credential
  [credential-id]
  [:accounts :credential credential-id])

(defn edit
  [credential-id]
  [:accounts :edit credential-id])

(defn remove-credential
  [credential-id]
  [:accounts :remove credential-id])

(def credentials [:accounts :credentials])
