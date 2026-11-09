(ns manager-web.components.build
  "CI build state of a run."
  (:require ["@mui/material/Chip" :default Chip]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Typography" :default Typography]
            [manager-web.components.chips :as chips]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(defn build-chip
  [state]
  [chips/status-chip :build state])

(defn build-panel
  [{:keys [build job state detail checked-at follow-up-run]}]
  [:> Paper {:sx (:panel theme/sx) :data-testid (el/id els/build-panel)}
   [RawStack {:spacing 1.5}
    [layout/row
     [build-chip state]
     (when build
       [layout/external-link {:href (:url build) :el-id els/build-link} (str "Build #" (:number build))])
     [:> Typography {:variant "body2" :color "text.secondary"}
      [layout/mono job] (str " · checked " (format/relative-time checked-at))]]
    (when detail
      [RawTypography {:variant "body2" :color "text.secondary"} detail])
    (when (seq (:stages build))
      [RawBox {:el-id els/build-stages}
       (into [layout/row]
             (for [{stage-name :name :keys [status]} (:stages build)]
               ^{:key stage-name}
               [:> Chip {:color (chips/status-color :stage status) :label stage-name
                         :data-testid (el/id (els/build-stage stage-name))}]))])
    (when follow-up-run
      [RawAlert {:severity "info"}
       [:span "The build failed, so an analysis was started: "
        [layout/link {:page :run :params {:run-id follow-up-run} :el-id els/follow-up-link}
         "open the failure analysis"]
        "."]])]])
