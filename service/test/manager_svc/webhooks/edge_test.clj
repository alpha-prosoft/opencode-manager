(ns manager-svc.webhooks.edge-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [lambda.util :as util]
            [manager-svc.auth :as auth]
            [manager-svc.client :as client]
            [manager-svc.edge.server :as server]
            [manager-svc.http :as http]
            [manager-svc.webhooks.delivery :as delivery]
            [manager-svc.webhooks.edge :as edge]
            [manager-svc.webhooks.fixtures :as fixtures]
            [manager-svc.webhooks.secrets :as secrets]
            [manager-svc.webhooks.verify :as verify]
            [manager-svc.workers :as workers])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(def hook-id
  #uuid "3c0f2b9e-6a8d-4b51-9e0c-7d1a2f3e4b5c")

(deftest paths
  (is
   (= {:tenant "acme" :webhook-id hook-id}
      (edge/hook-path (str "/hooks/acme/" hook-id))))
  (is
   (nil? (edge/hook-path "/hooks/acme/not-a-uuid")))
  (is
   (nil? (edge/hook-path (str "/hooks/Acme!/" hook-id))))
  (is
   (= [{:webhook-id hook-id :action nil} {:webhook-id hook-id :action :rotate} {:webhook-id hook-id :action :test}]
      (mapv edge/api-path [(str "/api/webhooks/" hook-id)
                           (str "/api/webhooks/" hook-id "/rotate")
                           (str "/api/webhooks/" hook-id "/test")])))
  (is
   (nil? (edge/api-path (str "/api/webhooks/" hook-id "/other")))))

(deftest the-edge-seals-the-secret-before-edd-sees-it
  (let [command
        (edge/save-command fixtures/settings {:sub "dave" :tenant "acme"} hook-id
                           {:name "x" :auth {:type "basic" :username "ci" :secret "pw-9876543"}})]
    (is
     (not (string/includes? (pr-str command) "pw-9876543")))
    (is
     (= {:type "basic" :username "ci"}
        (get-in command [:attrs :auth])))
    (is
     (= {:secret "pw-9876543" :username "ci"}
        (secrets/open fixtures/settings "acme" hook-id (get-in command [:attrs :sealed]))))
    (testing "without a secret the stored one stays"
      (is
       (nil? (get-in (edge/save-command fixtures/settings {:sub "dave" :tenant "acme"} hook-id
                                        {:name "x" :auth {:type "basic" :username "ci"}})
                     [:attrs :sealed]))))))

(deftest the-test-invocation-is-signed-like-a-real-caller
  (let [webhook
        {:id hook-id :tenant "acme" :auth {:type :hmac :header-name "X-Hub-Signature-256"}}

        sent
        (edge/test-request (assoc fixtures/settings :public-port-internal 18700) webhook {:secret "hmac-secret-1"}
                           {:body "{\"a\":1}"
                            :headers {"X-Event" "push"
                                      "Host" "evil"
                                      "X-Forwarded-For" "203.0.113.9"
                                      "x-hub-signature-256" "sha256=00"
                                      "X-Manager-Webhook-Test" "guess"}
                            :query {:ref "main"}}
                           "the-test-token"
                           1767225600)

        lowered
        (:headers sent)]
    (is
     (= (str "http://127.0.0.1:18700/hooks/acme/" hook-id "?ref=main")
        (:url sent)))
    (is
     (= ["push" "the-test-token" nil nil]
        [(get lowered "x-event") (get lowered "x-manager-webhook-test") (get lowered "host")
         (get lowered "x-forwarded-for")]))
    (is
     (= (sort (keys lowered))
        (sort (distinct (map string/lower-case (keys lowered)))))
     "one header per name, so the signature auth adds replaces a sample's")
    (is
     (:ok (verify/check (:auth webhook) {:secret "hmac-secret-1"}
                        {:headers lowered :body (.getBytes ^String (:body sent) StandardCharsets/UTF_8)}
                        1767225600)))))

(defn- token-of
  [username]
  (auth/login-token fixtures/settings
                    (auth/identity-of fixtures/settings (merge {:sub username :tenant "acme"} (get fixtures/people username)))))

(defn- request
  [method path & {:keys [body username headers remote-addr]}]
  {:method method
   :path path
   :query {}
   :headers (or headers {})
   :remote-addr remote-addr
   :cookies (if username {(auth/login-cookie fixtures/settings) (token-of username)} {})
   :body (ByteArrayInputStream. (.getBytes (str body) StandardCharsets/UTF_8))})

(defn- call
  [system request]
  (try
    ((:handler (server/route edge/routes request)) system request)
    (catch clojure.lang.ExceptionInfo e
      (select-keys (ex-data e) [:status :message]))))

(def system
  {:settings fixtures/settings
   :webhooks/test-token "the-test-token"})

(defmacro ^:private with-edd
  "Runs body with edd answering every command with answer (a JSON text);
   sent collects the request bodies."
  [sent answer & body]
  `(with-redefs [client/post-raw (fn [_# path# _# text#]
                                   (swap! ~sent conj [path# text#])
                                   {:status 200 :body ~answer})
                 workers/kick! (fn [])]
     ~@body))

(deftest saving-needs-a-login-and-a-json-object
  (let [sent
        (atom [])

        path
        (str "/api/webhooks/" hook-id)]
    (with-edd sent "{\"result\":{\"success\":true}}"
      (is
       (= {:status 401 :message "Not authenticated"}
          (call system (request :put path :body "{}"))))
      (is
       (= [422 422]
          [(:status (call system (request :put path :username "dave" :body "[]")))
           (:status (call system (request :put path :username "dave" :body "{\"name\": ")))]))
      (is
       (= []
          @sent))
      (testing "a saved webhook answers edd's response, its secret sent sealed"
        (is
         (= [200 "{\"result\":{\"success\":true}}"]
            ((juxt :status :body)
             (call system (request :put path :username "dave"
                                   :body "{\"name\":\"x\",\"auth\":{\"type\":\"bearer\",\"secret\":\"plain-bearer-123\"}}")))))
        (is
         (= ["/command" false]
            [(ffirst @sent) (string/includes? (second (first @sent)) "plain-bearer-123")]))))))

(deftest a-refused-command-answers-its-status-on-every-webhook-route
  (let [sent
        (atom [])

        refused
        "{\"exception\":{\"message\":\"Only admins of team alpha manage its webhooks\",\"status\":403,\"trace\":\"internal\"}}"]
    (with-edd sent refused
      (is
       (= [{:status 403 :message "Only admins of team alpha manage its webhooks"}
           {:status 403 :message "Only admins of team alpha manage its webhooks"}]
          [(call system (request :put (str "/api/webhooks/" hook-id) :username "alice" :body "{\"name\":\"x\"}"))
           (call system (request :post (str "/api/webhooks/" hook-id "/rotate") :username "alice" :body "{}"))])))))

(deftest rotating-answers-the-new-secret-once-it-is-stored
  (let [sent
        (atom [])]
    (with-edd sent "{\"result\":{\"success\":true}}"
      (let [response
            (call system (request :post (str "/api/webhooks/" hook-id "/rotate") :username "dave" :body "{}"))

            secret
            (:secret (util/to-edn (:body response)))

            [command]
            (:commands (util/to-edn (second (first @sent))))]
        (is
         (= [200 "no-store"]
            [(:status response) (get-in response [:headers "Cache-Control"])]))
        (is
         (= 43
            (count secret)))
        (is
         (not (string/includes? (second (first @sent)) secret)))
        (is
         (= {:secret secret}
            (secrets/open fixtures/settings "acme" hook-id (get-in command [:attrs :sealed]))))))))

(deftest only-those-who-manage-a-webhook-send-tests
  (let [called
        (atom [])]
    (with-redefs [client/query-as (fn [_ _ _] {:id hook-id :can-manage false})
                  http/request! (fn [sent] (swap! called conj sent) {:status 202 :body "{}"})]
      (is
       (= {:status 403 :message "Only admins of the webhook's scope can do this"}
          (call system (request :post (str "/api/webhooks/" hook-id "/test") :username "alice" :body "{}"))))
      (is
       (= {:status 401 :message "Not authenticated"}
          (call system (request :post (str "/api/webhooks/" hook-id "/test") :body "{}"))))
      (is
       (= []
          @called)))))

(deftest a-test-is-sent-to-the-public-endpoint-with-the-process-token
  (let [called
        (atom [])

        webhook
        {:id hook-id
         :tenant "acme"
         :auth {:type :header :header-name "X-Hook-Token"}
         :sealed (secrets/seal fixtures/settings "acme" hook-id {:secret "s3cret-token-1"})}]
    (with-redefs [client/query-as (fn [_ _ _] {:id hook-id :can-manage true})
                  client/query (fn [_ _] {:webhook webhook})
                  http/request! (fn [sent] (swap! called conj sent) {:status 202 :body "{\"run-id\":\"r1\"}"})]
      (let [response
            (util/to-edn (:body (call system (request :post (str "/api/webhooks/" hook-id "/test") :username "dave"
                                                      :body "{\"body\":\"{}\",\"headers\":{\"X-Event\":\"push\"}}"))))]
        (is
         (= ["s3cret-token-1" "the-test-token" "push"]
            (mapv #(get-in (first @called) [:headers %]) ["x-hook-token" "x-manager-webhook-test" "x-event"])))
        (is
         (= {:status 202 :body {:run-id "r1"}}
            (select-keys response [:status :body])))
        (testing "what it shows of the request hides the secret and the token"
          (is
           (= ["[redacted]" "[redacted]" "push"]
              (mapv (fn [header-name]
                      (some #(when (= header-name (:name %)) (:value %)) (get-in response [:request :headers])))
                    ["x-hook-token" "x-manager-webhook-test" "x-event"]))))))))

(defn- delivered
  "The request the delivery gets for an edge request to the hook."
  [system edge-request]
  (let [seen
        (atom nil)]
    (with-redefs [delivery/handle (fn [_ports request]
                                    (reset! seen request)
                                    {:status 202 :body {:run-id "r1"}})]
      (assoc (call system edge-request) :delivered @seen))))

(deftest the-hook-takes-the-callers-address-from-the-connection
  (let [path
        (str "/hooks/acme/" hook-id)

        from
        (fn [system peer forwarded]
          (get-in (delivered system (request :post path :remote-addr peer :headers {"x-forwarded-for" forwarded}))
                  [:delivered :source-ip]))]
    (testing "without trusted proxies X-Forwarded-For is never believed"
      (is
       (= ["172.18.0.4" "127.0.0.1"]
          [(from system "172.18.0.4" "203.0.113.9") (from system "127.0.0.1" "203.0.113.9")])))
    (testing "a trusted proxy's last address is the caller"
      (let [behind-proxy
            (assoc-in system [:settings :trusted-proxies] [" 172.18.0.0/16 "])]
        (is
         (= ["203.0.113.9" "198.51.100.7"]
            [(from behind-proxy "172.18.0.4" "10.9.9.9, 203.0.113.9")
             (from behind-proxy "198.51.100.7" "203.0.113.9")]))))
    (testing "a proxy named by host is trusted at its current addresses"
      (let [named
            (assoc (assoc-in system [:settings :trusted-proxies] ["localhost"])
                   :webhooks/proxy-lookups (atom {}))]
        (is
         (= "203.0.113.9"
            (from named "127.0.0.1" "203.0.113.9")))
        (is
         (= ["localhost"]
            (keys @(:webhooks/proxy-lookups named))))))
    (testing "a name that doesn't resolve trusts nobody"
      (is
       (= []
          (edge/trusted-proxies (assoc-in system [:settings :trusted-proxies] ["no-such-proxy.invalid"]) 0))))))

(deftest the-hook-answers-machines
  (let [path
        (str "/hooks/acme/" hook-id)]
    (is
     (= 405
        (:status (call system (request :delete path)))))
    (let [{:keys [status headers delivered]}
          (delivered system (request :post path :remote-addr "198.51.100.7" :body "{\"a\":1}"))]
      (is
       (= [202 "no-store"]
          [status (get headers "Cache-Control")]))
      (is
       (= {:tenant "acme" :webhook-id hook-id :method :post}
          (select-keys delivered [:tenant :webhook-id :method])))
      (is
       (= "{\"a\":1}"
          (String. ^bytes (force (:body delivered)) StandardCharsets/UTF_8))))
    (testing "a GET has no body to read"
      (is
       (nil? (get-in (delivered system (request :get path :remote-addr "198.51.100.7")) [:delivered :body]))))))
