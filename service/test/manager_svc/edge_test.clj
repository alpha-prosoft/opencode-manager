(ns manager-svc.edge-test
  (:require [clojure.test :refer [deftest is testing]]
            [lambda.util :as util]
            [edd.undertow.core :as undertow]
            [manager-svc.client :as client]
            [manager-svc.edge.api :as edge-api]
            [manager-svc.edge.http :as edge-http]
            [manager-svc.edge.listener :as listener]
            [manager-svc.edge.login :as login]
            [manager-svc.edge.static :as static]
            [manager-svc.effects :as effects]
            [manager-svc.signin :as signin]
            [manager-svc.test-support :as support]
            [manager-svc.tokens :as tokens]
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
     (listener/csrf-refused? origin post))
    (is
     (listener/csrf-refused? origin (assoc-in post [:headers "origin"] "http://abc.localhost")))
    (is
     (listener/csrf-refused? origin (assoc-in post [:headers "sec-fetch-site"] "same-site")))
    (is
     (not (listener/csrf-refused? origin (assoc-in post [:headers "x-requested-with"] "XMLHttpRequest"))))
    (is
     (not (listener/csrf-refused? origin (assoc-in post [:headers "sec-fetch-site"] "same-origin"))))
    (is
     (not (listener/csrf-refused? origin (assoc-in post [:headers "origin"] origin))))
    (testing "reads, cookie-less callers and the sandbox listener are not affected"
      (is
       (not (listener/csrf-refused? origin (assoc post :method :get))))
      (is
       (not (listener/csrf-refused? origin (assoc post :headers {"authorization" "Bearer x"}))))
      (is
       (not (listener/csrf-refused? nil post))))))

(defn- forwarded
  [path body]
  (let [sent
        (atom nil)

        token
        (:token (signin/test-login settings "alice"))]
    (with-redefs [client/request-raw (fn [_settings edd-path edd-token text]
                                       (reset! sent {:path edd-path :token edd-token :body (util/to-edn text)})
                                       {:status 200 :body "{\"result\":{\"ok\":true},\"invocation-id\":\"x\"}"})
                  effects/settle! (fn [])
                  workers/kick! (fn [])]
      {:response (listener/answer {:settings settings}
                                  {:routes edge-api/routes}
                                  (request :post path :cookies {"oc_auth" token} :body body))
       :sent @sent
       :token token})))

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
    (with-redefs [client/request-raw (fn [& _] {:status 200 :body body})]
      (is
       (= body
          (:body (edge-api/send! settings "/query" "t" {:query {:query-id :x}})))))
    (with-redefs [client/request-raw (fn [& _] {:status 200 :body "{\"exception\":{\"status\":409,\"message\":\"Busy\",\"cmd\":{}}}"})]
      (is
       (= {:exception {:status 409 :message "Busy"}}
          (util/to-edn (:body (edge-api/send! settings "/command" "t" {:commands []}))))))))

(deftest personas-only-in-login-mode
  (is
   (= 404
      (:status (listener/answer {:settings (assoc settings :auth-mode "jwt")}
                                {:routes login/routes}
                                (request :get "/api/auth/personas"))))))

(deftest login-refuses-the-platform-name
  (is
   (= 422
      (:status (listener/answer {:settings settings}
                                {:routes login/routes}
                                (request :post "/api/auth/login" :body "{\"username\":\"platform\"}"))))))

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

(defn- status-of
  [f]
  (try
    (:status (f))
    (catch clojure.lang.ExceptionInfo e
      (:status (ex-data e)))))

