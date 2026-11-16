(ns manager-svc.credentials.edge-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [lambda.util :as util]
            [manager-svc.client :as client]
            [manager-svc.cookies :as cookies]
            [manager-svc.credentials.edge :as edge]
            [manager-svc.credentials.fixtures :as fixtures]
            [manager-svc.credentials.vault :as vault]
            [manager-svc.edge.listener :as listener]
            [manager-svc.test-support :as support]
            [manager-svc.workers :as workers])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(def system
  {:settings fixtures/settings})

(defn- put
  [path body cookie]
  {:method :put
   :path path
   :headers {}
   :cookies (if cookie {(cookies/login-cookie fixtures/settings) cookie} {})
   :body (ByteArrayInputStream. (.getBytes ^String body StandardCharsets/UTF_8))})

(defn- send-to
  [path body cookie]
  (let [sent
        (atom nil)]
    (with-redefs [client/request-raw (fn [_settings edd-path token text]
                                       (reset! sent {:path edd-path :token token :body text})
                                       {:status 200 :body "{\"result\":{\"success\":true}}"})
                  workers/kick! (fn [])]
      {:response (listener/answer system {:routes edge/routes} (put path body cookie))
       :sent @sent})))

(deftest values-are-sealed-before-they-reach-edd
  (let [token
        (support/platform-token fixtures/settings {:sub "alice" :tenant "acme" :teams {"alpha" :admin}})

        {:keys [response sent]}
        (send-to "/api/credentials/jira" "{\"secret\":\"s3cr3t-value-123\",\"username\":\"alice\"}" token)

        [command]
        (:commands (util/to-edn (:body sent)))]
    (is
     (= 200
        (:status response)))
    (is
     (= ["/command" token]
        [(:path sent) (:token sent)]))
    (is
     (not (string/includes? (:body sent) "s3cr3t-value-123")))
    (is
     (= {:cmd-id "set-my-credential" :credential-id "jira"}
        {:cmd-id (name (:cmd-id command)) :credential-id (get-in command [:attrs :credential-id])}))
    (is
     (= {:secret "s3cr3t-value-123" :username "alice"}
        (vault/unseal fixtures/settings "jira" "user:acme/alice" (get-in command [:attrs :sealed]))))
    (testing "a value sealed for alice in acme opens for no one else"
      (is
       (thrown? Exception
                (vault/unseal fixtures/settings "jira" "user:globex/alice" (get-in command [:attrs :sealed])))))
    (testing "the admin route seals for the caller's tenant"
      (let [{:keys [sent]}
            (send-to "/api/admin/credentials/llm-key" "{\"secret\":\"tenant-value\"}" token)

            [command]
            (:commands (util/to-edn (:body sent)))]
        (is
         (= "set-tenant-credential"
            (name (:cmd-id command))))
        (is
         (= "tenant-value"
            (:secret (vault/unseal fixtures/settings "llm-key" "tenant:acme" (get-in command [:attrs :sealed])))))))
    (testing "the team route seals for the team of the caller's tenant"
      (let [{:keys [sent]}
            (send-to "/api/teams/alpha/credentials/bitbucket" "{\"secret\":\"team-value\"}" token)

            [command]
            (:commands (util/to-edn (:body sent)))]
        (is
         (= {:cmd-id "set-team-credential" :team-id "alpha" :credential-id "bitbucket"}
            {:cmd-id (name (:cmd-id command))
             :team-id (get-in command [:attrs :team-id])
             :credential-id (get-in command [:attrs :credential-id])}))
        (is
         (= "team-value"
            (:secret (vault/unseal fixtures/settings "bitbucket" "team:acme/alpha" (get-in command [:attrs :sealed])))))))))

(deftest anonymous-callers-are-refused
  (is
   (= 401
      (try
        (send-to "/api/credentials/jira" "{\"secret\":\"x\"}" nil)
        nil
        (catch clojure.lang.ExceptionInfo e
          (:status (ex-data e)))))))

(defn- failure
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(defn- alice-token
  []
  (support/platform-token fixtures/settings {:sub "alice" :tenant "acme" :teams {"alpha" :admin}}))

(deftest edd-failures-reach-the-caller-without-internals
  (is
   (= {:status 404 :message "Unknown credential"}
      (with-redefs [client/request-raw (fn [& _]
                                         {:status 200
                                          :body "{\"exception\":{\"message\":\"Unknown credential\",\"status\":404,\"request\":{\"secret\":1}}}"})
                    workers/kick! (fn [])]
        (let [request
              (put "/api/credentials/jira" "{\"secret\":\"value-1234567890\"}" (alice-token))]
          (failure #(listener/answer system {:routes edge/routes} request)))))))

(deftest a-body-that-is-no-json-object-is-refused-before-edd
  (doseq [body ["{\"secret\": \"unterminated" "[1,2]" "\"text\"" ""]]
    (is
     (= 422
        (:status (failure #(send-to "/api/credentials/jira" body (alice-token)))))
     body)))

(deftest only-plain-credential-ids-are-routed
  (is
   (nil?
    (listener/route edge/routes (put "/api/credentials/a%2Fb" "{}" nil))))
  (is
   (nil?
    (listener/route edge/routes (put "/api/credentials/../x" "{}" nil))))
  (is
   (nil?
    (listener/route edge/routes (put "/api/teams/Alpha%2F/credentials/x" "{}" nil)))))
