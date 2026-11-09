(ns manager-web.harnesses.elements)

(def page [:harnesses :page])
(def error [:harnesses :error])
(def empty-state [:harnesses :empty])

(defn harness
  [team-id harness-id]
  [:harnesses :harness team-id harness-id])

(defn ready
  [team-id harness-id]
  [:harnesses :ready team-id harness-id])

(defn needs-setup
  [team-id harness-id]
  [:harnesses :needs-setup team-id harness-id])

(defn add-credential
  [team-id harness-id]
  [:harnesses :add-credential team-id harness-id])

(def grid [:harnesses :grid])
