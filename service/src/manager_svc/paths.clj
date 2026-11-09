(ns manager-svc.paths
  "Filesystem paths that must stay inside a base directory."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file FileVisitOption Files LinkOption Path]))

(defn resolved
  "Absolute, normalized path with symlinks resolved when it exists."
  ^Path [location]
  (let [path
        (.normalize (.toAbsolutePath (.toPath (io/file (str location)))))]
    (if (Files/exists path (make-array LinkOption 0))
      (.toRealPath path (make-array LinkOption 0))
      path)))

(defn inside?
  [base location]
  (.startsWith (resolved location) (resolved base)))

(defn under-any?
  [location roots]
  (boolean (some #(inside? % location) roots)))

(defn file-inside?
  "True when relative names a regular file inside base (after resolving links)."
  [base relative]
  (let [path
        (resolved (io/file (str (resolved base)) (str relative)))]
    (and (.startsWith path (resolved base))
         (Files/isRegularFile path (make-array LinkOption 0)))))

(defn delete-tree!
  "Deletes a directory tree without following links."
  [file]
  (let [root
        (.toPath (io/file file))]
    (when (Files/exists root (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (with-open [stream (Files/walk root (make-array FileVisitOption 0))]
        (doseq [^Path path (reverse (vec (iterator-seq (.iterator stream))))]
          (Files/delete path))))))
