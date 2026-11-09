(ns manager-svc.time
  "Timestamps are ISO-8601 strings in aggregates; these helpers take and
   return such strings."
  (:import [java.time Duration Instant]
           [java.time.temporal ChronoUnit]))

(defn now
  "Current time as an ISO-8601 string, the format stored in aggregates."
  []
  (str (Instant/now)))

(defn parse
  [value]
  (when value
    (Instant/parse value)))

(defn seconds-since
  [value]
  (when value
    (.getSeconds (Duration/between (parse value) (Instant/now)))))

(defn days-ago
  [days]
  (str (.minus (Instant/now) (Duration/ofDays days))))

(defn seconds-between
  [from to]
  (.getSeconds (Duration/between (parse from) (parse to))))

(defn plus-seconds
  [value seconds]
  (str (.plusSeconds (parse value) (long seconds))))

(defn after?
  [a b]
  (.isAfter (parse a) (parse b)))

(defn start-of-day
  "Midnight UTC of today, or of the day days before today."
  ([]
   (start-of-day 0))
  ([days]
   (str (.minus (.truncatedTo (Instant/now) ChronoUnit/DAYS) (Duration/ofDays days)))))
