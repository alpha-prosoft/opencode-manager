(ns manager-web.components.conversations
  (:require ["@mui/material/Paper" :default Paper]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [manager-web.urls :as urls]
            [web.primitives.components :refer [RawBox RawTypography]]))

(defn- summary
  [{:keys [messages last]}]
  (str (format/plural (or messages 0) "message")
       (when last (str " · last " (format/relative-time last)))))

(defn- conversation-list
  [session conversations]
  (if (empty? conversations)
    [layout/empty-state {:title "No conversations yet" :el-id els/conversations-empty}
     "Each chat started in this instance shows up here."]
    (into [:> Paper {:data-testid (el/id els/conversations-list)}]
          (for [conversation conversations]
            ^{:key (:id conversation)}
            [layout/list-row {}
             [RawBox {:sx (:grow theme/sx)}
              [RawTypography {:variant "h3"} (:title conversation)]
              [RawTypography {:variant "body2" :color "text.secondary"} (summary conversation)]]
             (when (:can-open session)
               [layout/external-button {:href (urls/with-query (:open-url session) :conversation (:id conversation))
                                        :size "small"
                                        :el-id (els/conversation-open (:id conversation))}
                "Open"])]))))

(defn conversations
  [session]
  [data/loaded {:key [:conversations (:id session)]
                :query {:query-id :list-conversations :session-id (:id session)}
                :interval (when (format/session-live? (:status session)) 10000)
                :error-el-id els/conversations-error}
   #(conversation-list session %)])
