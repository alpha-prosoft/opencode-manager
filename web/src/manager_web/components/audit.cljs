(ns manager-web.components.audit
  "The tamper-evident audit trail of a session."
  (:require ["@mui/icons-material/Download" :default DownloadIcon]
            ["@mui/icons-material/ExpandMore" :default ExpandMoreIcon]
            ["@mui/material/Accordion" :default Accordion]
            ["@mui/material/AccordionDetails" :default AccordionDetails]
            ["@mui/material/AccordionSummary" :default AccordionSummary]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Typography" :default Typography]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.components.markdown :as markdown]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(def type-names
  {"message" "Message"
   "text" "Text"
   "reasoning" "Thinking"
   "tool" "Tool call"
   "conversation" "Conversation"})

(defn- event-type
  [event]
  (some-> (:type event) name))

(defn summary
  [{:keys [payload] :as event}]
  (let [type
        (event-type event)]
    (case type
      "message" (str (if (= "user" (some-> (:role payload) name)) "Prompt" "Agent reply")
                     (when-let [tokens (:tokens payload)]
                       (str ", " (or (:input tokens) 0) " tokens in, " (or (:output tokens) 0) " out")))
      ("text" "reasoning") (format/truncate (:text payload) 160)
      "tool" (str (:tool payload) ": " (or (get-in payload [:state :title]) (some-> (get-in payload [:state :status]) name) ""))
      "conversation" (str "“" (:title payload) "”")
      type)))

(defn- detail
  [{:keys [payload] :as event}]
  (case (event-type event)
    "text" [markdown/markdown (:text payload)]
    "tool" (let [state
                 (:state payload)

                 output
                 (str (or (:output state) (:error state) ""))]
             [RawStack {:spacing 1}
              [RawTypography {:variant "label"} "Input"]
              [layout/code-block {} (format/json (:input state))]
              [RawTypography {:variant "label"} "Output"]
              [layout/code-block {:short? true} (format/truncate output 20000)]])
    [layout/code-block {} (format/json payload)]))

(defn- verification-alert
  [{:keys [valid checked]}]
  [RawAlert {:severity (if valid "success" "error") :sx (:grow theme/sx) :el-id els/audit-verification}
   (if valid
     (str "Verified: all " checked " records are intact. They are stored outside the sandbox and can't be edited.")
     "Verification failed: records were changed after they were written.")])

(def page-size 500)

(defn- record
  [event]
  [:> Accordion {:slotProps {:transition {:unmountOnExit true}}
                 :data-testid (el/id (els/audit-record (:seq event)))}
   [:> AccordionSummary {:expandIcon (r/as-element [:> ExpandMoreIcon])}
    [RawStack {:direction "row" :spacing 2 :sx (:baseline-row theme/sx)}
     [RawTypography {:variant "body2" :color "text.secondary" :sx (:time-column theme/sx)}
      (format/time-of-day (:ts event))]
     [RawTypography {:variant "body2" :sx (:type-column theme/sx)}
      (get type-names (event-type event) (event-type event))]
     [RawTypography {:variant "body2" :noWrap true} (summary event)]]]
   [:> AccordionDetails
    [RawStack {:spacing 1}
     [detail event]
     [:> Typography {:variant "caption" :color "text.secondary"}
      (str "Record " (:seq event) ", fingerprint ")
      [layout/mono (format/truncate (:hash event) 16)]]]]])

(defn- records
  [session-id events]
  (let [{verification :data verify-error :error}
        @(rf/subscribe [::data/load [:audit-verify session-id]])]
    (if (empty? events)
      [layout/empty-state {:title "No audit records yet" :el-id els/audit-empty}
       "Messages and tool calls are recorded here as the agent works."]
      [RawStack {:spacing 2}
       [RawStack {:direction {:xs "column" :sm "row"} :spacing 2 :sx (:responsive-row theme/sx)}
        (when verification
          [verification-alert verification])
        [:> Button {:startIcon (r/as-element [:> DownloadIcon])
                    :href (str "/api/sessions/" session-id "/audit/export")
                    :data-testid (el/id els/audit-download)}
         "Download (JSONL)"]]
       [layout/error-alert verify-error els/audit-verify-error]
       (when (>= (count events) page-size)
         [RawAlert {:severity "info" :el-id els/audit-truncated}
          (str "Showing the first " page-size " records. Download the export for the complete trail.")])
       (into [RawBox {}]
             (for [event events]
               ^{:key (:seq event)}
               [record event]))])))

(defn audit-timeline
  [session-id]
  [:<>
   [data/poll {:key [:audit-verify session-id] :query {:query-id :verify-audit :session-id session-id}}]
   [data/loaded {:key [:audit session-id]
                 :query {:query-id :list-audit-events :session-id session-id :after 0 :limit page-size}}
    #(records session-id %)]])
