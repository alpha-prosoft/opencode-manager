(ns manager-web.components.review
  "A code review result."
  (:require ["@mui/icons-material/CheckCircleOutlineOutlined" :default CheckIcon]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/List" :default List]
            ["@mui/material/ListItem" :default ListItem]
            ["@mui/material/ListItemText" :default ListItemText]
            ["@mui/material/Paper" :default Paper]
            [manager-web.components.chips :as chips]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.components.markdown :as markdown]
            [manager-web.format :as format]
            [manager-web.theme :as theme]
            [web.primitives.components :refer [RawAlert RawStack RawTypography]]))

(defn posting-text
  ([result]
   (posting-text result nil))
  ([{:keys [plan posted branch]} scm-provider]
   (let [comments
         (format/plural (or (:comments plan) 0) "comment")

         target
         (if (format/gerrit? scm-provider) "the change" "the pull request")]
     (if branch
       (str "Review of branch " (:name branch) " against " (:base branch) ": nothing is posted. "
            comments " planned.")
       (case (format/kw (:mode plan))
         :auto (str posted " of " comments " posted to " target " ("
                    (:inline plan) " on lines, " (:folded plan) " in the summary).")
         :dry-run (str "Dry run: nothing was posted. " comments " would be posted to " target ".")
         "Posting is off for this workflow: the review is only shown here.")))))

(defn- finding-view
  [{:keys [severity title path line body suggestion]}]
  (let [severity
        (some-> severity name)]
    [:> Paper {:sx (:panel theme/sx)}
     [RawStack {:direction "row" :spacing 1 :useFlexGap true :sx (:finding-header theme/sx)}
      [:> Chip {:label severity :color (chips/status-color :severity severity)}]
      [RawTypography {:sx (:strong theme/sx)} title]
      (when path
        [layout/mono (str path (when line (str ":" line)))])]
     [markdown/markdown body]
     (when suggestion
       [layout/code-block {} suggestion])]))

(defn review-result
  [{:keys [review] :as result} & [scm-provider]]
  (let [verdict
        (:verdict review)]
    [RawStack {:spacing 2}
     [RawAlert {:severity (chips/status-color :verdict verdict "info") :el-id els/review-verdict}
      [:<> [:strong (chips/status-label :verdict verdict)] (str " — " (:summary review))]]
     [RawTypography {:variant "body2" :color "text.secondary"} (posting-text result scm-provider)]
     (when (empty? (:findings review))
       [RawTypography {:color "text.secondary"} "No findings."])
     (for [[index finding] (map-indexed vector (:findings review))]
       ^{:key index}
       [finding-view finding])
     (when (seq (:checked review))
       [:> Paper {:sx (:panel theme/sx)}
        [RawTypography {:variant "subtitle2" :gutterBottom true} "Checked and found fine"]
        (into [:> List {:dense true :disablePadding true}]
              (for [[index item] (map-indexed vector (:checked review))]
                ^{:key index}
                [:> ListItem {:disableGutters true}
                 [:> CheckIcon {:fontSize "small" :color "success" :sx (:icon-gap theme/sx)}]
                 [:> ListItemText {:primary item}]]))])]))
