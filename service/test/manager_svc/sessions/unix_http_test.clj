(ns manager-svc.sessions.unix-http-test
  (:require [clojure.test :refer [deftest is]]
            [manager-svc.sessions.fixtures :as fixtures]
            [manager-svc.sessions.unix-http :as http])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel]))

(deftest a-daemon-that-does-not-answer-times-out
  (let [path
        (str (fixtures/temp-dir) "/docker.sock")

        server
        (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
          (.bind (UnixDomainSocketAddress/of ^String path)))

        accepted
        (future (.accept server))

        started
        (System/currentTimeMillis)]
    (try
      (is
       (= "Docker API /containers/json timed out after 300 ms"
          (try
            (http/request path :get "/containers/json" nil {:timeout-ms 300})
            (catch clojure.lang.ExceptionInfo e
              (ex-message e)))))
      (is
       (< (- (System/currentTimeMillis) started) 5000))
      (finally
        (some-> (deref accepted 1000 nil) (.close))
        (.close server)))))
