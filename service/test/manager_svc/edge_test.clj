(ns manager-svc.edge-test
  (:require [clojure.test :refer [deftest is testing]]
            [lambda.util :as util]
            [manager-svc.auth :as auth]
            [manager-svc.client :as client]
            [manager-svc.edge.api :as edge-api]
            [manager-svc.edge.server :as server]
            [manager-svc.edge.static :as static]
            [manager-svc.effects :as effects]
            [manager-svc.test-support :as support]
            [manager-svc.workers :as workers])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def settings
  support/settings)

(def origin
  "http://localhost")

(defn- request
  [method path & {:keys [headers cookies body]}]
  {:method method
   :path path
   :headers (or headers {})
   :cookies (or cookies {})
   :body (ByteArrayInputStream. (.getBytes (str body) StandardCharsets/UTF_8))})

(deftest state-changing-cookie-requests-must-come-from-the-ui
  (let [post
        (request :post "/api/command" :headers {"cookie" "oc_auth=t"})]
    (is
     (server/csrf-refused? origin post))
    (is
     (server/csrf-refused? origin (assoc-in post [:headers "origin"] "http://abc.localhost")))
    (is
     (server/csrf-refused? origin (assoc-in post [:headers "sec-fetch-site"] "same-site")))
    (is
     (not (server/csrf-refused? origin (assoc-in post [:headers "x-requested-with"] "XMLHttpRequest"))))
    (is
     (not (server/csrf-refused? origin (assoc-in post [:headers "sec-fetch-site"] "same-origin"))))
    (is
     (not (server/csrf-refused? origin (assoc-in post [:headers "origin"] origin))))
    (testing "reads, cookie-less callers and the sandbox listener are not affected"
      (is
       (not (server/csrf-refused? origin (assoc post :method :get))))
      (is
       (not (server/csrf-refused? origin (assoc post :headers {"authorization" "Bearer x"}))))
      (is
       (not (server/csrf-refused? nil post))))))

(defn- forwarded
  [path body]
  (let [sent
        (atom nil)

        token
        (auth/login-token settings (auth/login-identity settings "alice"))]
    (with-redefs [client/post-raw (fn [_ edd-path edd-token text]
                                    (reset! sent {:path edd-path :token edd-token :body (util/to-edn text)})
                                    {:status 200 :body "{\"result\":{\"ok\":true},\"invocation-id\":\"x\"}"})
                  effects/settle! (fn [])
                  workers/kick! (fn [])]
      (let [req
            (request :post path :cookies {"oc_auth" token} :body body)

            response
            ((:handler (server/route edge-api/routes req)) {:settings settings} req)]
        {:response response
         :sent @sent
         :token token}))))

(deftest only-the-query-or-commands-reach-edd
  (let [{:keys [sent token response]}
        (forwarded "/api/query" "{\"query\":{\"query-id\":\"get-me\"},\"apply\":{\"x\":1},\"request-id\":\"fixed\",\"meta\":{\"realm\":\"other\"}}")]
    (is
     (= ["/query" token]
        [(:path sent) (:token sent)]))
    (is
     (= #{:query :request-id :interaction-id}
        (set (keys (:body sent)))))
    (is
     (not= "fixed"
           (str (get-in sent [:body :request-id]))))
    (is
     (= {:ok true}
        (:result (util/to-edn (:body response))))))
  (let [{:keys [sent]}
        (forwarded "/api/command" "{\"commands\":[{\"cmd-id\":\"stop-session\"}],\"query\":{\"query-id\":\"x\"}}")]
    (is
     (= #{:commands :request-id :interaction-id}
        (set (keys (:body sent))))))
  (is
   (= 400
      (:status (:response (forwarded "/api/command" "{\"query\":{}}")))))
  (is
   (= 400
      (:status (:response (forwarded "/api/query" "not json"))))))

