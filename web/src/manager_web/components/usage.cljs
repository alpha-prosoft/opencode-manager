(ns manager-web.components.usage
  (:require [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.format :as format]
            [web.primitives.components :refer [RawBox]]))

(defn usage-stats
  [{:keys [calls prompt-tokens completion-tokens cached-tokens errors avg-latency-ms]}]
  [RawBox {:el-id els/usage-stats}
   [layout/stat-grid
    [layout/stat {:label "LLM calls" :value (or calls 0)}]
    [layout/stat {:label "Prompt tokens" :value (format/tokens prompt-tokens)
                  :hint (str (format/tokens cached-tokens) " from cache")}]
    [layout/stat {:label "Completion tokens" :value (format/tokens completion-tokens)}]
    [layout/stat {:label "Average latency" :value (str (.toFixed (/ (or avg-latency-ms 0) 1000) 1) " s")}]
    (when (pos? (or errors 0))
      [layout/stat {:label "Failed calls" :value errors}])]])

(defn usage-section
  [usage]
  (when (pos? (or (:calls usage) 0))
    [layout/section {:title "LLM usage" :description "Measured by the platform's gateway, not reported by the agent."}
     [usage-stats usage]]))
