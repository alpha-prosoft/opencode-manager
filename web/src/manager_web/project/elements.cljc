(ns manager-web.project.elements)

(def page [:project :page])
(def title [:project :title])
(def error [:project :error])
(def crumbs [:project :crumbs])
(def crumb-tenant [:project :crumb-tenant])
(def crumb-team [:project :crumb-team])
(def team-chip [:project :team-chip])
(def repository [:project :repository])
(def repository-link [:project :repository-link])
(def branch [:project :branch])
(def ci [:project :ci])

(defn tab
  [tab-id]
  [:project :tab tab-id])

(def runs-error [:project :runs-error])
(def sessions-error [:project :sessions-error])
(def sessions-action-error [:project :sessions-action-error])
(def changes [:project :changes])
(def changes-empty [:project :changes-empty])
(def changes-error [:project :changes-error])

(defn change
  [change-id]
  [:project :change (str change-id)])

(def builds [:project :builds])
(def builds-empty [:project :builds-empty])

(defn build
  [run-id]
  [:project :build (str run-id)])

(defn build-run
  [run-id]
  [:project :build-run (str run-id)])

(def back-to-list [:project :back-to-list])
