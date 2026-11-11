(ns manager-web.guide-doc
  "The guides' block DSL and its Markdown rendering for MkDocs Material.
   Text is Markdown: UI labels in **bold**, links as [text](page.md#anchor)."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [manager-web.guide-shots :as shots])
  (:import [javax.imageio ImageIO]))

(def site-dir
  "../docs/guide")

(def ^:dynamic *blocks* nil)

(defn- add!
  [block]
  (swap! *blocks* conj block)
  nil)

(defn chapter!
  [title & [intro]]
  (add! {:type :chapter :title title :intro intro}))

(defn section!
  [title & [intro]]
  (add! {:type :section :title title :intro intro}))

(defn p!
  [text]
  (add! {:type :p :text text}))

(defn steps!
  [& items]
  (add! {:type :steps :items items}))

(defn bullets!
  [& items]
  (add! {:type :bullets :items items}))

(defn table!
  [header & rows]
  (add! {:type :table :header header :rows rows}))

(defn tip!
  [text]
  (add! {:type :tip :text text}))

(defn note!
  [text]
  (add! {:type :note :text text}))

(defn figure!
  "Takes a screenshot via `shots/shot!` and adds it with `caption`."
  [name caption opts]
  (shots/shot! name opts)
  (add! {:type :figure :img name :caption caption}))

(defn slot!
  "Reserves a place in the guide for blocks added later with `fill!`: what a
   slow run produces is documented where it belongs, not where it finished."
  []
  (let [blocks
        (atom [])]
    (add! {:type :slot :blocks blocks})
    blocks))

(defn fill!
  [slot body-fn]
  (binding [*blocks* slot]
    (body-fn)))

(defn- flatten-slots
  [blocks]
  (mapcat (fn [block]
            (if (= :slot (:type block))
              (flatten-slots @(:blocks block))
              [block]))
          blocks))

(defn published-figure!
  "Keeps a figure of the published guide that this run can't take again (the
   provider's login page while the stack runs without one); false when there
   is none."
  [name caption]
  (let [published?
        (.isFile (io/file site-dir "img" (str name ".png")))]
    (when published?
      (println "  [guide] kept the published figure" name)
      (add! {:type :figure :img name :caption caption}))
    published?))

(defn- caption-html
  [caption]
  (-> caption
      (string/replace "&" "&amp;")
      (string/replace "<" "&lt;")
      (string/replace ">" "&gt;")
      (string/replace #"\*\*(.+?)\*\*" "<strong>$1</strong>")
      (string/replace #"`(.+?)`" "<code>$1</code>")))

(defn- alt-text
  [caption]
  (-> caption
      (string/replace #"\*\*|[\[\]`\"]" "")
      (string/replace #"\s+" " ")))

(defn- row
  [cells]
  (str "| " (string/join " | " cells) " |"))

(def device-scale
  "Screenshots are taken at this device scale factor, so a figure shown at
   its pixel width divided by it is as large as the page was on screen."
  2)

(defn- width-attribute
  [img]
  (let [^java.io.File file
        (first (filter #(.isFile ^java.io.File %)
                       [(io/file shots/out-dir (str img ".png")) (io/file site-dir "img" (str img ".png"))]))]
    (if-let [image (some-> file ImageIO/read)]
      (str " width=\"" (quot (.getWidth image) device-scale) "\"")
      "")))

(defn- render-block
  [{:keys [type title intro text items img caption header rows]}]
  (case type
    :chapter (str "## " title (when intro (str "\n\n" intro)))
    :section (str "### " title (when intro (str "\n\n" intro)))
    :p text
    :steps (string/join "\n" (map-indexed (fn [idx item] (str (inc idx) ". " item)) items))
    :bullets (string/join "\n" (map #(str "- " %) items))
    :table (string/join "\n" (concat [(row header) (row (map (constantly "---") header))] (map row rows)))
    :tip (str "!!! tip\n\n    " text)
    :note (str "!!! note\n\n    " text)
    :figure (str "<figure markdown>\n"
                 "![" (alt-text caption) "](img/" img ".png){ loading=lazy" (width-attribute img) " }\n"
                 "<figcaption>" (caption-html caption) "</figcaption>\n"
                 "</figure>")))

(defn markdown
  [{:keys [title lead]} blocks]
  (str "# " title "\n\n"
       lead "\n\n"
       (string/join "\n\n" (map render-block blocks))
       "\n"))

(defn- image-names
  [blocks]
  (set (keep :img blocks)))

(defn- remove-stale-images!
  [prefix names]
  (doseq [^java.io.File file (.listFiles (io/file site-dir "img"))
          :let [file-name (.getName file)]
          :when (and (string/starts-with? file-name prefix)
                     (re-matches #"[a-z]+\d\d-.*\.png" file-name)
                     (not (contains? names (string/replace file-name #"\.png$" ""))))]
    (.delete file)))

(defn render!
  "Writes the guide's page and its images into the docs; images of the same
   guide (`:prefix`) it no longer uses are removed."
  [{:keys [file prefix] :as guide} blocks]
  (let [names
        (image-names blocks)

        page
        (io/file site-dir file)]
    (io/make-parents (io/file site-dir "img" "x"))
    (doseq [img names
            :let [shot (io/file shots/out-dir (str img ".png"))]
            :when (.isFile shot)]
      (io/copy shot (io/file site-dir "img" (str img ".png"))))
    (remove-stale-images! prefix names)
    (spit page (markdown guide blocks))
    (println "  [guide] wrote" (.getPath page) "with" (count names) "figures")))

(defn run-guide!
  "Runs `body-fn` collecting its blocks and renders the guide only when it
   completed; a failure saves FAILED-<prefix>.png under target/guide/img and
   leaves the published page as it was. True when rendered."
  [{:keys [title prefix] :as guide} body-fn]
  (println "\n== Guide:" title)
  (let [blocks
        (atom [])

        started
        (System/currentTimeMillis)]
    (try
      (binding [*blocks* blocks]
        (body-fn))
      (render! guide (flatten-slots @blocks))
      (println "  [guide]" title "took" (quot (- (System/currentTimeMillis) started) 1000) "s")
      true
      (catch Throwable t
        (println "  [guide] FAILED:" title "-" (ex-message t))
        (.printStackTrace t)
        (try
          (shots/shot! (str "FAILED-" prefix) {:full true :scroll false})
          (catch Throwable shot-error
            (println "  [guide] no failure screenshot:" (ex-message shot-error))))
        false))))