(defn- asking
  "[ask seen]: ask answers the status of a request to an open route and one
   for a person, seen holds the request the handler got."
  [settings]
  (let [seen
        (atom nil)

        routes
        [{:method :get :path "/open" :handler (fn [_ req] (reset! seen req) {:status 200})}
         {:method :get :path "/person" :caller :person :handler (fn [_ req] (reset! seen req) {:status 200})}]]
    [(fn [path & {:as carried}]
       (reset! seen nil)
       (status-of #(listener/answer {:settings settings} {:routes routes} (request :get path carried))))
     seen]))

(deftest a-route-says-who-must-be-logged-in
  (let [[ask seen]
        (asking settings)

        token
        (:token (signin/test-login settings "alice"))]
    (is
     (= [200 401]
        (mapv ask ["/open" "/person"])))
    (is
     (= 401
        (ask "/person" :cookies {"oc_auth" "forged"})))
    (testing "a route that asks for nobody learns nothing about the caller"
      (ask "/open" :cookies {"oc_auth" token})
      (is
       (= [nil nil]
          [(:token @seen) (:identity @seen)])))
    (testing "the handler gets the person and their platform token"
      (ask "/person" :cookies {"oc_auth" token})
      (is
       (= [token "alice"]
          [(:token @seen) (get-in @seen [:identity :sub])])))
    (testing "only a platform token logs in: not the platform's own, a session host's or a handoff"
      (let [alice
            (tokens/person settings token)]
        (is
         (= [401 401 401]
            (mapv #(ask "/person" :cookies {"oc_auth" %})
                  [(tokens/service-token settings)
                   (tokens/session-token settings alice "s1" 4102444800)
                   (tokens/handoff-token settings alice "s1")])))))))

(deftest a-provider-token-is-exchanged-at-the-door
  (let [jwt-settings
        (assoc settings :auth-mode "jwt" :jwt-secret "idp-secret" :jwt-issuer "https://idp.example" :jwt-audience "manager")

        [ask seen]
        (asking jwt-settings)

        provider-token
        (support/provider-token "idp-secret" {"sub" "alice" "tenant" "acme" "groups" ["/acme/alpha"]})

        refused
        (support/provider-token "idp-secret" {"sub" "erin" "groups" ["/acme/a" "/globex/b"]})]
    (testing "the handler gets a platform token of the person, never the provider's"
      (is
       (= 200
          (ask "/person" :headers {"authorization" (str "Bearer " provider-token)})))
      (is
       (not= provider-token
             (:token @seen)))
      (is
       (= {:sub "alice" :tenant "acme" :teams {"alpha" :member}}
          (select-keys (:identity @seen) [:sub :tenant :teams])))
      (is
       (= (:identity @seen)
          (tokens/person jwt-settings (:token @seen)))))
    (testing "the provider's cookie counts like a bearer token"
      (is
       (= 200
          (ask "/person" :cookies {"access_token" provider-token}))))
    (testing "someone the claims parser refuses is told why"
      (is
       (= [403 "Login refused: your account belongs to no tenant"]
          (try
            (listener/answer {:settings jwt-settings}
                             {:routes edge-api/routes}
                             (request :post "/api/query" :headers {"authorization" (str "Bearer " refused)}))
            (catch clojure.lang.ExceptionInfo e
              ((juxt :status :message) (ex-data e)))))))
    (testing "a token of another issuer is nobody"
      (is
       (= 401
          (ask "/person" :headers {"authorization" (str "Bearer " (support/provider-token "other-secret" {"sub" "alice" "tenant" "acme"}))}))))
    (testing "outside jwt mode a provider token is nobody"
      (is
       (= 401
          ((first (asking (assoc jwt-settings :auth-mode "login")))
           "/person" :headers {"authorization" (str "Bearer " provider-token)}))))))

(deftest the-origin-is-checked-before-anything-is-served
  (let [served
        (atom 0)

        public
        {:origin origin
         :host-handlers [(fn [_ _] (swap! served inc) nil)]
         :routes [{:method :post :path "/api/command" :handler (fn [_ _] (swap! served inc) {:status 200})}]}

        post
        (request :post "/api/command" :headers {"cookie" "oc_auth=t"})]
    (is
     (= 403
        (:status (listener/answer {:settings settings} public post))))
    (is
     (= 0
        @served))
    (is
     (= 200
        (:status (listener/answer {:settings settings} public (assoc-in post [:headers "x-requested-with"] "manager-web")))))))

(deftest failures-are-answered-without-internal-text
  (is
   (= [409 {:detail "Busy"}]
      ((juxt :status (comp util/to-edn :body))
       (listener/refusal {:path "/x"} (ex-info "Busy" {:status 409 :message "Busy"})))))
  (is
   (= [500 {:detail "Internal error"}]
      ((juxt :status (comp util/to-edn :body))
       (listener/refusal {:path "/x"} (ex-info "connection to db.internal:5432 refused" {}))))))

(defn- with-listener
  "Calls f with the base URL of a real listener serving routes."
  [routes f]
  (let [runtime
        (undertow/start {:service-name :edge-test-svc
                         :hosted-zone-name "example.com"
                         :environment-name-lower "local"}
                        (fn [_ctx _request] {})
                        :consume []
                        :listeners {:default false
                                    :test (merge (listener/limits {:max-body-bytes 1024})
                                                 {:host "127.0.0.1"
                                                  :port 0
                                                  :handler (listener/handler {:settings settings} {:routes routes})})})]
    (try
      (f (str "http://127.0.0.1:" (undertow/port runtime :test)))
      (finally
        (undertow/stop runtime)))))

(defn- post-bytes
  [url size]
  (let [connection
        (doto ^java.net.HttpURLConnection (.openConnection (java.net.URL. url))
          (.setRequestMethod "POST")
          (.setDoOutput true))]
    (with-open [out (.getOutputStream connection)]
      (.write out (byte-array size (byte 97))))
    (.getResponseCode connection)))

(deftest bodies-above-the-limit-are-refused
  (let [echo
        (fn [_ req] {:status 200 :body (edge-http/body-text req)})]
    (with-listener [{:method :post :path "/echo" :handler echo}
                    {:method :post :path "/big" :max-body-bytes 4096 :handler echo}]
      (fn [url]
        (is
         (= 200
            (post-bytes (str url "/echo") 1000)))
        (is
         (= 413
            (post-bytes (str url "/echo") 2000)))
        (is
         (= 200
            (post-bytes (str url "/big") 2000)))))))

(deftest a-listener-streams-and-answers-refusals
  (with-listener [{:method :get
                   :path "/stream"
                   :handler (fn [_ _]
                              {:status 200
                               :headers {"Set-Cookie" ["a=1" "b=2"]}
                               :body (fn [^java.io.OutputStream out]
                                       (doseq [part ["one " "two"]]
                                         (.write out (.getBytes ^String part StandardCharsets/UTF_8))
                                         (.flush out)))})}
                  {:method :get :path "/refused" :handler (fn [_ _] (throw (ex-info "No" {:status 409 :message "No"})))}
                  {:method :get :path "/broken" :handler (fn [_ _] (throw (ex-info "db.internal:5432" {})))}]
    (fn [url]
      (let [fetch
            (fn [path]
              (let [connection
                    ^java.net.HttpURLConnection (.openConnection (java.net.URL. (str url path)))

                    status
                    (.getResponseCode connection)]
                {:status status
                 :cookies (vec (get (.getHeaderFields connection) "Set-Cookie"))
                 :nosniff (.getHeaderField connection "X-Content-Type-Options")
                 :body (slurp (if (< status 400) (.getInputStream connection) (.getErrorStream connection)))}))]
        (is
         (= {:status 200 :cookies ["a=1" "b=2"] :nosniff "nosniff" :body "one two"}
            (update (fetch "/stream") :cookies (comp vec sort))))
        (is
         (= [409 "{\"detail\":\"No\"}"]
            ((juxt :status :body) (fetch "/refused"))))
        (is
         (= [500 "{\"detail\":\"Internal error\"}"]
            ((juxt :status :body) (fetch "/broken"))))
        (is
         (= 404
            (:status (fetch "/nothing"))))))))
