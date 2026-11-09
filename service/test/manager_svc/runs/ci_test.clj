(ns manager-svc.runs.ci-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-svc.credentials.api :as credentials]
            [manager-svc.runs.ci :as ci]
            [manager-svc.runs.fixtures :as f]))

(deftest builds-are-read-through-the-shared-mcp-client
  (f/with-fakes [_]
    (let [calls
          (atom [])

          run
          {:created-by "alice" :result {:pull-request {:pushed-at "2026-01-01T00:00:00Z"}
                                        :ci {:job "app/PR-1"}}}

          ci
          {:mcp-server "jenkins" :job "app/PR-{pr}"}]
      (with-redefs [credentials/call-mcp-tool (fn [server credential tool arguments timeout]
                                                (swap! calls conj [(:url server) credential tool arguments timeout])
                                                {:building true :started 1767225600000})]
        (is
         (= "running"
            (:state (ci/poll {:settings {}} run ci))))
        (is
         (= [["http://jenkins/mcp" nil "get_build" {:job "app/PR-1" :number "lastBuild"} 30]]
            @calls))))))

(deftest failed-tools-leave-the-build-waiting
  (f/with-fakes [_]
    (with-redefs [credentials/call-mcp-tool (fn [& _] (throw (ex-info "job not found" {:mcp/tool-failed true})))
                  credentials/mcp-tool-failed? (fn [e] (true? (:mcp/tool-failed (ex-data e))))]
      (is
       (= {:state "waiting" :detail "The build job doesn't exist yet"}
          (ci/poll {:settings {}} {:result {:ci {:job "j"}}} {:mcp-server "jenkins"}))))
    (testing "an unknown server is a failed tool too"
      (is
       (= "waiting"
          (:state (ci/poll {:settings {}} {:result {:ci {:job "j"}}} {:mcp-server "nope"})))))))

(deftest follow-ups-of-a-persons-run-act-for-that-person
  (with-redefs [ci/tool (fn [& _] "log")]
    (is
     (= ["alice" "alice"]
        ((juxt :person :created-by)
         (ci/failure-run {} {:team-id "alpha" :created-by "alice" :platform-started false
                             :result {:ci {:job "j" :build {:number 2}} :pull-request {:id 1}}}
                         {:on-failure "analyse"} {:activity "analysis"}))))
    (is
     (= [nil "platform:pull_request"]
        ((juxt :person :created-by)
         (ci/failure-run {} {:team-id "alpha" :created-by "platform:pull_request" :platform-started true
                             :result {:ci {:job "j" :build {:number 2}} :pull-request {:id 1}}}
                         {:on-failure "analyse"} {:activity "analysis"}))))))

(deftest ci-times-out-and-polls-on-its-interval
  (let [run
        {:result {:ci {:started-at "2026-01-01T00:00:00Z" :checked-at "2026-01-01T00:00:00Z"}}}]
    (is
     (false? (ci/timed-out? run {:timeout-minutes 10} "2026-01-01T00:10:00Z")))
    (is
     (true? (ci/timed-out? run {:timeout-minutes 10} "2026-01-01T00:10:01Z")))
    (is
     (false? (ci/poll-due? run "2026-01-01T00:00:59Z")))
    (is
     (true? (ci/poll-due? run "2026-01-01T00:01:00Z")))))

(deftest a-shared-job-is-followed-by-the-build-of-the-pushed-commit
  (let [run
        {:result {:pull-request {:id 7 :commit "abcdef1234567890" :pushed-at "2026-01-01T00:00:00Z"}
                  :ci {:job "demo-service-verify"}}}

        ci
        {:mcp-server "jenkins" :job "demo-service-verify"}

        poll-with
        (fn [builds build]
          (let [calls
                (atom [])]
            (with-redefs [ci/tool (fn [_ _ _ tool arguments]
                                    (swap! calls conj [tool arguments])
                                    (if (= "get_builds" tool) builds build))]
              [(ci/poll {} run ci) @calls])))]
    (testing "no build names the commit yet"
      (is
       (= [{:state "waiting" :detail "No build of the pushed change yet"}
           [["get_builds" {:job "demo-service-verify" :limit 20}]]]
          (poll-with [{:number 3 :description "change 6,1 0000000000001111"}] nil))))
    (testing "the matching build decides, whenever it started"
      (let [[state calls]
            (poll-with [{:number 4 :description "change 7,1 abcdef1234567890"}
                        {:number 3 :description "change 6,1 0000000000001111"}]
                       {:number 4 :building false :result "SUCCESS" :started 0})]
        (is
         (= "passed"
            (:state state)))
        (is
         (= ["get_build" {:job "demo-service-verify" :number "4"}]
            (second calls)))))
    (testing "running and failed builds"
      (is
       (= "running"
          (:state (first (poll-with [{:number 4 :description "abcdef123456"}] {:number 4 :building true})))))
      (is
       (= "failed"
          (:state (first (poll-with [{:number 4 :description "abcdef123456"}] {:number 4 :building false :result "FAILURE"}))))))))

(deftest builds-are-matched-by-the-commits-first-twelve-characters
  (is
   (= 2
      (:number (ci/build-of-commit [{:number 2 :description "change 1,2 abcdef123456ffff"}] "abcdef123456"))))
  (is
   (nil? (ci/build-of-commit [{:number 2 :description nil}] "abcdef123456")))
  (is
   (nil? (ci/build-of-commit [{:number 2 :description "anything"}] nil))))
