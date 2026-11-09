(ns manager-svc.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.client :as client]
            [manager-svc.effects :as effects]
            [manager-svc.test-support :as support]))

(defn- answering
  [body]
  (fn [& _]
    {:status 200 :body body}))

(deftest a-handler-exception-is-a-failure
  (with-redefs [client/post-raw (answering "{\"exception\":{\"message\":\"No run is waiting for a result\",\"status\":409}}")
                effects/settle! (fn [])]
    (is
     (= {:status 409 :message "No run is waiting for a result"}
        (try
          (client/command support/settings {:cmd-id :submit-run-result})
          nil
          (catch clojure.lang.ExceptionInfo e
            (ex-data e)))))))

(deftest internal-errors-are-not-passed-on
  (with-redefs [client/post-raw (answering "{\"exception\":\"Connection refused: db.internal\"}")]
    (is
     (= {:status 500 :message "Internal error"}
        (try
          (client/query support/settings {:query-id :x})
          nil
          (catch clojure.lang.ExceptionInfo e
            (ex-data e)))))))

(deftest results-are-returned
  (with-redefs [client/post-raw (answering "{\"result\":{\"n\":1}}")]
    (is
     (= {:n 1}
        (client/query support/settings {:query-id :x})))))

(deftest only-command-waits-for-effects
  (let [settled
        (atom 0)]
    (with-redefs [client/post-raw (answering "{\"result\":{\"success\":true}}")
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
