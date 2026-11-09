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

(defn resolution
  [credential-id]
  [:accounts :resolution credential-id])

(defn explanation
  [credential-id]
  [:accounts :explanation credential-id])

(defn read-only
  [credential-id]
  [:accounts :read-only credential-id])

(defn breakdown
  [credential-id]
  [:accounts :breakdown credential-id])

(defn admin-link
  [credential-id scope]
  [:accounts :admin-link credential-id scope])

(defn required
  [credential-id]
  [:accounts :required credential-id])

(defn required-by
  [credential-id]
  [:accounts :required-by credential-id])

(defn needs-own
  [credential-id]
  [:accounts :needs-own credential-id])
