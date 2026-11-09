(ns manager-svc.platform.tenants-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [manager-svc.auth :as auth]
            [manager-svc.platform.api :as api]
            [manager-svc.platform.reload :as reload]
            [manager-svc.platform.tenants :as tenants]
            [manager-svc.test-support :as support]))

(defn- tenants-file
  []
  (io/file (support/temp-dir) "tenants.json"))

(defn- write!
  [file entries modified]
  (spit file (if (string? entries) entries (json/write-value-as-string entries)))
  (.setLastModified file modified))

(defn- settings-for
  [file]
  (assoc support/settings :tenants-file (.getPath file) :tenants nil :config-reload-seconds 0))

(defn- names
  [settings]
  (mapv :name (tenants/current settings)))

(deftest the-tenants-file-is-read-again-when-it-changes
  (let [file
        (tenants-file)

        settings
        (settings-for file)]
    (write! file [{:name "acme" :onboarding "/repos/acme"}] 1000000)
    (tenants/check-settings! settings)
    (is
     (= ["acme"]
        (names settings)))
    (testing "an invalid file keeps the last good list"
      (write! file [{:name "Bad Name" :onboarding "/repos/x"}] 2000000)
      (is
       (= ["acme"]
          (names settings)))
      (write! file "[{" 3000000)
      (is
       (= ["acme"]
          (names settings))))
    (testing "the fixed file applies"
      (write! file [{:name "acme" :onboarding "/repos/acme"} {:name "globex" :onboarding "/repos/globex"}] 4000000)
      (is
       (= ["acme" "globex"]
          (names settings))))
    (testing "an operator reloads it now; a failure is reported"
      (write! file "not json" 4000000)
      (is
       (= :failed
          (:status (tenants/reload! settings))))
      (is
       (= ["acme" "globex"]
          (names settings))))))

(deftest tenants-added-to-the-file-are-synced-right-away
  (let [file
        (tenants-file)

        system
        {:settings (settings-for file)
         :platform/configured-tenants (atom nil)}

        synced
        (atom [])]
    (write! file [{:name "acme" :onboarding "/repos/acme"}] 1000000)
    (with-redefs [api/sync-tenant! (fn [_ tenant-name] (swap! synced conj tenant-name))]
      (testing "the startup sync covers the tenants configured at start"
        (is
         (= []
            (reload/sync-new-tenants! system))))
      (write! file [{:name "acme" :onboarding "/repos/acme"} {:name "globex" :onboarding "/repos/globex"}] 2000000)
      (is
       (= ["globex"]
          (reload/sync-new-tenants! system)))
      (is
       (= []
          (reload/sync-new-tenants! system)))
      (testing "a removed tenant is no longer configured and syncs again when it comes back"
        (write! file [{:name "acme" :onboarding "/repos/acme"}] 3000000)
        (is
         (= []
            (reload/sync-new-tenants! system)))
        (is
         (not (api/tenant-known? (assoc system :platform/registry (atom {})) "globex")))
        (write! file [{:name "acme" :onboarding "/repos/acme"} {:name "globex" :onboarding "/repos/globex"}] 4000000)
        (is
         (= ["globex"]
            (reload/sync-new-tenants! system))))
      (is
       (= ["globex" "globex"]
          @synced)))))

(deftest a-new-tenant-that-fails-to-sync-does-not-stop-the-others
  (let [file
        (tenants-file)

        system
        {:settings (settings-for file)
         :platform/configured-tenants (atom nil)}

        synced
        (atom [])]
    (write! file [{:name "acme" :onboarding "/repos/acme"}] 1000000)
    (reload/sync-new-tenants! system)
    (write! file
            [{:name "acme" :onboarding "/repos/acme"}
             {:name "globex" :onboarding "/repos/globex"}
             {:name "initech" :onboarding "/repos/initech"}]
            2000000)
    (with-redefs [api/sync-tenant! (fn [_ tenant-name]
                                     (when (= "globex" tenant-name)
                                       (throw (ex-info "edd is down" {})))
                                     (swap! synced conj tenant-name))]
      (is
       (= ["globex" "initech"]
          (reload/sync-new-tenants! system)))
      (is
       (= ["initech"]
          @synced)))))

(deftest only-operators-reload-configuration-now
  (let [file
        (tenants-file)

        settings
        (settings-for file)

        system
        {:settings settings
         :platform/configured-tenants (atom nil)}

        call
        (fn [identity]
          (let [token
                (when identity (auth/login-token settings identity))]
            (reload/handler system {:cookies (if token {"oc_auth" token} {}) :headers {}})))]
    (write! file [{:name "acme" :onboarding "/repos/acme"}] 1000000)
    (is
     (= 401
        (:status (ex-data (try (call nil) (catch Exception e e))))))
    (is
     (= 403
        (:status (ex-data (try (call {:sub "alice" :tenant "acme" :teams {} :tenant-admin true})
                               (catch Exception e e))))))
    (let [response
          (with-redefs [api/sync-tenant! (fn [_ _] nil)]
            (call {:sub "oscar" :operator true :teams {}}))]
      (is
       (= 200
          (:status response)))
      (is
       (= {:claims-parser {:status "reloaded"} :tenants {:status "reloaded"} :identity-provider nil :new-tenants []}
          (json/read-value (:body response) json/keyword-keys-object-mapper))))))

(deftest tenant-names-cant-name-a-page-of-the-ui
  (doseq [reserved ["login" "setup" "platform" "api" "runs" "tenants" "hooks" "healthz" "metrics"]]
    (is
     (= [(str "name " reserved " names a page of the UI (/" reserved "); choose another name. Reserved: "
              "accounts, activities, admin, api, harnesses, healthz, hooks, login, metrics, platform, runs, sessions, "
              "setup, teams, tenants")]
        (:errors (tenants/tenant {:name reserved :onboarding "/repos/x"})))))
  (is
   (= []
      (:errors (tenants/tenant {:name "acme" :onboarding "/repos/x"})))))
