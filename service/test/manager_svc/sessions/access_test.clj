(ns manager-svc.sessions.access-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.platform.api :as platform]
            [manager-svc.sessions.access :as access]
            [manager-svc.sessions.fixtures :as fixtures]))

(def session
  {:id #uuid "11111111-1111-1111-1111-111111111111"
   :kind :session
   :sub "bob"
   :team-id "alpha"
   :visibility :private})

(defmacro with-teams
  [& body]
  `(with-redefs [platform/platform-admin? (fn [_# username#] (= "root" username#))
                 platform/role (fn [_# username# team-id#] (get-in fixtures/teams [team-id# username#]))
                 credentials/uses-personal? (fn [_# owner# _#] (contains? fixtures/*personal* owner#))]
     ~@body))

(deftest viewing
  (with-teams
    (let [system
          (fixtures/system)]
      (testing "private sessions are seen by their owner, team admins and platform admins"
        (is
         (= {"bob" true "alice" true "root" true "carol" false "mallory" false}
            (into {} (for [user ["bob" "alice" "root" "carol" "mallory"]]
                       [user (access/can-view? system session user)])))))
      (testing "team sessions are seen by team members"
        (is
         (= {"carol" true "mallory" false}
            (into {} (for [user ["carol" "mallory"]]
                       [user (access/can-view? system (assoc session :visibility :team) user)])))))
      (testing "public sessions are seen by everyone"
        (is
         (true?
          (access/can-view? system (assoc session :visibility :public) "mallory")))))))

(deftest managing
  (with-teams
    (let [system
          (fixtures/system)]
      (is
       (= {"bob" true "alice" true "root" true "carol" false}
          (into {} (for [user ["bob" "alice" "root" "carol"]]
                     [user (access/can-manage? system (assoc session :visibility :public) user)])))))))

(deftest opening
  (with-teams
    (let [system
          (fixtures/system)]
      (testing "a locked session is blocked even for its owner"
        (is
         (= :locked
            (access/open-blocked system (assoc session :locked-at "2026-01-01T00:00:00Z") "bob"))))
      (testing "the owner may open their own session"
        (is
         (nil?
          (access/open-blocked system session "bob"))))
      (testing "a team admin may see but not open a private session"
        (is
         (= :private
            (access/open-blocked system session "alice"))))
      (testing "sessions with the owner's personal credentials are blocked for others"
        (binding [fixtures/*personal* #{"bob"}]
          (is
           (= :personal
              (access/open-blocked system (assoc session :id (random-uuid) :visibility :public) "carol")))))
      (testing "team sessions are open to team members and hidden from others"
        (is
         (nil?
          (access/open-blocked system (assoc session :visibility :team) "carol")))
        (is
         (= :hidden
            (access/open-blocked system (assoc session :visibility :team) "mallory")))))))

(deftest platform-run-sessions-are-for-their-team
  (with-teams
    (let [system
          (fixtures/system)

          platform-session
          (assoc session :sub "platform" :visibility :public)]
      (testing "non-members may look at a public platform session but not work in it"
        (is
         (true?
          (access/can-view? system platform-session "mallory")))
        (is
         (= :team-only
            (access/open-blocked system platform-session "mallory"))))
      (testing "team members and platform admins may work in it"
        (is
         (= {"carol" nil "alice" nil "root" nil}
            (into {} (for [user ["carol" "alice" "root"]]
                       [user (access/open-blocked system platform-session user)])))))
      (testing "the platform's own sessions never use personal credentials"
        (is
         (false?
          (access/personal? system platform-session)))))))

(deftest a-person-named-like-the-platform-is-not-the-platform
  (with-teams
    (let [system
          (fixtures/system)]
      (is
       (false?
        (access/platform-owned? (assoc session :sub "platformer")))))))

(deftest failing-facades-fail-closed
  (with-redefs [platform/platform-admin? (fn [_ _] (throw (ex-info "down" {})))
                platform/role (fn [_ _ _] (throw (ex-info "down" {})))
                credentials/uses-personal? (fn [_ _ _] (throw (ex-info "down" {})))]
    (let [system
          (fixtures/system)]
      (is
       (false?
        (access/can-view? system session "alice")))
      (testing "an unknown answer about personal credentials counts as personal"
        (is
         (true?
          (access/personal? system session))))
      (is
       (= :personal
          (access/open-blocked system (assoc session :visibility :public) "carol"))))))

(deftest a-viewer-asks-the-platform-once-per-team
  (let [asked
        (atom [])]
    (with-redefs [platform/platform-admin? (fn [_ username] (swap! asked conj [:admin username]) false)
                  platform/role (fn [_ username team-id] (swap! asked conj [:role username team-id]) "member")]
      (let [system
            (fixtures/system)

            viewer
            (access/viewer system "carol")]
        (doseq [visibility [:team :private :team]]
          (access/can-view? system (assoc session :visibility visibility) viewer))
        (is
         (= [[:admin "carol"] [:role "carol" "alpha"]]
            @asked))))))
