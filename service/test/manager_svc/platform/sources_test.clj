(ns manager-svc.platform.sources-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [manager-svc.http]
            [manager-svc.platform.fixtures :as f]
            [manager-svc.platform.sources :as sources]
            [manager-svc.test-support :as support])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.file Files]
           [java.util.zip ZipEntry ZipOutputStream]))

(defn- zip-bytes
  [entry-name content]
  (let [out
        (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (.putNextEntry zip (ZipEntry. ^String entry-name))
      (.write zip (.getBytes ^String content))
      (.closeEntry zip))
    (.toByteArray out)))

(defn- tar-bytes
  [dir & args]
  (let [archive
        (io/file dir "archive.tgz")]
    (apply shell/sh "tar" "-czf" (.getPath archive) (concat args [:dir (.getPath (io/file dir))]))
    (Files/readAllBytes (.toPath archive))))

(deftest zip-entries-cannot-escape-the-target
  (let [dir
        (support/temp-dir)]
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"escapes the target directory"
                       (sources/extract! (zip-bytes "../escaped.txt" "owned") (io/file dir "t1"))))
    (is
     (not (.exists (io/file dir "escaped.txt"))))
    (sources/extract! (zip-bytes ".platform/agents/a.json" "{}") (io/file dir "t2"))
    (is
     (= "{}"
        (slurp (io/file dir "t2/.platform/agents/a.json"))))))

(deftest tar-entries-cannot-escape-the-target
  (let [dir
        (support/temp-dir)

        source
        (io/file dir "src")]
    (f/write-files! source {"inside/file.txt" "ok"})
    (let [archive
          (tar-bytes source "--transform" "s,^inside,../../escaped," "inside")]
      (is
       (thrown-with-msg? clojure.lang.ExceptionInfo #"escapes the target directory"
                         (sources/extract! archive (io/file dir "t1"))))
      (is
       (not (.exists (io/file dir "escaped")))))
    (sources/extract! (tar-bytes source "inside") (io/file dir "t2"))
    (is
     (= "ok"
        (slurp (io/file dir "t2/inside/file.txt"))))))

(deftest local-directories-stay-under-the-roots
  (let [dir
        (support/temp-dir)]
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"must live under"
                       (sources/local-directory "/etc" [(.getPath dir)])))
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"not a directory"
                       (sources/local-directory (.getPath (io/file dir "missing")) [(.getPath dir)])))))

(deftest fingerprints-follow-content
  (let [dir
        (support/temp-dir)

        _
        (f/write-files! dir {"a.txt" "one"})

        before
        (sources/fingerprint dir)]
    (f/write-files! dir {".git/HEAD" "ignored"})
    (is
     (= before
        (sources/fingerprint dir)))
    (f/write-files! dir {"a.txt" "two"})
    (is
     (not= before
           (sources/fingerprint dir)))))

(deftest fingerprints-neither-follow-nor-hash-links
  (let [dir
        (support/temp-dir)

        outside
        (support/temp-dir)

        _
        (f/write-files! dir {"a.txt" "one"})

        _
        (f/write-files! outside {"secret.txt" "s"})

        before
        (sources/fingerprint dir)]
    (Files/createSymbolicLink (.toPath (io/file dir "out")) (.toPath outside) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createSymbolicLink (.toPath (io/file dir "loop")) (.toPath dir) (make-array java.nio.file.attribute.FileAttribute 0))
    (is
     (= before
        (sources/fingerprint dir)))))

(deftest archive-tokens-stay-on-the-original-host
  (let [calls
        (atom [])]
    (with-redefs [manager-svc.http/request! (fn [{:keys [url headers]}]
                                              (swap! calls conj [url (get headers "Authorization")])
                                              (if (= "https://git.example/archive.zip" url)
                                                {:status 302
                                                 :headers {"location" "https://cdn.example/blob.zip"}
                                                 :body (java.io.ByteArrayInputStream. (byte-array 0))}
                                                {:status 200
                                                 :headers {}
                                                 :body (java.io.ByteArrayInputStream. (.getBytes "data"))}))]
      (is
       (= "data"
          (String. ^bytes (sources/download "https://git.example/archive.zip" "tok"))))
      (is
       (= [["https://git.example/archive.zip" "Bearer tok"] ["https://cdn.example/blob.zip" nil]]
          @calls)))))

(deftest archive-redirects-must-stay-on-https
  (with-redefs [manager-svc.http/request! (fn [_]
                                            {:status 302
                                             :headers {"location" "http://plain.example/x.zip"}
                                             :body (java.io.ByteArrayInputStream. (byte-array 0))})]
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"only redirect to https"
                       (sources/download "https://git.example/archive.zip" "tok")))))