(deftest failures-keep-only-message-and-status
  (is
   (= {:status 404 :message "Session not found"}
      (client/failure {:exception {:status 404 :message "Session not found" :request {:secret 1}}})))
  (is
   (= {:status 500 :message "Internal error"}
      (client/failure {:exception "java.net.ConnectException: db.internal:5432"})))
  (is
   (= {:status 403 :message "Forbidden"}
      (client/failure {:exception {:message "Forbidden" :role :user :policy {:x 1}}})))
  (is
   (= {:status 400 :message "Invalid request"}
      (client/failure {:error {:attrs {:id ["missing"]}}})))
  (is
   (= {:status 400 :message "Invalid request"}
      (client/failure {:exception {:session-id ["should be a uuid"]}}))))

(deftest successful-bodies-pass-through-untouched
  (let [body
        "{\"result\":{\"payload\":{\"#tag\":\":not-a-keyword\"}}}"]
    (with-redefs [client/post-raw (fn [& _] {:status 200 :body body})]
      (is
       (= body
          (:body (edge-api/send! settings "/query" "t" {:query {:query-id :x}})))))
    (with-redefs [client/post-raw (fn [& _] {:status 200 :body "{\"exception\":{\"status\":409,\"message\":\"Busy\",\"cmd\":{}}}"})]
      (is
       (= {:exception {:status 409 :message "Busy"}}
          (util/to-edn (:body (edge-api/send! settings "/command" "t" {:commands []}))))))))

(deftest personas-only-in-login-mode
  (is
   (= 404
      (:status ((:handler (server/route edge-api/routes (request :get "/api/auth/personas")))
                {:settings (assoc settings :auth-mode "jwt")}
                (request :get "/api/auth/personas"))))))

(deftest login-refuses-the-platform-name
  (let [req
        (request :post "/api/auth/login" :body "{\"username\":\"platform\"}")]
    (is
     (= 422
        (:status ((:handler (server/route edge-api/routes req)) {:settings settings} req))))))

(deftest the-ui-gets-security-headers-and-no-source-maps
  (let [dir
        (Files/createTempDirectory "ui" (make-array FileAttribute 0))

        _
        (spit (.toFile (.resolve dir "index.html")) "<html></html>")

        _
        (spit (.toFile (.resolve dir "main.js.map")) "{}")

        system
        {:settings (assoc settings :ui-dist-dir (str dir))}

        index
        (static/handler system {:path "/"})

        source-map
        (static/handler system {:path "/main.js.map"})]
    (is
     (= static/default-csp
        (get-in index [:headers "Content-Security-Policy"])))
    (is
     (= "DENY"
        (get-in index [:headers "X-Frame-Options"])))
    (is
     (= "text/html; charset=utf-8"
        (get-in source-map [:headers "Content-Type"])))
    (is
     (nil?
      (get-in (static/handler (assoc-in system [:settings :content-security-policy] "off") {:path "/"})
              [:headers "Content-Security-Policy"])))))

(deftest bodies-above-the-limit-are-refused
  (let [server
        (server/start {} {:name :test
                          :routes [{:method :post :path "/echo" :handler (fn [_ req] {:status 200 :body (server/body-text req)})}
                                   {:method :post :path "/big" :max-body-bytes 4096 :handler (fn [_ req] {:status 200 :body (server/body-text req)})}]
                          :host "127.0.0.1"
                          :port 0
                          :max-body-bytes 1024})

        port
        (-> server .getListenerInfo first .getAddress .getPort)

        post
        (fn [path size]
          (let [connection
                (doto ^java.net.HttpURLConnection (.openConnection (java.net.URL. (str "http://127.0.0.1:" port path)))
                  (.setRequestMethod "POST")
                  (.setDoOutput true))]
            (with-open [out (.getOutputStream connection)]
              (.write out (byte-array size (byte 97))))
            (.getResponseCode connection)))]
    (try
      (is
       (= 200
          (post "/echo" 1000)))
      (is
       (= 413
          (post "/echo" 2000)))
      (is
       (= 200
          (post "/big" 2000)))
      (finally
        (server/stop server)))))
