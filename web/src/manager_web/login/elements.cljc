(ns manager-web.login.elements)

(def page [:login :page])
(def username [:login :username])
(def submit [:login :submit])
(def error [:login :error])
(def refused [:login :refused])
(def sign-in [:login :sign-in])
(def provider-managed [:login :provider-managed])

(defn persona
  [username]
  [:login :persona username])
(def personas-error [:login :personas-error])

(defn persona-tenant
  [username]
  [:login :persona-tenant username])
