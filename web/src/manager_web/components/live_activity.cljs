(ns manager-web.components.live-activity
  "What the agent in a session is doing, from the sandbox monitor."
  (:require ["@mui/material/Chip" :default Chip]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Typography" :default Typography]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(defn- parse
  [raw]
  (if (string? raw)
    (try
      (js->clj (js/JSON.parse raw) :keywordize-keys true)
      (catch :default _ {}))
    (or raw {})))

(def event-names
  {"session.idle" "Agent finished and is waiting"
   "session.error" "Agent hit an error"
   "session.compacted" "Conversation was compacted"
   "permission.asked" "Agent asked for permission"
   "permission.replied" "Permission answered"
   "todo.updated" "Agent updated its to-do list"})

(defn- kind
  [event]
  (some-> (:kind event) name))

(defn- event-name
  [event]
  (some-> (get-in event [:detail :event]) name))

(defn describe
  "Text and color of a timeline entry."
  [{:keys [tool detail] :as event}]
  (case (kind event)
    "tool.blocked" {:text (str "Blocked " tool ": " (:reason detail)) :color :warning}
    "tool.end" {:text (str tool (when (:title detail) (str ": " (:title detail)))) :color :success}
    "tool.start" {:text (str tool " started") :color :info}
    (let [name
          (event-name event)

          details
          (parse (:details detail))]
      (case name
        "file.edited" {:text (str "Edited " (:file details)) :color :default}
        "session.status" {:text (str "Agent is " (or (get-in details [:status :type]) "active")) :color :default}
        {:text (get event-names name name) :color :default}))))

(defn shown-events
  [events]
  (remove #(or (= "tool.start" (kind %))
               (and (= "event" (kind %)) (= "session.status" (event-name %))))
          events))

(defn- tools-table
  [tools]
  (let [right
        {:align "right"}]
    [layout/table {:el-id els/activity-tools}
     (layout/head "Tool" ["Calls" right] ["Average" right] ["Longest" right])
     (for [{:keys [tool calls avg-ms max-ms]} tools]
       ^{:key tool}
       [layout/table-row {}
        [layout/cell [layout/mono tool]]
        [layout/cell right calls]
        [layout/cell right (str (js/Math.round (or avg-ms 0)) " ms")]
        [layout/cell right (str (or max-ms 0) " ms")]])]))

(defn- activity-view
  [{:keys [state current events tools blocked files-edited last-seen]} live?]
  (let [busy?
        (= :busy (format/kw state))]
    (if (empty? events)
      [layout/empty-state {:title "Nothing reported yet" :el-id els/activity-empty}
       "The session's monitor reports here as soon as the agent starts working. Sessions created before monitoring was added stay empty."]
      [RawStack {:spacing 4}
       [layout/row
        [:> Chip {:color (if (and live? busy?) "info" "default")
                  :data-testid (el/id els/activity-state)
                  :label (cond (not live?) "Session is stopped" busy? "Agent is working" :else "Agent is idle")}]
        (when (and live? current)
          [:> Typography {:variant "body2"}
           "Running " [:strong (:tool current)] (str " since " (format/time-of-day (:ts current)))])
        [RawTypography {:variant "body2" :color "text.secondary"}
         (str "Last update " (format/relative-time last-seen))]]
       (when (pos? (or blocked 0))
         [RawAlert {:severity "warning" :el-id els/activity-blocked}
          (str "The harness policy blocked " (format/plural blocked "tool call")
               ". The agent was told the action isn't allowed.")])
       [layout/two-columns
        [layout/section {:title "Tools used"}
         [tools-table tools]]
        [layout/section {:title "Files edited"}
         [:> Paper {:sx (:panel theme/sx)}
          (when (empty? files-edited)
            [RawTypography {:color "text.secondary"} "No files edited."])
          (for [file files-edited]
            ^{:key file}
            [RawBox {} [layout/mono file]])]]]
       [layout/section {:title "Timeline"
                        :description "Newest first. Reported by the platform's monitor inside the sandbox; the audit trail is the complete record."}
        (into [:> Paper {:sx (:timeline theme/sx) :data-testid (el/id els/activity-timeline)}]
              (for [event (shown-events events)
                    :let [{:keys [text color]}
                          (describe event)]]
                ^{:key (:id event)}
                [layout/list-row {:dense true}
                 [RawTypography {:variant "body2" :color "text.secondary" :sx (:time-column theme/sx)}
                  (format/time-of-day (:ts event))]
                 [RawTypography {:variant "body2" :noWrap true
                                 :sx (if (= :warning color) (:timeline-warning theme/sx) (:grow theme/sx))}
                  text]
                 (when (some? (:duration-ms event))
                   [RawTypography {:variant "body2" :color "text.secondary"} (str (:duration-ms event) " ms")])]))]])))

(defn live-activity
  [{:keys [session-id live?]}]
  [data/loaded {:key [:session-activity session-id]
                :query {:query-id :get-session-activity :session-id session-id}
                :interval (when live? 3000)}
   #(activity-view % live?)])
