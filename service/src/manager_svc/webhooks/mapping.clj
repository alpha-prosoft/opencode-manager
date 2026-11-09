(ns manager-svc.webhooks.mapping
  "How a webhook request becomes run inputs. A mapping is a row per input of
   the target workflow:

     {:input \"brief\" :source :body :value \"$.pull_request.title\"}
     {:input \"brief\" :source :header :value \"X-Event\"}
     {:input \"brief\" :source :query :value \"ref\"}
     {:input \"brief\" :source :constant :value \"Weekly report\"}
     {:input \"brief\" :source :raw-body}

   A body path is $.a.b[0].c, a.b.0.c or a JSON pointer /a/b/0/c. A value
   found as an object or array becomes its JSON text, scalars follow the
   input's JSON Schema type (\"42\" for an integer input becomes 42). Inputs
   without a row or whose value is missing are left out, so the workflow's
   defaults apply; the workflow's input validation has the last word."
  (:require [clojure.string :as string]
            [jsonista.core :as json])
  (:import [java.net URLDecoder]
           [java.nio.charset StandardCharsets]))

(def sources
  #{:body :header :query :constant :raw-body})

(def raw-body-limit
  20000)

(defn- text
  [value]
  (some-> value str string/trim not-empty))

(defn- source-of
  [value]
  (when (or (string? value) (keyword? value))
    (keyword (name value))))

(defn row
  "A mapping row as it is stored: {:input :source :value}, the value kept
   as typed for a constant and left out for the raw body."
  [{:keys [input source value]}]
  (let [source
        (source-of source)]
    (cond-> {:input (text input) :source source}
      (not= :raw-body source) (assoc :value (if (= :constant source) (str (or value "")) (text value))))))

(defn- unescape-pointer
  [segment]
  (-> segment
      (string/replace "~1" "/")
      (string/replace "~0" "~")))

(defn- index-or-key
  [segment]
  (if (re-matches #"\d+" segment)
    (parse-long segment)
    segment))

(defn parse-path
  "Segments (strings and indexes) of a body path; nil for an invalid one."
  [path]
  (let [text
        (string/trim (str path))]
    (cond
      (string/blank? text)
      nil

      (string/starts-with? text "/")
      (mapv (comp index-or-key unescape-pointer) (rest (string/split text #"/" -1)))

      :else
      (let [dotted
            (-> text
                (string/replace #"^\$\.?" "")
                (string/replace #"\[(\d+)\]" ".$1")
                (string/replace #"\[['\"]([^'\"]*)['\"]\]" ".$1")
                (string/replace #"^\." ""))]
        (if (string/blank? dotted)
          []
          (let [segments
                (string/split dotted #"\.")]
            (when-not (some string/blank? segments)
              (mapv index-or-key segments))))))))

(defn at-path
  "[true value] of segments in data, [false nil] when it is not there."
  [data segments]
  (reduce (fn [[_ current] segment]
            (cond
              (and (map? current) (contains? current (str segment)))
              [true (get current (str segment))]

              (and (sequential? current) (int? segment) (< -1 segment (count current)))
              [true (nth current segment)]

              :else
              (reduced [false nil])))
          [true data]
          segments))

(defn- form-params
  [text]
  (into {}
        (for [pair (string/split (str text) #"&")
              :when (not (string/blank? pair))
              :let [[k v] (string/split pair #"=" 2)
                    decode #(URLDecoder/decode (str %) StandardCharsets/UTF_8)]]
          [(decode k) (decode (or v ""))])))

(defn parse-body
  "The body as data: JSON (string keys), a form, else nil."
  [content-type text]
  (cond
    (string/blank? text)
    nil

    (string/includes? (string/lower-case (str content-type)) "application/x-www-form-urlencoded")
    (try
      (form-params text)
      (catch IllegalArgumentException _
        nil))

    :else
    (try
      (json/read-value text)
      (catch Exception _
        nil))))

(defn request-data
  "What a mapping reads from a request: {:body (parsed) :raw :headers
   (lower-case names) :query (string keys)}."
  [{:keys [headers query body-text]}]
  (let [lowered
        (into {} (map (fn [[k v]] [(string/lower-case (name k)) (str v)])) headers)]
    {:body (parse-body (get lowered "content-type") body-text)
     :raw (str body-text)
     :headers lowered
     :query (into {} (map (fn [[k v]] [(name k) (str v)])) query)}))

(defn- found
  [value]
  (if (some? value) [true value] [false nil]))

(defn source-value
  "[found? value] of a mapping row in request data."
  [{:keys [body raw headers query]} {:keys [source value]}]
  (case (keyword source)
    :body (if-let [segments (parse-path value)]
            (at-path body segments)
            [false nil])
    :header (found (get headers (string/lower-case (str value))))
    :query (found (get query (str value)))
    :constant (found value)
    :raw-body (if (string/blank? raw)
                [false nil]
                [true (subs raw 0 (min raw-body-limit (count raw)))])
    [false nil]))

(defn- schema-type
  [property]
  (let [types
        (keep #(some-> (:type %) name) (cons property (or (:anyOf property) (:any-of property))))]
    (first (remove #{"null"} types))))

(defn coerce
  "value as the input's JSON Schema property expects it, where it
   converts cleanly."
  [property value]
  (let [kind
        (schema-type property)]
    (cond
      (or (map? value) (sequential? value))
      (json/write-value-as-string value)

      (and (= "string" kind) (or (number? value) (boolean? value)))
      (str value)

      (and (= "integer" kind) (string? value) (re-matches #"-?\d{1,18}" (string/trim value)))
      (parse-long (string/trim value))

      (and (= "integer" kind) (double? value) (== value (Math/rint value)) (<= -9.0E18 value 9.0E18))
      (long value)

      (and (= "boolean" kind) (string? value) (#{"true" "false"} (string/lower-case value)))
      (= "true" (string/lower-case value))

      :else
      value)))

(defn property-of
  [inputs-schema input]
  (get-in inputs-schema [:properties (keyword input)]))

(defn input-names
  [inputs-schema]
  (mapv name (keys (:properties inputs-schema))))

(defn inputs
  "{:inputs {input value} :missing [input]} of mapping over request data."
  [inputs-schema mapping data]
  (reduce (fn [acc {:keys [input] :as row}]
            (let [[present? value]
                  (source-value data row)]
              (if present?
                (assoc-in acc [:inputs (name input)] (coerce (property-of inputs-schema input) value))
                (update acc :missing conj (name input)))))
          {:inputs {} :missing []}
          mapping))

(defn field-errors
  "[{:field :message}] of validation messages \"path: message\"."
  [messages]
  (vec (for [message messages
             :let [[_ field text] (re-matches #"(?s)([^:\s]+): (.*)" (str message))]]
         (if field
           {:field (when-not (= "file" field) field) :message text}
           {:field nil :message (str message)}))))

(defn mapping-problems
  "Why a mapping can't be saved for a workflow with inputs-schema; empty
   when it can."
  [inputs-schema mapping]
  (let [known
        (set (input-names inputs-schema))]
    (vec
     (concat
      (for [{:keys [input source value]} mapping
            :let [source (keyword source)]
            problem [(cond
                       (nil? input) "Every mapped row names an input"
                       (not (contains? known (name input))) (str "The workflow has no input " input))
                     (when-not (contains? sources source)
                       (str "Unknown source for " (or input "a row without an input")))
                     (when (and (#{:body :header :query} source) (string/blank? (str value)))
                       (str "Say where " input " comes from"))
                     (when (and (= :body source) (not (string/blank? (str value))) (nil? (parse-path value)))
                       (str "The body path of " input " is invalid"))]
            :when problem]
        problem)
      (for [[input rows] (group-by :input mapping)
            :when (and input (< 1 (count rows)))]
        (str "Input " input " is mapped twice"))))))
