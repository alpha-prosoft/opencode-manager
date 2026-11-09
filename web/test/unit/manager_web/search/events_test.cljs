(ns manager-web.search.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-web.search.events :as events]))

(def results
  {:projects [{:id "api" :team-id "demo" :name "API"}]
   :teams [{:id "demo" :name "Demo" :member true} {:id "ops" :name "Ops" :member false}]
   :sessions [{:id #uuid "00000000-0000-0000-0000-000000000001" :name "Fix it"}]})

(deftest results-form-one-list-group-by-group
  (is
   (= [[:project "api"] [:team "demo"] [:team "ops"] [:session #uuid "00000000-0000-0000-0000-000000000001"]]
      (mapv (juxt :kind (comp :id :item)) (events/rows results))))
  (is
   (= []
      (events/rows nil))))

(deftest arrows-move-through-the-list-and-wrap
  (is
   (= 1
      (events/moved 0 4 1)))
  (is
   (= 0
      (events/moved 3 4 1)))
  (is
   (= 3
      (events/moved 0 4 -1)))
  (is
   (= 0
      (events/moved 2 0 1))))

(deftest a-result-opens-its-page
  (let [[project team other-team session]
        (events/rows results)]
    (is
     (nil? (events/target other-team))
     "a team the person isn't in has no page for them")
    (is
     (= [:project {:team-id "demo" :project-id "api"}]
        (events/target project)))
    (is
     (= [:team {:team-id "demo"}]
        (events/target team)))
    (is
     (= [:session {:session-id "00000000-0000-0000-0000-000000000001"}]
        (events/target session)))))

(deftest ctrl-or-cmd-k-opens-the-search
  (is
   (true? (events/shortcut? {:key "k" :ctrl? true})))
  (is
   (true? (events/shortcut? {:key "K" :meta? true})))
  (is
   (false? (events/shortcut? {:key "k"})))
  (is
   (false? (events/shortcut? {:key "j" :ctrl? true}))))

(deftest only-the-reply-to-the-latest-text-counts
  (let [[state token]
        (events/typed {:token 1 :results results :index 2} "ap")]
    (is
     (= [2 "ap" 0 true]
        [token (:text state) (:index state) (:loading? state)]))
    (is
     (nil? (events/answered state 1 {:projects []})))
    (is
     (= results
        (:results (events/answered state 2 results)))))
  (testing "Enter while the reply is on its way waits for it instead of opening an earlier result"
    (let [[state _]
          (events/typed {:token 1 :results results} "fix")

          [waiting row]
          (events/entered state)]
      (is
       (nil? row))
      (is
       (= {:kind :session :item {:id 9 :name "Fix it"}}
          (events/awaited waiting {:sessions [{:id 9 :name "Fix it"}]})))
      (is
       (nil? (events/awaited state {:sessions [{:id 9 :name "Fix it"}]})))
      (is
       (nil? (events/awaited waiting {:sessions []})))))
  (testing "Enter on settled results opens the selected one"
    (is
     (= {:kind :team :item {:id "demo" :name "Demo" :member true}}
        (second (events/entered {:results results :index 1})))))
  (testing "a blank text clears the results without asking"
    (let [[state token]
          (events/typed {:token 1 :results results} "  ")]
      (is
       (nil? token))
      (is
       (nil? (:results state))))))


