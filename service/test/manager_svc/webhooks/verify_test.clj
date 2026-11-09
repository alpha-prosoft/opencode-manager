(ns manager-svc.webhooks.verify-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.webhooks.verify :as verify])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def body
  (.getBytes "{\"ref\":\"main\"}" StandardCharsets/UTF_8))

(def now
  1767225600)

(defn- check
  [auth secret headers]
  (verify/check auth {:secret secret :username (:username auth)} {:headers headers :body body} now))

(defn- basic
  [user password]
  (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes (str user ":" password) StandardCharsets/UTF_8))))

(deftest constant-time-comparison
  (is
   (verify/equal? "abc" "abc"))
  (is
   (not (verify/equal? "abc" "abd")))
  (is
   (not (verify/equal? "abc" "abcd")))
  (is
   (not (verify/equal? nil "abc"))))

(deftest auth-types
  (testing "none lets everyone in"
    (is
     (:ok (check {:type :none} nil {}))))
  (testing "header auth compares the named header"
    (is
     (:ok (check {:type :header :header-name "X-Token"} "secret-1" {"x-token" "secret-1"})))
    (is
     (= {:ok false :status 401 :reason "Header X-Token is missing or wrong"}
        (check {:type :header :header-name "X-Token"} "secret-1" {"x-token" "secret-2"}))))
  (testing "bearer"
    (is
     (:ok (check {:type :bearer} "tok-12345" {"authorization" "Bearer tok-12345"})))
    (is
     (not (:ok (check {:type :bearer} "tok-12345" {"authorization" "Basic tok-12345"})))))
  (testing "basic checks username and password"
    (is
     (:ok (check {:type :basic :username "ci"} "pw-123456" {"authorization" (basic "ci" "pw-123456")})))
    (is
     (not (:ok (check {:type :basic :username "ci"} "pw-123456" {"authorization" (basic "cd" "pw-123456")}))))
    (is
     (not (:ok (check {:type :basic :username "ci"} "pw-123456" {"authorization" "Basic !!!"})))))
  (testing "a secret type without a stored secret refuses everything"
    (is
     (= "The webhook has no secret"
        (:reason (check {:type :bearer} nil {"authorization" "Bearer "}))))))

(deftest hmac-signatures
  (let [secret
        "It's a Secret to Everybody"

        github-body
        (.getBytes "Hello, World!" StandardCharsets/UTF_8)]
    (testing "GitHub's documented example"
      (is
       (= "757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"
          (verify/signature :sha256 secret github-body))))
    (is
     (:ok (verify/check {:type :hmac :header-name "X-Hub-Signature-256"} {:secret secret}
                        {:headers {"x-hub-signature-256" "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"}
                         :body github-body}
                        now)))
    (testing "Gitea's signature has no prefix"
      (is
       (:ok (verify/check {:type :hmac :header-name "X-Gitea-Signature"} {:secret secret}
                          {:headers {"x-gitea-signature" "757107EA0EB2509FC211221CCE984B8A37570B6D7586C22C46F4379C8B043E17"}
                           :body github-body}
                          now))))
    (testing "a changed body fails"
      (is
       (not (:ok (verify/check {:type :hmac :header-name "X-Hub-Signature-256"} {:secret secret}
                               {:headers {"x-hub-signature-256" "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"}
                                :body body}
                               now)))))))

(deftest hmac-timestamps-bound-replays
  (let [auth
        {:type :hmac :header-name "X-Signature" :timestamp-header "X-Timestamp" :tolerance-seconds 300}

        headers-at
        (fn [stamp]
          (into {} (map (fn [[k v]] [(.toLowerCase ^String k) v]))
                (verify/sign-headers auth {:secret "hmac-secret"} body stamp)))]
    (is
     (:ok (check auth "hmac-secret" (headers-at now))))
    (is
     (:ok (check auth "hmac-secret" (headers-at (- now 299)))))
    (is
     (= "Timestamp is outside the 300 s window"
        (:reason (check auth "hmac-secret" (headers-at (- now 301))))))
    (is
     (= "Timestamp is outside the 300 s window"
        (:reason (check auth "hmac-secret" (headers-at (+ now 400))))))
    (testing "the timestamp is part of what is signed"
      (is
       (not (:ok (check auth "hmac-secret" (assoc (headers-at now) "x-timestamp" (str (dec now))))))))
    (is
     (= "Header X-Timestamp is missing or not unix seconds"
        (:reason (check auth "hmac-secret" (dissoc (headers-at now) "x-timestamp")))))))

(deftest signing-round-trips-every-type
  (doseq [auth [{:type :header :header-name "X-Token"}
                {:type :bearer}
                {:type :basic :username "ci"}
                {:type :hmac :header-name "X-Hub-Signature-256" :algorithm :sha1}]]
    (let [headers
          (into {} (map (fn [[k v]] [(.toLowerCase ^String k) v]))
                (verify/sign-headers auth {:secret "round-trip-1"} body now))]
      (is
       (:ok (check auth "round-trip-1" headers))
       (str (:type auth))))))

(deftest addresses
  (is
   (verify/ip-allowed? [] "203.0.113.9"))
  (is
   (verify/ip-allowed? ["203.0.113.0/24"] "203.0.113.9"))
  (is
   (not (verify/ip-allowed? ["203.0.113.0/24"] "203.0.114.9")))
  (is
   (verify/ip-allowed? ["10.1.2.3"] "10.1.2.3"))
  (is
   (verify/ip-allowed? ["2001:db8::/32"] "2001:db8:1::7"))
  (is
   (not (verify/ip-allowed? ["2001:db8::/32"] "203.0.113.9")))
  (is
   (not (verify/ip-allowed? ["203.0.113.0/24"] "example.com")))
  (is
   (= [true true false false]
      (mapv verify/valid-cidr? ["10.0.0.0/8" "::1" "10.0.0.0/33" "example.com"])))
  (testing "behind a trusted proxy, the address that proxy appended"
    (is
     (= "203.0.113.9"
        (verify/source-ip ["172.18.0.0/16"] "172.18.0.4" "198.51.100.1, 203.0.113.9")))
    (is
     (= "203.0.113.9"
        (verify/source-ip ["172.18.0.4"] "172.18.0.4" "198.51.100.1,203.0.113.9"))))
  (testing "nobody else's X-Forwarded-For is believed, however private their address"
    (is
     (= ["172.18.0.9" "172.18.0.4" "198.51.100.2" "127.0.0.1"]
        [(verify/source-ip ["172.18.0.4"] "172.18.0.9" "203.0.113.9")
         (verify/source-ip [] "172.18.0.4" "203.0.113.9")
         (verify/source-ip ["172.18.0.0/16"] "198.51.100.2" "203.0.113.9")
         (verify/source-ip nil "127.0.0.1" "203.0.113.9")])))
  (testing "a trusted proxy that forwards nothing is the caller"
    (is
     (= "172.18.0.4"
        (verify/source-ip ["172.18.0.0/16"] "172.18.0.4" nil)))))
