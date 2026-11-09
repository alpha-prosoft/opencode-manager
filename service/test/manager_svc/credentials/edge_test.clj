(ns manager-svc.credentials.edge-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [lambda.util :as util]
            [manager-svc.auth :as auth]
            [manager-svc.client :as client]
            [manager-svc.credentials.edge :as edge]
            [manager-svc.credentials.fixtures :as fixtures]
            [manager-svc.credentials.vault :as vault]
            [manager-svc.edge.server :as server]
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
   :cookies (if cookie {(auth/login-cookie fixtures/settings) cookie} {})
   :body (ByteArrayInputStream. (.getBytes ^String body StandardCharsets/UTF_8))})

(defn- send-to
  [path body cookie]
  (let [sent
        (atom nil)]
    (with-redefs [client/post-raw (fn [_ edd-path token text]
                                    (reset! sent {:path edd-path :token token :body text})
                                    {:status 200 :body "{\"result\":{\"success\":true}}"})
                  workers/kick! (fn [])]
      (let [request
            (put path body cookie)

            {:keys [handler]}
            (server/route edge/routes request)]
        {:response (handler system request)
         :sent @sent}))))

(deftest values-are-sealed-before-they-reach-edd
  (let [token
        (auth/login-token fixtures/settings (auth/identity-of fixtures/settings {:sub "alice"}))

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
        (vault/unseal fixtures/settings "jira" "alice" (get-in command [:attrs :sealed]))))
    (testing "the admin route seals for the platform"
      (let [{:keys [sent]}
            (send-to "/api/admin/credentials/llm-key" "{\"secret\":\"platform-value\"}" token)

            [command]
            (:commands (util/to-edn (:body sent)))]
        (is
         (= "set-platform-credential"
            (name (:cmd-id command))))
        (is
         (= "platform-value"
            (:secret (vault/unseal fixtures/settings "llm-key" vault/platform-owner (get-in command [:attrs :sealed])))))))))

(deftest anonymous-callers-are-refused
  (is
   (= 401
      (try
        (send-to "/api/credentials/jira" "{\"secret\":\"x\"}" nil)
        nil
        (catch clojure.lang.ExceptionInfo e
          (:status (ex-data e)))))))

(deftest edd-failures-reach-the-caller-without-internals
  (let [token
        (auth/login-token fixtures/settings (auth/identity-of fixtures/settings {:sub "alice"}))

        response
        (with-redefs [client/post-raw (fn [& _]
                                        {:status 200
                                         :body "{\"exception\":{\"message\":\"Unknown credential\",\"status\":404,\"request\":{\"secret\":1}}}"})
                      workers/kick! (fn [])]
          (let [request
                (put "/api/credentials/jira" "{\"secret\":\"value-1234567890\"}" token)]
            ((:handler (server/route edge/routes request)) system request)))]
    (is
     (= {:exception {:status 404 :message "Unknown credential"}}
        (util/to-edn (:body response))))))

(deftest only-plain-credential-ids-are-routed
  (is
   (nil?
    (server/route edge/routes (put "/api/credentials/a%2Fb" "{}" nil))))
  (is
   (nil?
    (server/route edge/routes (put "/api/credentials/../x" "{}" nil)))))
