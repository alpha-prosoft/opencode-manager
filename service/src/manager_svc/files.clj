(ns manager-svc.files
  "Files holding secrets: created readable by the owner only, never through
   a symlink, and never visible half-written."
  (:require [clojure.string :as string])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileAlreadyExistsException Files LinkOption OpenOption Path Paths StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(set! *warn-on-reflection* true)

(defn- owner-only
  ^"[Ljava.nio.file.attribute.FileAttribute;" []
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rw-------"))]))

(defn- ->path
  ^Path [location]
  (Paths/get (str location) (make-array String 0)))

(defn temp-private!
  "A new temporary file readable by the owner only, holding content."
  ^Path [prefix content]
  (let [path
        (Files/createTempFile prefix "" (owner-only))]
    (Files/writeString path content StandardCharsets/UTF_8 (make-array OpenOption 0))
    path))

(defn read-private
  "Content of a regular file that is not a symlink, else nil."
  [location]
  (let [path
        (->path location)]
    (when (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (Files/readString path StandardCharsets/UTF_8))))

(defn read-or-create!
  "The trimmed content of a private file, creating it with (generate) when
   missing. When two processes create it at once, both read the winner's."
  [location generate]
  (or (some-> (read-private location) string/trim)
      (let [target
            (->path location)

            dir
            (.getParent (.toAbsolutePath target))

            _
            (Files/createDirectories dir (make-array FileAttribute 0))

            tmp
            (Files/createTempFile dir ".key-" ".tmp" (owner-only))]
        (try
          (Files/writeString tmp (str (generate)) StandardCharsets/UTF_8
                             (into-array OpenOption [StandardOpenOption/TRUNCATE_EXISTING]))
          (Files/createLink target tmp)
          (catch FileAlreadyExistsException _
            nil)
          (finally
            (Files/deleteIfExists tmp)))
        (or (some-> (read-private location) string/trim)
            (throw (ex-info (str location " is not a regular file") {}))))))
