(ns manager-svc.claims-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [manager-svc.claims :as claims]
            [manager-svc.test-support :as support]))

(def settings
  support/settings)

(deftest the-default-parser-reads-keycloak-claims
  (is
   (= {:username "alice"
       :email "alice@acme.test"
       :name "Alice"
       :tenant "acme"
       :teams [{:id "demo" :role :admin} {:id "ops" :role :member}]
       :tenant-admin true
       :operator false}
      (claims/default-parser {:sub "0c1d"
                              :preferred_username "alice"
                              :email "alice@acme.test"
                              :name "Alice"
                              :tenant "acme"
                              :groups ["/acme/ops" "/acme/demo" "/acme/demo/admin" "/globex/web/admin" "/acme/x/y/z"]
                              :realm_access {:roles ["tenant-admin" "offline_access"]}})))
  (testing "without a tenant claim the tenant is the one all groups agree on"
    (is
     (= "acme"
        (:tenant (claims/default-parser {:sub "bob" :groups ["/acme/demo" "/acme/ops/admin"]}))))
    (is
     (nil?
      (:tenant (claims/default-parser {:sub "bob" :groups ["/acme/demo" "/globex/web"]})))))
  (testing "a username that isn't valid falls back to the subject"
    (is
     (= "bob"
        (:username (claims/default-parser {:sub "bob" :preferred_username "Bob Smith"})))))
  (testing "roles may also come as a top-level roles claim"
    (is
     (true?
      (:operator (claims/default-parser {:sub "oscar" :roles ["platform-operator"]}))))))

(deftest parsed-claims-become-an-identity
  (is
   (= {:identity {:sub "bob"
                  :email "bob@users.local"
                  :name "bob"
                  :tenant "acme"
                  :teams {"demo" :member}
                  :tenant-admin false
                  :operator false}}
      (claims/parse settings {:sub "bob" :groups ["/acme/demo"]})))
  (testing "string keys, as a JSON file has them"
    (is
     (= "acme"
        (get-in (claims/parse settings {"sub" "bob" "tenant" "acme"}) [:identity :tenant]))))
  (testing "MANAGER_OPERATORS makes an operator whatever the claims say"
    (is
     (true?
      (get-in (claims/parse (assoc settings :operators ["bob"]) {:sub "bob" :tenant "acme"}) [:identity :operator])))))

(deftest some-identities-are-refused
  (is
   (= {:refused "Login refused: your account belongs to no tenant"}
      (claims/parse settings {:sub "bob"})))
  (is
   (= {:refused "Login refused: platform is the platform's own name"}
      (claims/parse settings {:sub "platform" :tenant "acme"})))
  (is
   (= {:refused "Login refused: the claims parser's answer is invalid (username should be a lowercase username)"}
      (claims/parse settings {:sub "Not Valid" :tenant "acme"})))
  (is
   (= {:refused "Login refused: the token names no valid username"}
      (claims/parse settings {:tenant "acme"}))))

(defn- parser-file!
  [source]
  (let [file
        (io/file (support/temp-dir) "parser.clj")]
    (spit file source)
    (.getPath file)))

(deftest operators-plug-in-their-own-parser-from-a-file
  (let [path
        (parser-file! (str "(ns custom.claims-parser)\n"
                           "(defn parse [claims]\n"
                           "  {:username (:upn claims)\n"
                           "   :tenant (:org claims)\n"
                           "   :teams (mapv (fn [team] {:id team :role \"member\"}) (:squads claims))\n"
                           "   :tenant-admin (= \"owner\" (:level claims))})\n"))

        custom
        (assoc settings :claims-parser "custom.claims-parser/parse" :claims-parser-file path)]
    (claims/check-settings! custom)
    (is
     (= {:sub "carol"
         :email "carol@users.local"
         :name "carol"
         :tenant "initech"
         :teams {"tps" :member}
         :tenant-admin true
         :operator false}
        (:identity (claims/parse custom {:sub "x" :upn "carol" :org "initech" :squads ["tps"] :level "owner"}))))))

(deftest invalid-parser-output-refuses-the-login
  (let [path
        (parser-file! (str "(ns custom.broken-parser)\n"
                           "(defn parse [claims]\n"
                           "  (case (:sub claims)\n"
                           "    \"a\" {:tenant \"Not A Name\"}\n"
                           "    \"b\" {:tenant \"acme\" :teams [{:id \"demo\" :role :owner}]}\n"
                           "    \"c\" [:not :a :map]\n"
                           "    (throw (ex-info \"boom\" {}))))\n"))

        broken
        (assoc settings :claims-parser "custom.broken-parser/parse" :claims-parser-file path)

        refusal
        (fn [sub] (:refused (claims/parse broken {:sub sub})))]
    (is
     (string/includes? (refusal "a") "tenant should be lowercase letters, digits and dashes"))
    (is
     (string/includes? (refusal "b") "teams.0.role"))
    (is
     (= "Login refused: the claims parser's answer is invalid (the claims parser did not return a map)"
        (refusal "c")))
    (is
     (= "Login refused: the claims parser failed (boom)"
        (refusal "d")))))

(deftest the-parser-file-is-loaded-again-when-it-changes
  (let [file
        (io/file (support/temp-dir) "hot_parser.clj")

        write!
        (fn [source modified]
          (spit file source)
          (.setLastModified file modified))

        parser-source
        (fn [tenant]
          (str "(ns custom.hot-parser)\n"
               "(defn parse [claims] {:username (:sub claims) :tenant \"" tenant "\"})\n"))

        hot
        (assoc settings
               :claims-parser "custom.hot-parser/parse"
               :claims-parser-file (.getPath file)
               :config-reload-seconds 0)

        tenant-of
        (fn [] (get-in (claims/parse hot {:sub "dana"}) [:identity :tenant]))]
    (write! (parser-source "acme") 1000000)
    (claims/check-settings! hot)
    (is
     (= "acme"
        (tenant-of)))
    (testing "a file that does not compile keeps the last good parser"
      (write! "(ns custom.hot-parser)\n(defn parse [claims] {:tenant " 2000000)
      (is
       (= "acme"
          (tenant-of))))
    (testing "the fixed file applies"
      (write! (parser-source "globex") 3000000)
      (is
       (= "globex"
          (tenant-of))))
    (testing "an operator reloads it now"
      (spit file (parser-source "initech"))
      (.setLastModified file 3000000)
      (is
       (= {:status :reloaded}
          (claims/reload! hot)))
      (is
       (= "initech"
          (tenant-of))))))

(deftest a-parser-that-does-not-load-stops-the-manager
  (is
   (thrown-with-msg? clojure.lang.ExceptionInfo #"Claims parser no.such/parser not found"
                     (claims/check-settings! (assoc settings :claims-parser "no.such/parser")))))

(deftest personas-list-the-dev-users
  (is
   (= [{:username "alice" :tenant "acme" :roles ["Tenant admin" "Team admin · alpha"]}
       {:username "bob" :tenant "acme" :roles ["Member · alpha" "Member · beta"]}
       {:username "gina" :tenant "globex" :roles ["Tenant admin" "Team admin · research"]}]
      (filterv #(#{"alice" "bob" "gina"} (:username %)) (claims/personas settings)))))
