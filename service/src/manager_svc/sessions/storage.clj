(ns manager-svc.sessions.storage
  "A session's files on the shared mount (manager/app/storage.py):

     sessions/<id>/{home,workspace}   mounted read-write at /session
     configs/<id>/mount/              sandbox config files, mounted read-only
     configs/<id>/sandbox.edn         the sandbox description, host only

   A sandbox is {:image :network :cpus :memory-mb :pids :llm-connection
   :llm-models :team-id :run-id :idle-minutes :mcp-servers :env :files}."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file CopyOption Files LinkOption Path StandardCopyOption]
           [java.nio.file.attribute PosixFilePermissions]))

(def session-uid 1000)
(def session-dirs ["home" "workspace"])
(def opencode-data "home/.local/share/opencode")
(def opencode-db-files #{"opencode.db" "opencode.db-wal" "opencode.db-shm"})

(defn session-subpath
  [session-id]
  (str "sessions/" session-id))

(defn config-subpath
  [session-id]
  (str "configs/" session-id "/mount"))

(defn- root
  [settings]
  (io/file (:shared-mount-path settings)))

(defn session-dir
  ^File [settings session-id]
  (io/file (root settings) (session-subpath session-id)))

(defn workspace
  ^File [settings session-id]
  (io/file (session-dir settings session-id) "workspace"))

(defn- mount-dir
  ^File [settings session-id]
  (io/file (root settings) (config-subpath session-id)))

(defn- sandbox-file
  ^File [settings session-id]
  (io/file (root settings) "configs" (str session-id) "sandbox.edn"))

(def ^:private no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- symlink?
  [^File file]
  (Files/isSymbolicLink (.toPath file)))

(defn- tree
  "The directory and everything below it, not following symlinks."
  [^File dir]
  (tree-seq (fn [^File f] (and (.isDirectory f) (not (symlink? f))))
            (fn [^File f] (seq (.listFiles f)))
            dir))

(defn- chown!
  [^File file]
  (try
    (Files/setAttribute (.toPath file) "unix:uid" (int session-uid) no-follow)
    (Files/setAttribute (.toPath file) "unix:gid" (int session-uid) no-follow)
    (catch Exception _
      nil)))

(defn- chmod!
  [^File file mode]
  (Files/setPosixFilePermissions (.toPath file) (PosixFilePermissions/fromString mode)))

(defn- delete-tree!
  [^File dir]
  (when (or (.exists dir) (symlink? dir))
    (doseq [^File file (reverse (tree dir))]
      (Files/deleteIfExists (.toPath file)))))

(defn hand-over!
  "Gives the session's files to the sandbox user."
  [settings session-id]
  (doseq [file (tree (session-dir settings session-id))
          :when (not (symlink? file))]
    (chown! file)))

(defn prepare!
  [settings session-id]
  (doseq [dir session-dirs]
    (.mkdirs (io/file (session-dir settings session-id) dir)))
  (hand-over! settings session-id))

(defn- copy-tree!
  [^File source ^File target skip?]
  (let [source-path
        (.toPath source)

        options
        (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/COPY_ATTRIBUTES LinkOption/NOFOLLOW_LINKS])]
    (doseq [^File file (tree source)
            :let [relative (.relativize source-path (.toPath file))
                  ^Path destination (.resolve (.toPath target) relative)]
            :when (not (some skip? (map str relative)))]
      (if (and (.isDirectory file) (not (symlink? file)))
        (Files/createDirectories destination (make-array java.nio.file.attribute.FileAttribute 0))
        (do
          (Files/createDirectories (.getParent destination) (make-array java.nio.file.attribute.FileAttribute 0))
          (Files/copy (.toPath file) destination ^"[Ljava.nio.file.CopyOption;" options))))))

(defn copy-session!
  "Copies files and OpenCode's database (with its WAL) from source to target."
  [settings source-id target-id]
  (let [source
        (session-dir settings source-id)

        target
        (session-dir settings target-id)]
    (doseq [dir session-dirs
            :let [from (io/file source dir)]
            :when (.isDirectory from)]
      (copy-tree! from (io/file target dir) #(or (opencode-db-files %) (= "log" %))))
    (doseq [db-file opencode-db-files
            :let [from (io/file source opencode-data db-file)]
            :when (.isFile from)]
      (io/make-parents (io/file target opencode-data db-file))
      (io/copy from (io/file target opencode-data db-file)))
    (hand-over! settings target-id)))

(defn write-sandbox!
  [settings session-id {:keys [files] :as sandbox}]
  (let [mount
        (mount-dir settings session-id)

        host-only
        (sandbox-file settings session-id)]
    (doseq [dir (filter #(.isDirectory ^File %) (tree mount))]
      (chmod! dir "rwxr-xr-x"))
    (delete-tree! mount)
    (.mkdirs mount)
    (doseq [[relative content] files
            :let [file (io/file mount (name relative))]]
      (io/make-parents file)
      (spit file content)
      (chmod! file "r--r--r--"))
    (doseq [dir (filter #(.isDirectory ^File %) (tree mount))]
      (chmod! dir "r-xr-xr-x"))
    (io/make-parents host-only)
    (spit host-only (pr-str (dissoc sandbox :files)))
    (chmod! host-only "rw-------")))

(defn load-sandbox
  [settings session-id]
  (let [file
        (sandbox-file settings session-id)]
    (when (.isFile file)
      (edn/read-string (slurp file)))))

(defn mcp-servers
  [settings session-id]
  (vec (:mcp-servers (load-sandbox settings session-id))))

(defn remove!
  [settings session-id]
  (let [mount
        (mount-dir settings session-id)]
    (delete-tree! (session-dir settings session-id))
    (doseq [dir (filter #(.isDirectory ^File %) (tree mount))]
      (chmod! dir "rwxr-xr-x"))
    (delete-tree! (io/file (root settings) "configs" (str session-id)))))
