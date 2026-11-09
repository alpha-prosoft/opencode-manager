(ns manager-web.team.elements)

(def page [:team :page])
(def title [:team :title])
(def back [:team :back])
(def error [:team :error])
(def sync-error [:team :sync-error])

(defn tab
  [tab-id]
  [:team :tab tab-id])

(def budget [:team :budget])
(def members [:team :members])

(defn agent-card
  [agent-id]
  [:team :agent agent-id])

(defn harness-card
  [harness-id]
  [:team :harness harness-id])

(def runs-error [:team :runs-error])
(def usage-by-model [:team :usage-by-model])
