(ns manager-web.format
  "Display formatting shared by views."
  (:require [clojure.string :as string]))

(defn kw
  "An enum value as a lower kebab-case keyword, whether it arrived as a
   keyword or a string: \"APPROVE_WITH_NITS\" -> :approve-with-nits."
  [value]
  (when (some? value)
    (keyword (string/lower-case (string/replace (name value) "_" "-")))))

(defn scopes
  "The scopes a credential's :provided-by allows, as a set of :tenant :team :user."
  [provided-by]
  (set (keep kw (if (sequential? provided-by) provided-by [provided-by]))))

(def scope-labels
  {:tenant "Tenant admins"
   :team "Team admins"
   :user "Each person"})

(defn provided-by-label
  [provided-by]
  (let [allowed
        (scopes provided-by)]
    (string/join ", " (keep #(when (contains? allowed %) (scope-labels %)) [:tenant :team :user]))))

(defn shared-source?
  "True for a value that comes from the tenant or the team rather than the person."
  [source]
  (contains? #{:tenant :team :platform} (kw source)))

(defn possessive
  "\"Operations'\", \"acme's\"."
  [name]
  (let [name
        (str name)]
    (if (string/ends-with? name "s") (str name "'") (str name "'s"))))

(defn words
  [field]
  (string/replace (name field) #"[-_]" " "))

(defn label
  "A field name as a sentence-case label: :pull-request -> \"Pull request\"."
  [field]
  (let [text
        (words field)]
    (str (string/upper-case (subs text 0 1)) (subs text 1))))

(defn truncate
  [text n]
  (let [text
        (str text)]
    (subs text 0 (min n (count text)))))

(defn- millis
  [value]
  (.getTime (js/Date. value)))

(defn relative-time
  [value]
  (if (string/blank? value)
    "—"
    (let [seconds
          (js/Math.round (/ (- (js/Date.now) (millis value)) 1000))

          minutes
          (js/Math.round (/ seconds 60))

          hours
          (js/Math.round (/ minutes 60))]
      (cond
        (neg? seconds) (str "in " (js/Math.round (/ (- seconds) 60)) " min")
        (< seconds 60) "just now"
        (< minutes 60) (str minutes " min ago")
        (< hours 24) (str hours " h ago")
        :else (.toLocaleDateString (js/Date. value))))))

(defn date-time
  [value]
  (if (string/blank? value)
    "—"
    (.toLocaleString (js/Date. value))))

(defn time-of-day
  [value]
  (if (string/blank? value)
    ""
    (.toLocaleTimeString (js/Date. value))))

(defn tokens
  [value]
  (let [n
        (or value 0)]
    (cond
      (>= n 1000000) (str (.toFixed (/ n 1000000) 1) "M")
      (>= n 1000) (str (.toFixed (/ n 1000) 1) "k")
      :else (str n))))

(defn money
  [value]
  (if (number? value)
    (str "$" (.toFixed value 2))
    "—"))

(defn short-commit
  [commit]
  (if (string/blank? commit)
    "—"
    (truncate commit 12)))

(defn plural
  [n singular]
  (str n " " singular (when-not (= 1 n) "s")))

(defn gerrit?
  [scm-provider]
  (= :gerrit (kw scm-provider)))

(defn change-name
  "What a pull request is called: \"Change 12\" on Gerrit, \"PR #12\" elsewhere."
  [scm-provider id]
  (if (gerrit? scm-provider)
    (str "Change " id)
    (str "PR #" id)))

(defn trigger-label
  [trigger]
  (let [type
        (kw (:type trigger))]
    (cond
      (:retry-of trigger) (str "Re-run by " (:by trigger))
      (= :ci-failure type) (str "After build #" (:build trigger) " failed")
      (= :manual type) (str "Started by " (:by trigger))
      (= :team-session type) (str "Team session · started by " (:by trigger))
      (= :pull-request type) (str (change-name (get-in trigger [:scm :provider]) (get-in trigger [:pull-request :id]))
                                  " in " (get-in trigger [:scm :repository]))
      (= :schedule type) "Started by the platform (schedule)"
      (and (= :webhook type) (:webhook-name trigger)) (str "Started by the platform (webhook " (:webhook-name trigger) ")")
      (nil? type) "Started by the platform"
      :else (str "Started by the platform (" (words (:type trigger)) ")"))))

(defn run-title
  "A run as a list names it: the title it was given, else its workflow."
  [{:keys [inputs workflow-name workflow-id]}]
  (let [title
        (:title inputs)]
    (if (string/blank? title)
      (or workflow-name workflow-id)
      title)))

(defn workflow-trigger
  [spec]
  (let [trigger
        (or (:trigger spec) {:type :manual})]
    (case (kw (:type trigger))
      :pull-request (str "Runs on new pull requests in " (:repository trigger))
      :schedule (str "Runs every " (:every-minutes trigger) " min")
      "Started by hand")))

(defn run-active?
  [status]
  (not (contains? #{:completed :failed :cancelled} (kw status))))

(defn session-live?
  [status]
  (contains? #{:running :starting} (kw status)))

(defn json
  [value]
  (.stringify js/JSON (clj->js value) nil 2))

(defn source-label
  [{:keys [repository]}]
  (let [kind
        (get {:git "Git" :url "Archive" :path "Local folder"} (kw (:type repository)))]
    (str kind ": " (or (:url repository) (:location repository))
         (when-not (string/blank? (:branch repository))
           (str " (" (:branch repository) ")")))))
