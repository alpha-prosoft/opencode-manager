(ns manager-svc.workers-test
  (:require [clojure.test :refer [deftest is]]
            [manager-svc.workers :as workers])
  (:import [java.util.concurrent CountDownLatch Executors TimeUnit]))

(deftest a-job-never-overlaps-itself-and-reruns-for-calls-during-a-run
  (let [active
        (atom 0)

        overlaps
        (atom 0)

        runs
        (atom 0)

        started
        (CountDownLatch. 1)

        release
        (CountDownLatch. 1)

        job
        {:id :test
         :run (fn [_]
                (when (> (swap! active inc) 1)
                  (swap! overlaps inc))
                (swap! runs inc)
                (.countDown started)
                (.await release 5 TimeUnit/SECONDS)
                (swap! active dec))}

        runnable
        (workers/serialized {} job (atom :idle))

        pool
        (Executors/newFixedThreadPool 4)]
    (.submit pool ^Runnable runnable)
    (.await started 5 TimeUnit/SECONDS)
    (dotimes [_ 3]
      (.submit pool ^Runnable runnable))
    (Thread/sleep 100)
    (.countDown release)
    (.shutdown pool)
    (.awaitTermination pool 5 TimeUnit/SECONDS)
    (is
     (= 0
        @overlaps))
    (is
     (= 2
        @runs))))
