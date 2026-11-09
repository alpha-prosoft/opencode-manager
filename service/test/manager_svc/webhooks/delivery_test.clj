(ns manager-svc.webhooks.delivery-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [edd.test.fixture.dal :as mock]
            [manager-svc.test-support :as support]
            [manager-svc.webhooks.delivery :as delivery]
            [manager-svc.webhooks.fixtures :as fixtures :refer [config save! with-platform]]
            [manager-svc.webhooks.limiter :as limiter]
            [manager-svc.webhooks.secrets :as secrets]
            [manager-svc.webhooks.verify :as verify]
            [manager-svc.webhooks.workflows :as workflows])
  (:import [java.nio.charset StandardCharsets]))

(def hook-id
  #uuid "9d0c7e7a-1f7b-4d55-a2a3-3b0d0d6f8a11")

(def now-ms
  1767225600000)

(def test-token
  "the-process-test-token")

(defn- ports
  [started]
  {:lookup (fn [tenant webhook-id]
             (:webhook (mock/query (fixtures/platform-ctx) {:query-id :get-webhook-for-delivery
                                                            :tenant tenant
                                                            :webhook-id webhook-id})))
   :open (fn [{:keys [tenant id sealed]}]
           (when sealed (secrets/open fixtures/settings tenant id sealed)))
   :evaluate (fn [webhook request]
               (workflows/evaluate nil (:tenant webhook) webhook request))
   :start-run! (fn [run-id attrs]
                 (swap! started conj (assoc attrs :id run-id)))
   :record! (fn [webhook-id record]
              (support/apply! (fixtures/platform-ctx) {:cmd-id :record-webhook-delivery :id webhook-id
                                                       :attrs {:delivery record}}))
   :limits (limiter/limits)
   :test-token test-token
   :now-ms (constantly now-ms)})

(defn- request
  [& {:keys [tenant headers body method query source-ip]}]
  {:tenant (or tenant "acme")
   :webhook-id hook-id
   :method (or method :post)
   :headers (merge {"content-type" "application/json"} headers)
   :query (or query {})
   :body (.getBytes (str body) StandardCharsets/UTF_8)
   :source-ip (or source-ip "203.0.113.7")})

(def issue
  "{\"issue\": {\"summary\": \"Checkout times out under load\"}}")

(defn- header-value
  [delivery header-name]
  (some #(when (= header-name (:name %)) (:value %)) (:headers delivery)))

(defn- deliveries
  []
  (:deliveries (mock/query (fixtures/as "dave") {:query-id :get-webhook :webhook-id hook-id})))

(deftest a-valid-request-starts-the-target-workflow-as-the-platform
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config))
      (let [started
            (atom [])

            {:keys [status body]}
            (delivery/handle (ports started) (request :headers {"x-hook-token" "s3cret-token-1"} :body issue))

            [run]
            @started]
        (is
         (= 202
            status))
        (is
         (= (str (:id run))
            (:run-id body)))
        (is
         (= {:tenant "acme"
             :team-id "alpha"
             :workflow-id "analyse"
             :inputs {"brief" "Checkout times out under load"}
             :created-by "platform:webhook"}
            (select-keys run [:tenant :team-id :workflow-id :inputs :created-by])))
        (is
         (= {:type "webhook" :webhook-id (str hook-id) :webhook-name "Ticket intake" :delivery-id (:delivery-id body)}
            (:trigger run)))
        (testing "the delivery is logged with the secret header redacted"
          (let [[logged]
                (deliveries)]
            (is
             (= [202 (:run-id body) "203.0.113.7" "[redacted]" "application/json"]
                [(:status logged) (:run-id logged) (:source-ip logged)
                 (header-value logged "x-hook-token") (header-value logged "content-type")]))
            (is
             (= issue
                (:body logged)))))))))

