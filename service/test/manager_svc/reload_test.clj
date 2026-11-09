(ns manager-svc.reload-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.reload :as reload]))

(defn- cell
  [options]
  (reload/cell [:reload-test (random-uuid)] (constantly (merge {:name "Test value"} options))))

(defn- counting
  "{:calls atom :load fn}: a load answering how often it was called, after
   (before n) ran."
  ([]
   (counting (constantly nil)))
  ([before]
   (let [calls
         (atom 0)]
     {:calls calls
      :load (fn [_previous]
              (let [n
                    (swap! calls inc)]
                (before n)
                n))})))

(deftest a-value-is-loaded-once-and-again-when-it-is-old
  (let [clock
        (atom 0)

        {:keys [calls load]}
        (counting)

        value
        (cell {:load load :refresh-ms 1000 :retry-ms 100})]
    (with-redefs [reload/now-ms (fn [] @clock)]
      (is
       (= [1 1]
          [(reload/value value) (reload/value value)]))
      (reset! clock 1000)
      (is
       (= 2
          (reload/value value)))
      (is
       (= 2
          @calls)))))

(deftest a-failed-load-keeps-the-last-good-value-and-waits-before-it-retries
  (let [clock
        (atom 0)

        {:keys [calls load]}
        (counting #(when (= 2 %) (throw (ex-info "source is down" {}))))

        value
        (cell {:load load :refresh-ms 1000 :retry-ms 100})]
    (with-redefs [reload/now-ms (fn [] @clock)]
      (reload/value value)
      (reset! clock 1000)
      (is
       (= 1
          (reload/value value)))
      (is
       (= "source is down"
          (reload/error value)))
      (reset! clock 1050)
      (reload/value value)
      (is
       (= 2
          @calls)
       "within the retry interval nothing is tried")
      (reset! clock 1100)
      (is
       (= 3
          (reload/value value)))
      (is
       (nil? (reload/error value))))))

(deftest a-value-that-never-loaded-says-why
  (let [value
        (cell {:load (fn [_] (throw (ex-info "no such file" {})))})]
    (is
     (nil? (reload/value value)))
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"Test value: no such file"
                       (reload/value! value)))))

(deftest a-changed-stamp-loads-again-and-a-broken-source-waits-for-the-next-change
  (let [stamp
        (atom 1)

        {:keys [calls load]}
        (counting #(when (= 2 %) (throw (ex-info "broken" {}))))

        value
        (cell {:load load :stamp (fn [] @stamp)})]
    (is
     (= [1 1]
        [(reload/value value) (reload/value value)]))
    (reset! stamp 2)
    (is
     (= [1 1]
        [(reload/value value) (reload/value value)])
     "the broken version is tried once")
    (is
     (= 2
        @calls))
    (reset! stamp 3)
    (is
     (= 3
        (reload/value value)))))

(deftest callers-arriving-together-share-one-load
  (let [{:keys [calls load]}
        (counting (fn [_] (Thread/sleep 30)))

        value
        (cell {:load load})

        readers
        (mapv (fn [_] (future (reload/value value))) (range 16))]
    (is
     (= #{1}
        (set (map deref readers))))
    (is
     (= 1
        @calls))))

(deftest an-operators-reload-makes-its-own-attempt-after-one-under-way
  (let [loading
        (promise)

        release
        (promise)

        {:keys [calls load]}
        (counting (fn [n]
                    (when (= 1 n)
                      (deliver loading true)
                      @release)))

        value
        (cell {:load load})

        reader
        (future (reload/value value))

        _
        @loading

        operator
        (future (reload/reload-now! value))]
    (Thread/sleep 30)
    (deliver release true)
    (is
     (= 1
        @reader))
    (is
     (= {:status :reloaded}
        @operator))
    (is
     (= 2
        @calls))
    (testing "and reports a failure"
      (let [broken
            (cell {:load (fn [_] (throw (ex-info "broken" {})))})]
        (is
         (= {:status :failed :error "broken"}
            (reload/reload-now! broken)))))))
