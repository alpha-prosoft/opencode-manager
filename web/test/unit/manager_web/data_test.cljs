(ns manager-web.data-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.data :as data]))

(def query
  {:query {:query-id :list-runs}})

(deftest a-poll-is-skipped-while-the-same-request-is-in-flight
  (let [[db token]
        (data/begin-fetch {} :runs query nil false)]
    (is
     (= 1
        token))
    (is
     (true?
      (get-in db [::data/loads :runs :loading?])))
    (is
     (nil? (data/begin-fetch db :runs query nil false)))
    (is
     (= 2
        (second (data/begin-fetch db :runs query nil true))))
    (is
     (= 2
        (second (data/begin-fetch db :runs {:query {:query-id :other}} nil false))))))

(deftest only-the-latest-reply-is-kept
  (let [[db first-token]
        (data/begin-fetch {} :runs query nil false)

        [db second-token]
        (data/begin-fetch db :runs query nil true)

        db
        (data/accept-result db :runs second-token [:new])]
    (is
     (= [:new]
        (data/loaded-data db :runs)))
    (is
     (nil? (data/accept-result db :runs first-token [:old])))
    (is
     (nil? (data/accept-failure db :runs first-token "late error")))))

(deftest a-failed-refresh-keeps-the-data
  (let [[db token]
        (data/begin-fetch {} :runs query nil false)

        db
        (data/accept-result db :runs token [:a])

        [db token]
        (data/begin-fetch db :runs query nil false)

        db
        (data/accept-failure db :runs token "Network error")]
    (is
     (= {:data [:a] :error "Network error" :loading? false}
        (select-keys (data/load-of db :runs) [:data :error :loading?])))))

(deftest replies-after-logout-are-dropped
  (let [[_ token]
        (data/begin-fetch {} :runs query nil false)]
    (is
     (nil? (data/accept-result {} :runs token [:a])))))

(deftest run-effects
  (is
   (= [[:dispatch [::data/refetch [:runs]]]
       [:dispatch [:edd.events/navigate :run {:run-id "1"}]]
       [:dispatch [:done 42]]
       [:dispatch [:finally]]]
      (data/run-effects {:refetch [:runs] :navigate [:run {:run-id "1"}] :on-success [:done] :finally [:finally]}
                        42)))
  (is
   (= []
      (data/run-effects {} nil))))

(deftest busy-and-errors-of-a-run
  (let [spec
        {:id [:start 1] :error ::action}

        started
        (data/start-run {::data/errors {::action "old"}} spec)]
    (is
     (true?
      (get-in started [::data/busy [:start 1]])))
    (is
     (nil? (get-in started [::data/errors ::action])))
    (is
     (= "failed"
        (get-in (data/finish-run started spec "failed") [::data/errors ::action])))
    (is
     (empty? (::data/busy (data/finish-run started spec))))))

(deftest page-state
  (let [db
        (data/set-ui {} :runs :filter :active)]
    (is
     (= :active
        (data/ui db :runs :filter :all)))
    (is
     (= :all
        (data/ui (data/reset-ui db :runs) :runs :filter :all)))))

(deftest route-ids
  (is
   (uuid? (data/route-id "0b8f6c4e-0c39-4e57-9d1e-6b8bd5f0f3a1")))
  (is
   (= "demo"
      (data/route-id "demo"))))
