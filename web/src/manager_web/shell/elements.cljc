(ns manager-web.shell.elements)

(defn nav-item
  [page]
  [:nav page])

(def brand [:shell :brand])
(def open-navigation [:shell :open-navigation])
(def user-name [:shell :user-name])
(def user-role [:shell :user-role])
(def logout [:shell :logout])
(def error-snackbar [:shell :error])
(def load-error [:shell :load-error])
(def retry [:shell :retry])
