(ns manager-svc.sessions.routing-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [manager-svc.auth :as auth]
            [manager-svc.sessions.fixtures :as fixtures]
            [manager-svc.sessions.links :as links]
            [manager-svc.sessions.routing :as routing]
            [yaml.core :as yaml]))

(def session-id #uuid "11111111-1111-1111-1111-111111111111")

(defn- settings
  []
  (assoc (:settings (fixtures/system))
         :domain "example.test"
         :manager-service-url "http://manager:8000"
         :traefik-dynamic-file (str (fixtures/temp-dir) "/dynamic/sessions.yml")))

(deftest traefik-dynamic-file
  (let [system
        (fixtures/system)

        settings
        (settings)

        provider
        (routing/create settings (:sessions/backend system))]
    (routing/sync-routes! provider [{:id session-id}])
    (let [config
          (yaml/parse-string (slurp (:traefik-dynamic-file settings)) :keywords false)]
      (is
       (= {"rule" "Host(`example.test`)" "entryPoints" ["web"] "priority" 1000 "service" "manager"}
          (get-in config ["http" "routers" "manager"])))
      (is
       (= "HostRegexp(`^[a-z0-9-]+\\.example\\.test$`)"
          (get-in config ["http" "routers" "wake" "rule"])))
      (is
       (= {"rule" (str "Host(`" session-id ".example.test`)")
           "entryPoints" ["web"]
           "priority" 500
           "middlewares" ["opencode-forward-auth" "opencode-strip-cookie"]
           "service" (str "session-" session-id)}
          (get-in config ["http" "routers" (str "session-" session-id)])))
      (is
       (= [{"url" (str "http://opencode-" session-id ":4096")}]
          (get-in config ["http" "services" (str "session-" session-id) "loadBalancer" "servers"])))
      (is
       (= {"forwardAuth" {"address" "http://manager:8000/api/gateway/auth"
                          "authResponseHeaders" ["Authorization"]
                          "addAuthCookiesToResponse" ["oc_session"]}}
          (get-in config ["http" "middlewares" "opencode-forward-auth"])))
      (testing "the sandbox never receives the browser's cookies"
        (is
         (= {"headers" {"customRequestHeaders" {"Cookie" ""}}}
            (get-in config ["http" "middlewares" "opencode-strip-cookie"])))))
    (testing "removed sessions lose their routes"
      (routing/sync-routes! provider [])
      (is
       (= ["manager" "wake"]
          (sort (keys (get-in (yaml/parse-string (slurp (:traefik-dynamic-file settings)) :keywords false)
                              ["http" "routers"]))))))
    (testing "the file is rewritten when it disappears"
      (io/delete-file (:traefik-dynamic-file settings))
      (routing/sync-routes! provider [])
      (is
       (.exists (io/file (:traefik-dynamic-file settings)))))))

(deftest gateway-api-route
  (let [route
        (routing/http-route (assoc (settings) :gateway-namespace "traefik" :gateway-name "gw") {:id session-id})]
    (is
     (= {"name" (str "opencode-" session-id)
         "labels" {"opencode-manager/session" (str session-id)}}
        (get route "metadata")))
    (is
     (= {"parentRefs" [{"name" "gw" "namespace" "traefik"}]
         "hostnames" [(str session-id ".example.test")]}
        (select-keys (get route "spec") ["parentRefs" "hostnames"])))
    (is
     (= [{"name" (str "opencode-" session-id) "port" 4096}]
        (get-in route ["spec" "rules" 0 "backendRefs"])))
    (is
     (= {"type" "RequestHeaderModifier" "requestHeaderModifier" {"remove" ["Cookie"]}}
        (get-in route ["spec" "rules" 0 "filters" 1])))))

(deftest https-session-cookies-are-host-only
  (is
   (= ["__Host-oc_session"]
      (get (routing/forward-auth-config (assoc (settings) :public-scheme "https")) "addAuthCookiesToResponse"))))

(deftest session-hosts-and-keys
  (let [settings
        (settings)]
    (is
     (= session-id
        (links/session-id-from-host settings (str session-id ".Example.test:8000"))))
    (is
     (nil?
      (links/session-id-from-host settings "example.test")))
    (is
     (nil?
      (links/session-id-from-host settings "foo.example.test")))
    (is
     (= session-id
        (auth/derived-key-owner settings (links/secret-key settings session-id) #{links/password-purpose})))
    (is
     (nil?
      (auth/derived-key-owner settings (str session-id ".0000") #{links/password-purpose})))
    (is
     (= "/L3Nlc3Npb24vd29ya3NwYWNl"
        (links/open-path {:labels {}})))))