(deftest refusals
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config {:allowed-ips ["203.0.113.0/24"]}))
      (let [started
            (atom [])

            send
            (fn [& args]
              (delivery/handle (ports started) (apply request args)))]
        (testing "a wrong or missing secret is 401 without saying why; the log says"
          (is
           (= {:status 401 :body {:detail "Unauthorized"}}
              (send :headers {"x-hook-token" "wrong"} :body issue)))
          (is
           (= [401 "Header X-Hook-Token is missing or wrong"]
              ((juxt :status :error) (first (deliveries))))))
        (testing "a source outside the allowed addresses is 403"
          (is
           (= 403
              (:status (send :headers {"x-hook-token" "s3cret-token-1"} :body issue :source-ip "198.51.100.1")))))
        (testing "inputs failing the workflow's schema are 422 with field errors"
          (is
           (= {:status 422
               :body {:detail "The request doesn't map to valid inputs of the workflow"
                      :errors [{:field "brief" :message "should be at least 10 characters"}]}}
              (send :headers {"x-hook-token" "s3cret-token-1"} :body "{\"issue\": {\"summary\": \"short\"}}"))))
        (testing "GET is refused unless the webhook allows it"
          (is
           (= 405
              (:status (send :method :get :headers {"x-hook-token" "s3cret-token-1"})))))
        (testing "an unknown webhook, another tenant's path and a disabled webhook are all 404"
          (is
           (= delivery/not-found
              (delivery/handle (ports started) (assoc (request) :webhook-id (random-uuid)))))
          (is
           (= delivery/not-found
              (send :tenant "globex" :headers {"x-hook-token" "s3cret-token-1"} :body issue)))
          (save! "dave" hook-id (config {:enabled false}))
          (is
           (= delivery/not-found
              (send :headers {"x-hook-token" "s3cret-token-1"} :body issue))))
        (is
         (= []
            @started))))))

(deftest rate-limited-per-webhook
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config {:auth {:type "none"} :rate-limit-per-minute 2}))
      (let [started
            (atom [])

            same-ports
            (ports started)

            statuses
            (mapv (fn [_] (:status (delivery/handle same-ports (request :body issue)))) (range 3))]
        (is
         (= [202 202 429]
            statuses))
        (is
         (= 2
            (count (deliveries))))))))

(deftest deliveries-keep-the-last-fifty
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config {:auth {:type "none"} :rate-limit-per-minute 600}))
      (let [same-ports
            (ports (atom []))]
        (dotimes [_ 52]
          (delivery/handle same-ports (request :body issue)))
        (is
         (= 50
            (count (deliveries))))))))

(deftest delivery-records-redact-and-truncate
  (let [webhook
        {:auth {:type :hmac :header-name "X-Hub-Signature-256"}}

        record
        (delivery/delivery-record webhook
                                  {:method :post
                                   :headers {"authorization" "Bearer abc" "x-hub-signature-256" "sha256=00"
                                             "cookie" "a=b" "x-event" "push"}
                                   :query {:ref "main"}
                                   :body-text (apply str (repeat 5000 "x"))
                                   :source-ip "10.0.0.1"}
                                  {:status 401 :error "Signature in X-Hub-Signature-256 is missing or wrong"}
                                  (random-uuid)
                                  "2026-01-01T00:00:00Z")]
    (is
     (= [{:name "authorization" :value "[redacted]"}
         {:name "cookie" :value "[redacted]"}
         {:name "x-event" :value "push"}
         {:name "x-hub-signature-256" :value "[redacted]"}]
        (:headers record)))
    (is
     (= [4096 true [{:name "ref" :value "main"}]]
        [(count (:body record)) (:body-truncated record) (:query record)]))
    (is
     (not (string/includes? (pr-str record) "abc")))))

(deftest hmac-end-to-end-through-the-handler
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config {:auth {:type "hmac" :header-name "X-Gitea-Signature" :secret "hmac-secret-99"
                                            :timestamp-header "X-Timestamp" :tolerance-seconds 300}}))
      (let [started
            (atom [])

            body
            (.getBytes issue StandardCharsets/UTF_8)

            signed
            (verify/sign-headers {:type :hmac :header-name "x-gitea-signature" :timestamp-header "x-timestamp"}
                                 {:secret "hmac-secret-99"} body (quot now-ms 1000))

            lowered
            (into {} (map (fn [[k v]] [(string/lower-case k) v])) signed)]
        (is
         (= 202
            (:status (delivery/handle (ports started) (request :headers lowered :body issue)))))
        (testing "a replay outside the window is refused"
          (is
           (= 401
              (:status (delivery/handle (assoc (ports started) :now-ms (constantly (+ now-ms 301000)))
                                        (request :headers lowered :body issue))))))))))

(def good
  {"x-hook-token" "s3cret-token-1"})

