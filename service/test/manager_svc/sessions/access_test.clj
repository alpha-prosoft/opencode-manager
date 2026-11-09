(ns manager-svc.sessions.access-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.sessions.access :as access]
            [manager-svc.sessions.fixtures :as fixtures]))

(def session
  {:id #uuid "11111111-1111-1111-1111-111111111111"
   :kind :session
   :sub "bob"
   :tenant "acme"
   :team-id "alpha"
   :visibility :private})

(defmacro with-credentials
  [& body]
  `(with-redefs [credentials/uses-personal? (fn [_# scope# _#] (contains? fixtures/*personal* (:user scope#)))]
     ~@body))

(defn- by-person
  [f people]
  (into {} (for [username people] [username (f (fixtures/person username))])))

(deftest viewing
  (with-credentials
    (let [system
          (fixtures/system)]
      (testing "private sessions are seen by their owner, team admins and tenant admins"
        (is
         (= {"bob" true "alice" true "root" true "carol" false "mallory" false}
            (by-person #(access/can-view? system session %) ["bob" "alice" "root" "carol" "mallory"]))))
      (testing "team sessions are seen by team members"
        (is
         (= {"carol" true "mallory" false}
            (by-person #(access/can-view? system (assoc session :visibility :team) %) ["carol" "mallory"]))))
      (testing "tenant sessions are seen by everyone in the tenant"
        (is
         (true?
          (access/can-view? system (assoc session :visibility :tenant) (fixtures/person "mallory"))))))))

(deftest nothing-is-visible-across-tenants
  (with-credentials
    (let [system
          (fixtures/system)

          shared
          (assoc session :visibility :tenant)]
      (testing "not even to the other tenant's admins or members of a team with the same id"
        (is
         (= {"gina" false "hank" false}
            (by-person #(access/can-view? system shared %) ["gina" "hank"])))
        (is
         (= {"gina" false "hank" false}
            (by-person #(access/can-manage? system shared %) ["gina" "hank"])))
        (is
         (= {"gina" :hidden "hank" :hidden}
            (by-person #(access/open-blocked system shared %) ["gina" "hank"]))))
      (testing "a person of another tenant with the owner's username is not the owner"
        (is
         (false?
          (access/can-view? system session (assoc (fixtures/person "hank") :id "bob"))))))))

(deftest managing
  (with-credentials
    (let [system
          (fixtures/system)]
      (is
       (= {"bob" true "alice" true "root" true "carol" false}
          (by-person #(access/can-manage? system (assoc session :visibility :tenant) %) ["bob" "alice" "root" "carol"]))))))

(deftest edge-identities-are-viewers-too
  (with-credentials
    (let [system
          (fixtures/system)

          identity
          {:sub "carol" :tenant "acme" :teams {"alpha" :member} :tenant-admin false}]
      (is
       (true?
        (access/can-view? system (assoc session :visibility :team) identity)))
      (is
       (nil?
        (access/open-blocked system (assoc session :visibility :team) identity))))))

(deftest opening
  (with-credentials
    (let [system
          (fixtures/system)

          blocked
          (fn [s username] (access/open-blocked system s (fixtures/person username)))]
      (testing "a locked session is blocked even for its owner"
        (is
         (= :locked
            (blocked (assoc session :locked-at "2026-01-01T00:00:00Z") "bob"))))
      (testing "the owner may open their own session"
        (is
         (nil?
          (blocked session "bob"))))
      (testing "a team admin may see but not open a private session"
        (is
         (= :private
            (blocked session "alice"))))
      (testing "sessions with the owner's personal credentials are blocked for others"
        (binding [fixtures/*personal* #{"bob"}]
          (is
           (= :personal
              (blocked (assoc session :id (random-uuid) :visibility :tenant) "carol")))))
      (testing "team sessions are open to team members and hidden from others"
        (is
         (nil?
          (blocked (assoc session :visibility :team) "carol")))
        (is
         (= :hidden
            (blocked (assoc session :visibility :team) "mallory")))))))

(deftest platform-run-sessions-are-for-their-team
  (with-credentials
    (let [system
          (fixtures/system)

          platform-session
          (assoc session :sub "platform" :visibility :tenant)]
      (testing "people of the tenant outside the team may look at it but not work in it"
        (is
         (true?
          (access/can-view? system platform-session (fixtures/person "mallory"))))
        (is
         (= :team-only
            (access/open-blocked system platform-session (fixtures/person "mallory")))))
      (testing "team members and tenant admins may work in it"
        (is
         (= {"carol" nil "alice" nil "root" nil}
            (by-person #(access/open-blocked system platform-session %) ["carol" "alice" "root"]))))
      (testing "members of a same-named team of another tenant may not"
        (is
         (= :hidden
            (access/open-blocked system platform-session (fixtures/person "hank")))))
      (testing "the platform's own sessions never use personal credentials"
        (is
         (false?
          (access/personal? system platform-session)))))))

(deftest a-person-named-like-the-platform-is-not-the-platform
  (is
   (false?
    (access/platform-owned? (assoc session :sub "platformer")))))

(deftest failing-facades-fail-closed
  (with-redefs [credentials/uses-personal? (fn [_ _ _] (throw (ex-info "down" {})))]
    (let [system
          (fixtures/system)]
      (testing "an unknown answer about personal credentials counts as personal"
        (is
         (true?
          (access/personal? system session))))
      (is
       (= :personal
          (access/open-blocked system (assoc session :visibility :tenant) (fixtures/person "carol")))))))

(deftest personal-credentials-are-asked-about-in-the-sessions-scope
  (let [asked
        (atom nil)]
    (with-redefs [credentials/uses-personal? (fn [_ scope _] (reset! asked scope) false)]
      (access/personal? (fixtures/system) session)
      (is
       (= {:tenant "acme" :team-id "alpha" :user "bob"}
          @asked)))))
