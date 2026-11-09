(ns manager-svc.login-test
  "Logging in and out on the public listener, and calls without a login."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [manager-svc.auth :as auth]
            [manager-svc.edge.api :as edge-api]
            [manager-svc.edge.server :as server]
            [manager-svc.test-support :as support])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(defn- call
  [settings method path & {:keys [body cookies]}]
  (let [request
        {:method method
         :path path
         :headers {}
         :cookies (or cookies {})
         :body (ByteArrayInputStream. (.getBytes (str body) StandardCharsets/UTF_8))}]
    ((:handler (server/route edge-api/routes request)) {:settings settings} request)))

(defn- cookie-value
  [set-cookie]
  (second (re-find #"^oc_auth=([^;]*)" (str set-cookie))))

(deftest logging-in-sets-an-http-only-login-cookie
  (let [response
        (call support/settings :post "/api/auth/login" :body "{\"username\":\" Bob \"}")

        set-cookie
        (get-in response [:headers "Set-Cookie"])]
    (is
     (= 200
        (:status response)))
    (is
     (every? #(string/includes? set-cookie %) ["HttpOnly" "SameSite=Lax" "Path=/"]))
    (testing "the cookie names the person, normalised"
      (is
       (= "bob"
          (:sub (auth/person support/settings (cookie-value set-cookie))))))))

(deftest logging-in-without-a-name-is-refused
  (is
   (= 422
      (:status (call support/settings :post "/api/auth/login" :body "{\"username\":\"\"}")))))

(deftest the-identity-provider-handles-login-in-jwt-mode
  (is
   (= 404
      (:status (call (assoc support/settings :auth-mode "jwt") :post "/api/auth/login" :body "{\"username\":\"bob\"}")))))

(deftest logging-out-clears-the-cookie
  (let [response
        (call support/settings :post "/api/auth/logout")]
    (is
     (= [204 "" true]
        [(:status response)
         (cookie-value (get-in response [:headers "Set-Cookie"]))
         (string/includes? (get-in response [:headers "Set-Cookie"]) "Max-Age=0")]))))

(deftest queries-and-commands-need-a-login
  (is
   (= {"/api/query" 401 "/api/command" 401}
      (into {} (for [path ["/api/query" "/api/command"]]
                 [path (:status (call support/settings :post path :body "{\"query\":{\"query-id\":\"get-me\"}}"))]))))
  (testing "a forged cookie counts as none"
    (is
     (= 401
        (:status (call support/settings :post "/api/query"
                       :cookies {"oc_auth" "forged.token.value"}
                       :body "{\"query\":{\"query-id\":\"get-me\"}}"))))))

(deftest a-login-the-claims-parser-refuses-says-why
  (let [response
        (call support/settings :post "/api/auth/login" :body "{\"username\":\"nobody\"}")]
    (is
     (= 403
        (:status response)))
    (is
     (string/includes? (:body response) "belongs to no tenant"))
    (is
     (nil? (get-in response [:headers "Set-Cookie"])))))

(deftest personas-are-the-dev-users-with-their-parsed-roles
  (let [response
        (call support/settings :get "/api/auth/personas")

        personas
        (into {} (map (juxt :username identity)) (server/read-json {:body (ByteArrayInputStream. (.getBytes (str (:body response)) StandardCharsets/UTF_8))}))]
    (is
     (= 200
        (:status response)))
    (is
     (= {:username "alice" :tenant "acme" :roles ["Tenant admin" "Team admin · alpha"]}
        (get personas "alice")))
    (is
     (= {:username "oscar" :tenant nil :roles ["Operator"]}
        (get personas "oscar")))
    (is
     (= ["Login refused: your account belongs to no tenant"]
        (:roles (get personas "nobody"))))))
