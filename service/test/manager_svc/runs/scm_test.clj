(ns manager-svc.runs.scm-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [manager-svc.http :as http]
            [manager-svc.runs.scm :as scm])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util Base64]))

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

(defn- gerrit-server
  "A fake Gerrit on a free local port answering (respond method path-and-query
   body) → [status text]; returns {:url :requests :stop}."
  [respond]
  (let [requests
        (atom [])

        server
        (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [method
                              (.getRequestMethod exchange)

                              target
                              (str (.getRawPath (.getRequestURI exchange))
                                   (when-let [q (.getRawQuery (.getRequestURI exchange))] (str "?" q)))

                              body
                              (slurp (.getRequestBody exchange))

                              [status text]
                              (respond method target body)

                              bytes
                              (.getBytes ^String text StandardCharsets/UTF_8)]
                          (swap! requests conj {:method method
                                                :target target
                                                :authorization (.getFirst (.getRequestHeaders exchange) "Authorization")
                                                :body (http/read-json body)})
                          (.sendResponseHeaders exchange status (alength bytes))
                          (with-open [out (.getResponseBody exchange)]
                            (.write out bytes))))))
    (.start server)
    {:url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
     :requests requests
     :stop #(.stop server 0)}))

(defn- change-json
  [number patch-set]
  {:_number number
   :project "demo-service"
   :branch "main"
   :change_id "I0123"
   :subject (str "Change " number)
   :owner {:name "Dave" :username "dave"}
   :current_revision (str "commit-" number "-" patch-set)
   :revisions {(keyword (str "commit-" number "-" patch-set))
               {:_number patch-set
                :ref (str "refs/changes/0" number "/" number "/" patch-set)}}})

(defn- xssi
  [value]
  (str ")]}'\n" (http/write-json value)))

(deftest gerrit-changes-are-pull-requests
  (let [patch
        "diff --git a/demo/pricing.py b/demo/pricing.py\n+++ b/demo/pricing.py\n@@ -1,1 +1,2 @@\n x\n+y\n"

        pushes
        (atom [])

        {:keys [url requests stop]}
        (gerrit-server
         (fn [method target _body]
           (cond
             (string/starts-with? target "/a/changes/?q=status%3Aopen%20project%3Ademo-service&n=100&S=0")
             [200 (xssi [(change-json 1 1) (assoc (change-json 2 3) :_more_changes true)])]

             (string/starts-with? target "/a/changes/?q=status%3Aopen%20project%3Ademo-service&n=100&S=2")
             [200 (xssi [(change-json 3 1)])]

             (string/starts-with? target "/a/changes/?q=change%3AIabc")
             [200 (xssi [(change-json 7 1)])]

             (= "/a/changes/demo-service~2/revisions/current/patch" target)
             [200 (.encodeToString (Base64/getEncoder) (.getBytes ^String patch StandardCharsets/UTF_8))]

             (string/starts-with? target "/a/changes/demo-service~7?")
             [200 (xssi (change-json 7 2))]

             (and (= "POST" method) (= "/a/changes/demo-service~2/revisions/current/review" target))
             [200 (xssi {:labels {:Code-Review -1}})]

             :else
             [404 "Not found"])))

        provider
        (scm/gerrit {:url url :web-url "http://gerrit.localhost:8080/"} {:username "platform" :secret "pw"})

        push!
        (fn [options]
          (swap! pushes conj options)
          "pushed-commit")]
    (try
      (testing "open changes are listed through every page, XSSI prefix and all"
        (let [changes
              ((:open-pull-requests provider) "demo-service")]
          (is
           (= [1 2 3]
              (map :id changes)))
          (is
           (= {:id 2
               :title "Change 2"
               :author "Dave"
               :source-branch "refs/changes/02/2/3"
               :source-commit "commit-2-3"
               :destination-branch "main"
               :change-id "I0123"
               :url "http://gerrit.localhost:8080/c/demo-service/+/2"
               :clone-url (str url "/a/demo-service")}
              (second changes)))))
      (testing "requests authenticate with the HTTP password"
        (is
         (= (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes "platform:pw" StandardCharsets/UTF_8)))
            (:authorization (first @requests)))))
      (testing "the diff is the latest patch set's patch"
        (is
         (= patch
            ((:diff provider) "demo-service" 2))))
      (testing "a review is one post: summary, inline comments and the vote"
        (is
         (= 2
            (scm/post-review! provider "demo-service" 2
                              {:verdict "REQUEST_CHANGES"
                               :comments [{:key "summary" :content "## Automated review"}
                                          {:key "finding-0" :content "Off by one" :path "demo/pricing.py" :line 2}
                                          {:key "finding-1" :content "Posted before" :path "demo/pricing.py" :line 1}]}
                              #{"finding-1"}
                              (fn [_ _]))))
        (is
         (= {:message "## Automated review"
             :comments {:demo/pricing.py [{:line 2 :message "Off by one"}]}
             :labels {:Code-Review -1}}
            (:body (last @requests)))))
      (testing "a new change is pushed for review with a Change-Id derived from its key"
        (with-redefs [scm/change-id (fn [_] "Iabc")]
          (let [pr
                (scm/open-change! provider "demo-service"
                                  {:key "run:1" :branch "agent/1" :target "main" :title "Fix" :description "Body"}
                                  push!)]
            (is
             (= {:ref "refs/for/main" :trailer "Change-Id: Iabc" :message "Fix\n\nBody" :amend false}
                (last @pushes)))
            (is
             (= [7 "pushed-commit" "refs/changes/07/7/1"]
                ((juxt :id :commit :source-branch) pr))))))
      (testing "continuing a change amends it into a new patch set"
        (let [pr
              (scm/update-change! provider "demo-service"
                                  {:id 7 :destination-branch "main" :change-id "I0123"}
                                  push!)]
          (is
           (= {:ref "refs/for/main" :trailer "Change-Id: I0123" :message nil :amend true}
              (last @pushes)))
          (is
           (= "refs/changes/07/7/2"
              (:source-branch pr)))))
      (finally
        (stop)))))

(deftest change-ids-are-stable-per-key
  (is
   (re-matches #"I[0-9a-f]{40}" (scm/change-id "run:1")))
  (is
   (= (scm/change-id "run:1")
      (scm/change-id "run:1")))
  (is
   (not= (scm/change-id "run:1")
         (scm/change-id "run:2"))))

(deftest hosted-providers-comment-one-by-one-and-push-branches
  (let [calls
        (atom [])

        provider
        {:comment (fn [_ _ content _ _]
                    (swap! calls conj [:comment content])
                    (count @calls))
         :create-pull-request (fn [_ source destination _ _ draft]
                                (swap! calls conj [:create source destination draft])
                                {:id 4 :source-branch source})}]
    (is
     (= 1
        (scm/post-review! provider "acme/app" 4 {:comments [{:key "summary" :content "S"}]} #{} (fn [_ _]))))
    (is
     (= {:id 4 :source-branch "agent/1" :commit "c1"}
        (scm/open-change! provider "acme/app" {:branch "agent/1" :target "main" :draft true}
                          (fn [{:keys [ref]}]
                            (swap! calls conj [:push ref])
                            "c1"))))
    (is
     (= [[:comment "S"] [:push "refs/heads/agent/1"] [:create "agent/1" "main" true]]
        @calls))))