(deftest refused-callers-never-use-up-what-the-real-caller-may-send
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config {:rate-limit-per-minute 2}))
      (let [started
            (atom [])

            same-ports
            (ports started)

            send
            (fn [& args]
              (:status (delivery/handle same-ports (apply request args))))]
        (testing "someone without the secret is limited on their own address, and only the refusals are logged"
          (is
           (= [401 401 429 429]
              (mapv (fn [_] (send :headers {"x-hook-token" "guess"} :body issue :source-ip "198.51.100.66")) (range 4))))
          (is
           (= [401 401]
              (mapv :status (deliveries)))))
        (testing "another address has its own allowance"
          (is
           (= 401
              (send :headers {"x-hook-token" "guess"} :body issue :source-ip "198.51.100.67"))))
        (testing "the real caller still has the webhook's whole allowance"
          (is
           (= [202 202 429]
              (mapv (fn [_] (send :headers good :body issue)) (range 3))))
          (is
           (= 2
              (count @started))))))))

(deftest a-rate-limited-answer-says-when-to-come-back
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config {:auth {:type "none"} :rate-limit-per-minute 1}))
      (let [same-ports
            (ports (atom []))]
        (delivery/handle same-ports (request :body issue))
        (is
         (= {:status 429 :body {:detail "Too many requests"} :headers {"Retry-After" "60"}}
            (delivery/handle same-ports (request :body issue))))))))

(deftest only-the-managers-own-test-call-is-a-test
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config {:allowed-ips ["203.0.113.0/24"]}))
      (let [started
            (atom [])

            send
            (fn [headers]
              (:status (delivery/handle (ports started)
                                        (request :headers (merge good headers) :body issue :source-ip "127.0.0.1"))))]
        (testing "with the process's token it skips the source address check and is marked"
          (is
           (= 202
              (send {delivery/test-header test-token})))
          (is
           (= [true "[redacted]"]
              ((juxt :test #(header-value % delivery/test-header)) (first (deliveries))))))
        (testing "a caller claiming to be a test is checked like anyone and not marked"
          (is
           (= [403 403]
              [(send {delivery/test-header "1"}) (send {})]))
          (is
           (= [false false]
              (mapv :test (take 2 (deliveries))))))
        (testing "a test call still needs the secret"
          (is
           (= 401
              (:status (delivery/handle (ports started)
                                        (request :headers {delivery/test-header test-token "x-hook-token" "wrong"}
                                                 :body issue :source-ip "127.0.0.1"))))))))))

(deftest what-goes-wrong-inside-is-a-recorded-500-without-details
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config))
      (let [started
            (atom [])

            broken
            (fn [port]
              (assoc (ports started) port (fn [& _] (throw (ex-info "The secret was not sealed for this webhook" {:status 422})))))]
        (is
         (= {:status 500 :body {:detail "The request could not be processed"}}
            (delivery/handle (broken :open) (request :headers good :body issue))))
        (is
         (= {:status 500 :body {:detail "The request could not be processed"}}
            (delivery/handle (broken :evaluate) (request :headers good :body issue))))
        (is
         (= [[500 "The secret was not sealed for this webhook"] [500 "The secret was not sealed for this webhook"]]
            (mapv (juxt :status :error) (deliveries))))
        (is
         (= []
            @started))))))

(deftest a-body-that-cannot-be-read-as-its-content-type-is-422
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config {:auth {:type "none"}}))
      (is
       (= 422
          (:status (delivery/handle (ports (atom []))
                                    (request :headers {"content-type" "application/x-www-form-urlencoded"}
                                             :body "issue=%zz"))))))))

(deftest the-body-is-read-only-for-a-known-webhook
  (mock/with-mock-dal
    (with-platform
      (save! "dave" hook-id (config {:auth {:type "none"}}))
      (let [body-of
            (fn []
              (delay (.getBytes ^String issue StandardCharsets/UTF_8)))

            unknown
            (body-of)

            known
            (body-of)]
        (is
         (= [404 false]
            [(:status (delivery/handle (ports (atom [])) (assoc (request) :webhook-id (random-uuid) :body unknown)))
             (realized? unknown)]))
        (is
         (= [202 true]
            [(:status (delivery/handle (ports (atom [])) (assoc (request) :body known)))
             (realized? known)]))))))

(deftest buckets-nobody-uses-are-forgotten
  (let [buckets
        (limiter/limits)]
    (with-redefs [limiter/max-buckets 3]
      (doseq [source ["a" "b" "c"]]
        (limiter/take! buckets [hook-id source] 60 now-ms))
      (limiter/take! buckets [hook-id "d"] 60 (+ now-ms limiter/refill-ms))
      (is
       (= [[hook-id "d"]]
          (keys @buckets))))))
