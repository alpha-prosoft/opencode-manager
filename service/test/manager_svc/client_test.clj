(ns manager-svc.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.client :as client]
            [manager-svc.effects :as effects]
            [manager-svc.test-support :as support]
            [manager-svc.tokens :as tokens]))

(defn- answering
  [body]
  (fn [& _]
    {:status 200 :body body}))

(deftest a-handler-exception-is-a-failure
  (with-redefs [client/request-raw (answering "{\"exception\":{\"message\":\"No run is waiting for a result\",\"status\":409}}")
                effects/settle! (fn [])]
    (is
     (= {:status 409 :message "No run is waiting for a result"}
        (try
          (client/command support/settings {:cmd-id :submit-run-result})
          nil
          (catch clojure.lang.ExceptionInfo e
            (ex-data e)))))))

(deftest internal-errors-are-not-passed-on
  (with-redefs [client/request-raw (answering "{\"exception\":\"Connection refused: db.internal\"}")]
    (is
     (= {:status 500 :message "Internal error"}
        (try
          (client/query support/settings {:query-id :x})
          nil
          (catch clojure.lang.ExceptionInfo e
            (ex-data e)))))))

(deftest results-are-returned
  (with-redefs [client/request-raw (answering "{\"result\":{\"n\":1}}")]
    (is
     (= {:n 1}
        (client/query support/settings {:query-id :x})))))

(deftest only-command-waits-for-effects
  (let [settled
        (atom 0)]
    (with-redefs [client/request-raw (answering "{\"result\":{\"success\":true}}")
                  effects/settle! #(swap! settled inc)]
      (client/command-no-settle support/settings {:cmd-id :x})
      (testing "no-settle returns without waiting"
        (is
         (= 0
            @settled)))
      (client/command support/settings {:cmd-id :x})
      (is
       (= 1
          @settled)))))

(deftest the-platform-holds-one-service-token-and-renews-it-in-time
  (let [now
        (atom (tokens/now-seconds))

        issued
        (atom 0)

        issue
        tokens/service-token

        used
        (atom [])

        ask
        (fn [settings]
          (client/query settings {:query-id :x})
          (last @used))]
    (with-redefs [tokens/now-seconds (fn [] @now)
                  tokens/service-token (fn [settings] (swap! issued inc) (issue settings))
                  client/request-raw (fn [_settings _path token _body]
                                       (swap! used conj token)
                                       {:status 200 :body "{\"result\":{}}"})]
      (reset! @#'client/held-service-token nil)
      (let [token
            (ask support/settings)]
        (is
         (tokens/service-token? support/settings token))
        (testing "calls share it"
          (is
           (= [token 1]
              [(ask support/settings) @issued])))
        (testing "until half its life is over"
          (swap! now + (quot tokens/service-ttl-seconds 2) -1)
          (is
           (= 1
              (do (ask support/settings) @issued)))
          (swap! now + 1)
          (is
           (= 2
              (do (ask support/settings) @issued))))
        (testing "a manager with another signing key gets its own"
          (let [other
                (assoc support/settings :secret-key "another-key")]
            (is
             (= [true 3]
                [(tokens/service-token? other (ask other)) @issued]))))))))
