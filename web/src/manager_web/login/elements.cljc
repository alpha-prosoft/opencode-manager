(ns manager-web.login.elements)

(def page [:login :page])
(def username [:login :username])
(def submit [:login :submit])
(def error [:login :error])

(defn persona
  [username]
  [:login :persona username])
(def personas-error [:login :personas-error])
