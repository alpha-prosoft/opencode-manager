(ns manager-web.session.views-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.session.events :as events]
            [manager-web.session.views :as views]))

(deftest every-reason-the-service-blocks-a-session-is-explained
  (is
   (= #{:locked :personal :private :team-only :hidden}
      (set (keys views/blocked-reasons)))))

(deftest renaming-sends-the-name-and-description
  (is
   (= {:id [::events/rename 1]
       :error ::events/rename
       :command {:cmd-id :update-session :id 1 :attrs {:name "New name" :description "Why"}}
       :refetch [[:session 1]]
       :on-success [::events/close-rename]}
      (events/rename-spec 1 {:name "  New name " :description "Why"}))))

(deftest every-blocked-session-but-a-hidden-one-can-be-forked
  (is
   (= #{:locked :personal :private :team-only}
      (set (filter views/forkable? (keys views/blocked-reasons))))))

(deftest team-sessions-explain-who-may-work-in-them
  (is
   (not= (views/sharing-help {:sub "platform" :team-session true})
         (views/sharing-help {:sub "bob"}))))

(def dave
  {:tenant "acme" :sub "dave" :teams [{:id "payments" :name "Payments" :role :admin :claimed-role :admin}]})

(deftest admins-learn-why-they-see-a-private-session
  (is
   (= "This is erin's private session. As admin of Payments you can see, rename and delete it, but only erin can work in it. Fork it to continue in your own copy."
      (views/blocked-text :private dave {:sub "erin" :team-id "payments" :can-manage true})))
  (is
   (re-find #"^Only members of Payments can work in this team session \(it uses Payments' credentials\)"
            (views/blocked-text :team-only dave {:team-id "payments"}))))

(deftest only-owners-reshare-their-sessions
  (is
   (views/may-change-sharing? {:can-manage true :is-owner true}))
  (is
   (views/may-change-sharing? {:can-manage true :team-session true}))
  (is
   (not (views/may-change-sharing? {:can-manage true :is-owner false})))
  (is
   (= [:team :tenant]
      (mapv first (views/sharing {:tenant "acme"} {:team-session true :team-id "ops"})))))

(deftest sharing-with-the-whole-tenant-asks-first
  (let [[event request]
        (events/visibility-event 1 :tenant "acme")]
    (is
     (= :manager-web.components.confirm/ask
        event))
    (is
     (= "Share with everyone in acme?"
        (:title request)))
    (is
     (= {:cmd-id :update-session :id 1 :attrs {:visibility :tenant}}
        (get-in request [:run :command]))))
  (is
   (= [:manager-web.data/run (events/visibility-spec 1 :team)]
      (events/visibility-event 1 :team "acme")))
  (is
   (= [[:session 1]]
      (:refetch (events/visibility-spec 1 :private)))))

(deftest the-tabs-of-a-session
  (is
   (= [:overview :conversations :activity :audit]
      (mapv first events/tabs))))

(deftest a-read-only-sharing-note-names-who-sees-it
  (is
   (= "Private to erin: only erin and the admins of Payments see it."
      (views/sharing-note dave {:visibility :private :sub "erin" :team-id "payments"}))))
