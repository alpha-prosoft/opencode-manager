(ns manager-svc.webhooks.limiter
  "Rate limits: token buckets holding per-minute requests that refill
   continuously, kept in the process (each manager replica limits on its
   own). A webhook has one bucket for the requests it accepts and one per
   source address for those it refuses, so callers without the secret can't
   use up what the real caller is allowed.")

(def default-per-minute 60)

(def refill-ms 60000)

(def max-buckets 10000)

(defn limits
  "The component holding every bucket."
  []
  (atom {}))

(defn- refilled
  [{:keys [tokens at]} per-minute now-ms]
  (if at
    (min per-minute (+ tokens (* per-minute (/ (- now-ms at) (double refill-ms)))))
    per-minute))

(defn- without-full
  "Buckets nobody used for a whole refill are full again, so forgetting
   them changes nothing."
  [all now-ms]
  (if (< (count all) max-buckets)
    all
    (into {} (remove (fn [[_ {:keys [at]}]] (>= (- now-ms at) refill-ms))) all)))

(defn take!
  "{:allowed true} when bucket (a webhook id, or [webhook-id source] for
   refused requests) may take another request now, else {:allowed false
   :retry-after seconds}."
  [buckets bucket per-minute now-ms]
  (let [per-minute
        (max 1 (or per-minute default-per-minute))

        [before after]
        (swap-vals! buckets
                    (fn [all]
                      (let [tokens
                            (refilled (get all bucket) per-minute now-ms)]
                        (assoc (without-full all now-ms)
                               bucket
                               {:tokens (if (>= tokens 1) (dec tokens) tokens)
                                :at now-ms}))))

        available
        (refilled (get before bucket) per-minute now-ms)]
    (if (>= available 1)
      {:allowed true}
      {:allowed false
       :retry-after (long (Math/ceil (/ (- 1 (:tokens (get after bucket))) (/ per-minute 60.0))))})))
