(ns manager-web.session.elements)

(def page [:session :page])
(def title [:session :title])
(def back [:session :back])
(def error [:session :error])
(def action-error [:session :action-error])
(def blocked [:session :blocked])
(def status [:session :status])
(def locked [:session :locked])
(def sharing [:session :sharing])
(def sharing-text [:session :sharing-text])
(def details [:session :details])
(def team-link [:session :team])
(def run-link [:session :run])
(def forked-from [:session :forked-from])

(defn tab
  [tab-id]
  [:session :tab tab-id])

(defn sharing-option
  [visibility]
  [:session :sharing-option visibility])
(def sharing-saving [:session :sharing-saving])
(def rename [:session :rename])
(def rename-dialog [:session :rename-dialog])
(def rename-name [:session :rename-name])
(def rename-description [:session :rename-description])
(def rename-save [:session :rename-save])
(def rename-cancel [:session :rename-cancel])
(def rename-error [:session :rename-error])
