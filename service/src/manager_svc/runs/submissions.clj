(ns manager-svc.runs.submissions
  "The results agents submit through the /mcp tools (submit_review,
   submit_implementation, submit_document). Mirrors the submission models of
   manager/app/platform/handlers.py and review_post.py."
  (:require [clojure.string :as string]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]))

(defn- text
  [& {:keys [min max]}]
  [:string (cond-> {}
             min (assoc :min min)
             max (assoc :max max))])

(defn kebab-keys
  "Input maps arrive with snake_case or kebab-case keys."
  [m]
  (if (map? m)
    (into {}
          (map (fn [[k v]] [(keyword (string/replace (name k) "_" "-")) v]))
          m)
    m))

(def ^:private decoder
  (mt/transformer mt/string-transformer mt/default-value-transformer))

(defn- describe-errors
  [explanation]
  (string/join "; "
               (for [[field messages] (me/humanize explanation)]
                 (str (name field) ": " (string/join ", " (if (sequential? messages) messages [messages]))))))

(defn validate
  "Decoded value, or throws ex-info {:status 422} with field: message pairs."
  [schema value]
  (let [decoded
        (m/decode schema (kebab-keys (or value {})) decoder)]
    (if (m/validate schema decoded)
      decoded
      (throw (ex-info (describe-errors (m/explain schema decoded))
                      {:status 422
                       :message (describe-errors (m/explain schema decoded))})))))

(def severity-order
  {"blocker" 0 "major" 1 "minor" 2 "question" 3 "nit" 4})

(def review-finding
  [:map
   [:severity (into [:enum] (keys severity-order))]
   [:title (text :max 200)]
   [:body (text :max 4000)]
   [:path {:optional true} [:maybe :string]]
   [:line {:optional true} [:maybe [:int {:min 1}]]]
   [:suggestion {:optional true} [:maybe (text :max 4000)]]])

(def review-submission
  [:map
   [:verdict [:enum "APPROVE" "APPROVE_WITH_NITS" "REQUEST_CHANGES"]]
   [:summary (text :max 4000)]
   [:findings {:default []} [:vector {:max 100} review-finding]]
   [:checked {:default []} [:vector {:max 50} :string]]])

(def implementation-submission
  [:map
   [:title {:default ""} (text :max 120)]
   [:summary (text :max 4000)]
   [:verified {:default false} :boolean]
   [:changes {:default []} [:vector {:max 100} :string]]
   [:tests {:default []} [:vector {:max 50} :string]]
   [:notes {:default ""} (text :max 4000)]])

(def document-submission
  [:map
   [:title (text :max 200)]
   [:markdown (text :max 200000)]])

(def submissions
  {"submit_review" review-submission
   "submit_implementation" implementation-submission
   "submit_document" document-submission})

(defn validate-submission
  [tool payload]
  (if-let [schema (get submissions tool)]
    (validate schema payload)
    (throw (ex-info (str "Unknown result tool " tool) {:status 422 :message (str "Unknown result tool " tool)}))))
