(ns manager-svc.platform.sources
  "Non-git team sources: local directories and downloaded archives."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [manager-svc.git :as git]
            [manager-svc.http :as http]
            [manager-svc.paths :as paths])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream File InputStream]
           [java.net URI]
           [java.nio.file FileVisitOption Files LinkOption Path]
           [java.security MessageDigest]
           [java.util HexFormat]
           [java.util.concurrent TimeUnit]
           [java.util.zip ZipInputStream]))

(def max-archive-bytes
  (* 50 1024 1024))

(defn source-error
  [message]
  (ex-info message {:type :source-error :message message}))

(defn local-directory
  [location local-roots]
  (let [path
        (paths/resolved location)]
    (when-not (paths/under-any? path local-roots)
      (throw (source-error (str "local paths must live under " (git/roots-label local-roots)))))
    (when-not (Files/isDirectory path (make-array LinkOption 0))
      (throw (source-error (str location " is not a directory"))))
    (.toFile path)))

(defn- hex
  [^bytes digest length]
  (subs (.formatHex (HexFormat/of) digest) 0 length))

(defn- in-git-dir?
  [^Path relative]
  (some #(= ".git" (str %)) (iterator-seq (.iterator relative))))

(defn- regular-files
  "Relative paths of the regular files under root, without following links."
  [^Path root]
  (with-open [stream (Files/walk root (make-array FileVisitOption 0))]
    (let [paths
          (filter #(Files/isRegularFile % (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                  (iterator-seq (.iterator stream)))]
      (vec (sort-by str (remove in-git-dir? (map #(.relativize root ^Path %) paths)))))))

(defn fingerprint
  "files:<hash> of every regular file (except .git) in directory, by
   relative path. Links are neither followed nor hashed."
  [directory]
  (let [root
        (.toPath (io/file directory))

        files
        (regular-files root)

        digest
        (MessageDigest/getInstance "SHA-256")]
    (doseq [relative files]
      (.update digest (.getBytes (str relative) "UTF-8"))
      (.update digest (Files/readAllBytes (.resolve root relative))))
    (str "files:" (hex (.digest digest) 12))))

(def max-redirects 5)

(defn- host
  [url]
  (.getHost (URI/create url)))

(defn- redirect-target
  [url location]
  (let [target
        (str (.resolve (URI/create url) (str location)))]
    (when-not (string/starts-with? target "https://")
      (throw (source-error "archive downloads may only redirect to https://")))
    target))

(defn- fetch-response
  "The final response of a GET of url, following redirects; the token only
   goes to url's own host."
  [url token]
  (loop [current url
         redirects 0]
    (let [response
          (http/request! {:method :get
                          :url current
                          :headers (if (and token (= (host url) (host current)))
                                     {"Authorization" (str "Bearer " token)}
                                     {})
                          :timeout-seconds 60
                          :stream? true})

          location
          (get-in response [:headers "location"])]
      (if (and (<= 300 (:status response) 399) location (< redirects max-redirects))
        (do
          (.close ^InputStream (:body response))
          (recur (redirect-target current location) (inc redirects)))
        response))))

(defn download
  [url token]
  (let [{:keys [status body]}
        (fetch-response url token)]
    (when (>= status 400)
      (.close ^InputStream body)
      (throw (source-error (str "download of " url " failed with HTTP " status))))
    (with-open [^InputStream body body]
      (let [out
            (ByteArrayOutputStream.)

            buffer
            (byte-array 65536)]
        (loop []
          (let [n
                (.read body buffer)]
            (when (pos? n)
              (.write out buffer 0 n)
              (when (> (.size out) max-archive-bytes)
                (throw (source-error (str "archive " url " is larger than "
                                          (quot max-archive-bytes (* 1024 1024)) " MB"))))
              (recur))))
        (.toByteArray out)))))

(defn- zip?
  [^bytes data]
  (and (> (alength data) 3)
       (= 0x50 (aget data 0))
       (= 0x4b (aget data 1))))

(defn- escapes?
  [target entry-name]
  (not (paths/inside? target (io/file (str (paths/resolved target)) entry-name))))

(defn- extract-zip
  [^bytes data target]
  (with-open [zip (ZipInputStream. (ByteArrayInputStream. data))]
    (loop [entries []]
      (if-let [entry
               (.getNextEntry zip)]
        (do
          (when (escapes? target (.getName entry))
            (throw (source-error (str "archive entry " (.getName entry) " escapes the target directory"))))
          (let [file
                (io/file target (.getName entry))]
            (if (.isDirectory entry)
              (.mkdirs file)
              (do
                (io/make-parents file)
                (io/copy zip file))))
          (recur (conj entries (.getName entry))))
        entries))))

(defn- tar
  [args ^bytes data]
  (let [process
        (.start (ProcessBuilder. ^java.util.List (into ["tar"] args)))

        stdout
        (future (slurp (.getInputStream process)))

        stderr
        (future (slurp (.getErrorStream process)))]
    (with-open [in (.getOutputStream process)]
      (.write in data))
    (when-not (.waitFor process 120 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (source-error "extracting the archive timed out")))
    (if (zero? (.exitValue process))
      @stdout
      (throw (source-error (str "not a valid .tar.gz or .zip archive: " (string/trim @stderr)))))))

(defn- extract-tar
  [^bytes data target]
  (let [entries
        (remove string/blank? (string/split-lines (tar ["-tzf" "-"] data)))]
    (doseq [entry entries
            :when (or (string/starts-with? entry "/") (escapes? target entry))]
      (throw (source-error (str "archive entry " entry " escapes the target directory"))))
    (.mkdirs (io/file target))
    (tar ["-xzf" "-" "-C" (str target) "--no-same-owner" "--no-same-permissions"] data)
    (doseq [^File file (file-seq (io/file target))
            :when (and (Files/isSymbolicLink (.toPath file)) (not (paths/inside? target file)))]
      (throw (source-error (str "archive entry " (.getName file) " links outside the target directory"))))))

(defn extract!
  [^bytes data target]
  (.mkdirs (io/file target))
  (if (zip? data)
    (extract-zip data target)
    (extract-tar data target)))

(defn fetch-archive
  "Downloads and extracts an archive into target; returns sha256:<hash>."
  [url target token]
  (when-not (string/starts-with? url "https://")
    (throw (source-error "archive sources must use https://")))
  (let [data
        (download url token)

        digest
        (.digest (MessageDigest/getInstance "SHA-256") data)

        target-file
        (io/file target)

        staging
        (io/file (.getParentFile target-file) (str (.getName target-file) ".new"))

        _
        (do
          (paths/delete-tree! staging)
          (extract! data staging))

        entries
        (filter #(or (not (string/starts-with? (.getName ^File %) ".")) (= ".platform" (.getName ^File %)))
                (.listFiles staging))

        root
        (let [only (first entries)]
          (if (and (= 1 (count entries)) (.isDirectory ^File only) (not= ".platform" (.getName ^File only)))
            only
            staging))]
    (paths/delete-tree! target-file)
    (Files/move (.toPath ^File root) (.toPath target-file) (make-array java.nio.file.CopyOption 0))
    (paths/delete-tree! staging)
    (str "sha256:" (hex digest 12))))
