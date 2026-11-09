(ns manager-web.roles-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.roles :as roles]
            [manager-web.shell.views :as shell]))

(def alice
  {:tenant "acme" :tenant-admin true :teams [{:id "demo" :role :admin}]})

(def bob
  {:tenant "acme" :teams [{:id "demo" :role "member"} {:id "ops" :role "admin"}]})

(def oscar
  {:operator true :teams []})

(def dave
  {:tenant "acme" :teams [{:id "ops" :name "Operations" :role :member :claimed-role :member}
                          {:id "payments" :name "Payments" :role :admin :claimed-role :admin}]})

(def admin-alice
  {:tenant "acme" :tenant-admin true :sub "alice"
   :teams [{:id "demo" :name "Demo team" :role :admin :claimed-role :admin}
           {:id "ops" :name "Operations" :role :admin :claimed-role nil}]})

(deftest role-labels-name-the-teams
  (is
   (= "Platform operator"
      (roles/role-label oscar)))
  (is
   (= "Tenant admin"
      (roles/role-label alice))
   "the tenant is named once, under it")
  (is
   (= "Admin of ops · member of demo"
      (roles/role-label bob)))
  (is
   (= "Admin of Payments · member of Operations"
      (roles/role-label dave)))
  (is
   (= "Member of demo"
      (roles/role-label {:tenant "acme" :teams [{:id "demo" :role :member}]})))
  (is
   (= "Not in a team yet"
      (roles/role-label {:tenant "acme" :teams []}))))

(deftest every-team-and-role-for-the-tooltip
  (is
   (= ["Member · Operations" "Admin · Payments"]
      (roles/team-role-lines dave)))
  (is
   (= ["Admin · Demo team" "Tenant admin, not a member · Operations"]
      (roles/team-role-lines admin-alice))))

(deftest the-role-says-where-it-comes-from
  (is
   (= [:admin :tenant-admin :tenant-admin]
      (mapv #(roles/role-in-team admin-alice %) ["demo" "ops" "payments"])))
  (is
   (nil? (roles/role-in-team dave "demo")))
  (is
   (= [:member :admin]
      (mapv #(roles/role-in-team dave %) ["ops" "payments"])))
  (is
   (= "Operations"
      (roles/team-name dave "ops")))
  (is
   (= "unknown"
      (roles/team-name dave "unknown")))
  (is
   (= "Demo team"
      (roles/team-name (assoc dave :tenant-teams [{:id "demo" :name "Demo team"}]) "demo"))
   "teams of the tenant the person isn't in are named too"))

(deftest visibility-reads-from-the-viewer's-side
  (let [session
        {:visibility :private :sub "dave" :team-id "ops" :is-owner false}]
    (is
     (= "Private to dave"
        (roles/session-visibility-label admin-alice session)))
    (is
     (= "Only me"
        (roles/session-visibility-label admin-alice (assoc session :is-owner true))))
    (is
     (= ["Team Operations" "Everyone in acme"]
        (mapv #(roles/session-visibility-label admin-alice (assoc session :visibility %)) [:team :tenant])))))

(deftest admins-are-told-when-they-see-something-only-as-admins
  (let [private
        {:visibility :private :sub "erin" :team-id "payments" :is-owner false}]
    (is
     (= "Visible to you as Payments admin"
        (roles/admin-access dave private)))
    (is
     (= "Visible to you as tenant admin"
        (roles/admin-access admin-alice {:visibility :team :sub "henry" :team-id "ops"})))
    (is
     (nil? (roles/admin-access admin-alice {:visibility :team :sub "bob" :team-id "demo"}))
     "a member sees the team's sessions anyway")
    (is
     (nil? (roles/admin-access dave (assoc private :visibility :tenant))))
    (is
     (nil? (roles/admin-access dave (assoc private :is-owner true))))))

(deftest team-sessions-are-owned-by-their-team
  (is
   (= ["You" "Team Operations" "bob"]
      (mapv #(roles/session-owner dave %) [{:is-owner true :sub "dave"}
                                           {:team-session true :team-id "ops" :sub "platform"}
                                           {:sub "bob" :team-id "ops"}]))))

(deftest tenant-admins-administer-every-team-of-their-tenant
  (is
   (roles/team-admin? alice "payments"))
  (is
   (roles/team-admin? bob "ops"))
  (is
   (not (roles/team-admin? bob "demo")))
  (is
   (= :member
      (roles/team-role bob "demo")))
  (is
   (nil? (roles/team-role bob "payments"))))

(deftest a-tenant-admin-flag-without-a-tenant-counts-for-nothing
  (is
   (not (roles/tenant-admin? {:tenant-admin true}))))

(deftest tenant-labels
  (is
   (= "acme"
      (roles/tenant-label alice)))
  (is
   (= "Platform"
      (roles/tenant-label oscar))))

(deftest visibility-labels-name-the-tenant
  (is
   (= ["Only me" "Team" "Everyone in acme"]
      (mapv #(roles/visibility-label % "acme") [:private "team" "tenant"])))
  (is
   (= ["Private to dave" "Team Payments"]
      (mapv #(roles/visibility-label % {:tenant "acme" :team-name "Payments" :owner "dave"}) [:private :team]))))

(deftest navigation-follows-the-roles
  (is
   (= ["Work" "Library" "You" "Tenant"]
      (mapv :group (shell/nav-groups alice))))
  (is
   (= ["Work" "Library" "You"]
      (mapv :group (shell/nav-groups bob))))
  (is
   (= ["Platform"]
      (mapv :group (shell/nav-groups oscar))))
  (is
   (= ["Work" "Library" "You" "Tenant" "Platform"]
      (mapv :group (shell/nav-groups (assoc alice :operator true))))))

(deftest whose-credentials-work-runs-with
  (is
   (= ["your own where set, else Operations', else acme's"
       "their own where set, else Payments', else acme's"
       "Operations', else acme's"
       "ops', else the tenant's"]
      [(roles/credential-order dave "ops" "your")
       (roles/credential-order dave "payments" "their")
       (roles/credential-order dave "ops" nil)
       (roles/credential-order {} "ops" nil)])))

(deftest a-team-session-has-no-owner-to-keep-it-private
  (is
   (= [[:private :team :tenant] [:team :tenant]]
      [(roles/visibility-options false) (roles/visibility-options true)])))
