(ns manager-web.components.patch
  (:require ["@mui/icons-material/Download" :default DownloadIcon]
            ["@mui/material/Paper" :default Paper]
            [clojure.string :as string]
            [manager-web.components.elements :as els]
            [manager-web.el :as el]
            [manager-web.theme :as theme]
            [reagent.core :as r]
            [web.primitives.components :refer [RawBox RawButton RawStack RawTypography]]))

(defn- line-kind
  [line]
  (cond
    (or (string/starts-with? line "+++") (string/starts-with? line "---")) :patch-line-file
    (string/starts-with? line "+") :patch-line-added
    (string/starts-with? line "-") :patch-line-removed
    (string/starts-with? line "@@") :patch-line-hunk
    (string/starts-with? line "diff ") :patch-line-diff
    :else :patch-line))

(defn download!
  [patch file-name]
  (let [url
        (js/URL.createObjectURL (js/Blob. #js [patch] #js {:type "text/x-diff"}))

        anchor
        (js/document.createElement "a")]
    (set! (.-href anchor) url)
    (set! (.-download anchor) file-name)
    (.click anchor)
    (js/URL.revokeObjectURL url)))

(defn patch-view
  [{:keys [patch file-name]}]
  (if (string/blank? patch)
    [RawTypography {:color "text.secondary" :el-id els/patch-empty} "The agent didn't change any files."]
    [RawStack {:spacing 1 :sx (:start-aligned theme/sx)}
     [RawButton {:size "small" :startIcon (r/as-element [:> DownloadIcon]) :el-id els/patch-download
                 :on-click #(download! patch file-name)}
      "Download patch"]
     [:> Paper {:sx (:patch-paper theme/sx) :data-testid (el/id els/patch-view)}
      (into [RawBox {:component "pre" :sx (:patch theme/sx)}]
            (map-indexed (fn [index line]
                           ^{:key index}
                           [RawBox {:component "span" :sx ((line-kind line) theme/sx)}
                            (if (= "" line) " " line)])
                         (string/split patch #"\n" -1)))]]))
