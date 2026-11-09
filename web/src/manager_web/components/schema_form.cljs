(ns manager-web.components.schema-form
  "A form for a JSON schema of workflow inputs. Text fields are uncontrolled
   (their parent remounts the form to reset it); values go to on-change."
  (:require ["@mui/material/Autocomplete" :default Autocomplete]
            ["@mui/material/FormControlLabel" :default FormControlLabel]
            ["@mui/material/MenuItem" :default MenuItem]
            ["@mui/material/Switch" :default Switch]
            ["@mui/material/TextField" :default TextField]
            [manager-web.components.elements :as els]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [reagent.core :as r]
            [web.primitives.components :refer [RawStack]]))

(def ^:private long-text #{"prompt" "task" "brief"})

(defn attr
  "A schema attribute that may arrive camelCase or kebab-case."
  [schema camel kebab]
  (let [value
        (get schema camel)]
    (if (some? value) value (get schema kebab))))

(defn resolve-field
  "A nullable anyOf collapses to its one real option."
  [schema]
  (let [options
        (remove #(= "null" (some-> (:type %) name)) (attr schema :anyOf :any-of))]
    (if (= 1 (count options))
      (assoc (first options) :description (:description schema) :default (:default schema))
      schema)))

(defn field-name
  [field]
  (format/kw field))

(defn pull-request-field?
  [field]
  (= :pull-request (field-name field)))

(defn takes-pull-request?
  [schema]
  (boolean (some pull-request-field? (keys (:properties schema)))))

(defn- pr-label
  [^js pr]
  (str "#" (.-id pr) " " (.-title pr) " (" (.-author pr) ")"))

(defn- text-field
  [props & children]
  (into [:> TextField (merge {:fullWidth true} props)] children))

(defn- field-view
  [{:keys [field schema required? value on-set pull-requests]}]
  (let [label
        (format/label field)

        id
        (el/id (els/schema-field (name field)))

        common
        {:label label
         :helperText (:description schema)
         :required required?
         :id id}

        type
        (some-> (:type schema) name)

        default
        (:default schema)

        current
        (get value field)]
    (cond
      (= "boolean" type)
      [:> FormControlLabel
       {:label label
        :control (r/as-element [:> Switch {:checked (boolean (if (some? current) current default))
                                           :id id
                                           :on-change #(on-set field (.. % -target -checked))}])}]

      (:enum schema)
      (into [text-field (merge common {:select true
                                       :value (or current default "")
                                       :on-change #(on-set field (.. % -target -value))})]
            (for [option (:enum schema)]
              ^{:key option}
              [:> MenuItem {:value option} option]))

      (and (pull-request-field? field) (seq pull-requests))
      [:> Autocomplete
       {:options (clj->js pull-requests)
        :value (some-> (some #(when (= (:id %) current) %) pull-requests) clj->js)
        :getOptionLabel pr-label
        :isOptionEqualToValue (fn [^js a ^js b] (= (.-id a) (.-id b)))
        :onChange (fn [_ ^js pr] (on-set field (some-> pr .-id)))
        :id id
        :renderInput (fn [params]
                       (r/create-element TextField
                                         (js/Object.assign params #js {:label label
                                                                       :helperText (:description schema)
                                                                       :required required?})))}]

      (contains? #{"integer" "number"} type)
      [text-field (merge common {:type "number"
                                 :defaultValue (get value field "")
                                 :placeholder (when (some? default) (str default))
                                 :on-change #(let [raw (.. % -target -value)]
                                               (on-set field (when-not (= "" raw) (js/Number raw))))})]

      :else
      (let [long?
            (or (contains? long-text (name (field-name field)))
                (> (or (attr schema :maxLength :max-length) 0) 500))]
        [text-field (merge common {:defaultValue (get value field "")
                                   :multiline long?
                                   :minRows (when long? 5)
                                   :on-change #(on-set field (.. % -target -value))})]))))

(defn set-input
  "Inputs after a field changed; empty values are dropped."
  [inputs field next]
  (if (or (nil? next) (= "" next))
    (dissoc inputs field)
    (assoc inputs field next)))

(defn schema-form
  "on-change is called with the field name and its new value."
  [{:keys [schema value on-change pull-requests]}]
  (let [required
        (set (map field-name (:required schema)))]
    (into [RawStack {:spacing 2}]
          (for [[field raw] (:properties schema)]
            ^{:key field}
            [field-view {:field field
                         :schema (resolve-field raw)
                         :required? (contains? required (field-name field))
                         :value value
                         :on-set on-change
                         :pull-requests pull-requests}]))))
