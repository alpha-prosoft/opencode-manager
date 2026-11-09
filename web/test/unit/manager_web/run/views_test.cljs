(ns manager-web.run.views-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.components.review :as review]
            [manager-web.run.events :as events]
            [manager-web.run.views :as views]))

(deftest progress-steps
  (is
   (= [views/automated-steps 2]
      (views/progress-steps {:status "awaiting_result" :activity :review})))
  (is
   (= [views/ci-steps 4]
      (views/progress-steps {:status :following-ci :activity :implement})))
  (is
   (= [views/session-steps 1]
      (views/progress-steps {:status :running :activity :session}))))

(deftest retry-spec
  (is
   (= {:id [::events/retry 1]
       :error ::events/action
       :command {:cmd-id :retry-run :id 2 :attrs {:source-id 1}}
       :refetch-on-failure [:my-credentials]
       :navigate [:run {:run-id "2"}]}
      (events/retry-spec 1 2))))

(deftest cancelling-asks-first-and-refreshes-the-run
  (let [request
        (events/cancel-request 1)]
    (is
     (= "Cancel run"
        (:confirm request)))
    (is
     (= {:cmd-id :cancel-run :id 1}
        (get-in request [:run :command])))
    (is
     (= [[:run 1]]
        (get-in request [:run :refetch])))))

(def me
  {:sub "bob" :tenant "acme" :teams [{:id "ops" :name "Operations" :role :member :claimed-role :member}
                                     {:id "demo" :name "Demo" :role :admin :claimed-role :admin}]})

(deftest only-its-starter-and-team-admins-cancel-a-run
  (is
   (true? (views/may-cancel? me {:created-by "bob" :team-id "ops"})))
  (is
   (false? (views/may-cancel? me {:created-by "erin" :team-id "ops"})))
  (is
   (true? (views/may-cancel? me {:created-by "erin" :team-id "demo"})))
  (is
   (false? (views/may-cancel? (assoc me :sub "platform") {:created-by "platform" :platform-started true :team-id "ops"})))
  (is
   (true? (views/may-cancel? {:tenant "acme" :tenant-admin true} {:created-by "erin" :team-id "ops"}))))

(deftest a-run-says-whose-credentials-it-works-with
  (is
   (= ["You: your own where set, else Operations', else acme's"
       "erin: their own where set, else Operations', else acme's"
       "Team Operations: Operations', else acme's"]
      [(views/runs-as me {:created-by "bob" :team-id "ops"})
       (views/runs-as me {:created-by "erin" :team-id "ops"})
       (views/runs-as me {:created-by "platform" :platform-started true :team-id "ops"})])))

(deftest started-by-names-the-platform-from-its-marker
  (is
   (= "The platform"
      (views/started-by {:platform-started true :created-by "platform:schedule"})))
  (is
   (= "bob"
      (views/started-by {:platform-started false :created-by "bob"}))))

(deftest gerrit-results-speak-of-changes-and-patch-sets
  (is
   (= "Change "
      (views/pull-request-lead :gerrit {:id 5})))
  (is
   (= "New patch set on change "
      (views/pull-request-lead "gerrit" {:id 5 :updated true})))
  (is
   (= "Draft pull request "
      (views/pull-request-lead :github {:id 5 :draft true}))))

(deftest reviews-say-where-they-were-posted
  (is
   (re-find #"posted to the change \("
            (review/posting-text {:plan {:mode "auto" :comments 1 :inline 0 :folded 3} :posted 1} :gerrit)))
  (is
   (re-find #"posted to the pull request \("
            (review/posting-text {:plan {:mode "auto" :comments 1 :inline 0 :folded 3} :posted 1}))))
