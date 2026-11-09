(ns manager-svc.runs.scm-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.http :as http]
            [manager-svc.runs.scm :as scm]))

(deftest the-token-never-leaves-the-provider
  (let [requests
        (atom [])

        responses
        {"https://api.bitbucket.org/2.0/repositories/acme/app/pullrequests/1/diff"
         {:status 302 :headers {"location" "/2.0/repositories/acme/app/diff/abc"}}
         "https://api.bitbucket.org/2.0/repositories/acme/app/diff/abc"
         {:status 200 :headers {} :body "diff"}
         "https://api.bitbucket.org/2.0/repositories/acme/app/pullrequests/2/diff"
         {:status 302 :headers {"location" "https://evil.example/steal"}}
         "https://api.bitbucket.org/2.0/repositories/acme/app/pullrequests?state=OPEN&pagelen=50"
         {:status 200 :headers {} :body "{\"values\":[],\"next\":\"https://evil.example/page2\"}"}}]
    (with-redefs [http/request! (fn [request]
                                  (swap! requests conj (:url request))
                                  (get responses (:url request)))]
      (let [provider
            (scm/bitbucket "token")]
        (testing "a redirect on the provider's host is followed"
          (is
           (= "diff"
              ((:diff provider) "acme/app" 1))))
        (testing "a redirect or pagination link to another host is refused"
          (is
           (thrown-with-msg? clojure.lang.ExceptionInfo #"Refusing to follow"
                             ((:diff provider) "acme/app" 2)))
          (is
           (thrown-with-msg? clojure.lang.ExceptionInfo #"Refusing to follow"
                             ((:open-pull-requests provider) "acme/app")))
          (is
           (not-any? #(.contains ^String % "evil") @requests)))))))
