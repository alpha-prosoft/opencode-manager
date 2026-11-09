(ns manager-svc.rows
  "Vectors of maps kept unique by a key.")

(defn by-id
  "The row of rows with id, or nil."
  [rows id]
  (some #(when (= id (:id %)) %) rows))

(defn upsert
  "rows with (f row) applied to the first row matching match?, or with
   (f init) appended when none does."
  [rows match? init f]
  (let [rows
        (vec rows)

        index
        (first (keep-indexed (fn [i row] (when (match? row) i)) rows))]
    (if index
      (update rows index f)
      (conj rows (f init)))))
