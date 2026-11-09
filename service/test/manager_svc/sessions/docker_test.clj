(ns manager-svc.sessions.docker-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.docker :as docker]
            [manager-svc.sessions.fixtures :as fixtures]
            [manager-svc.sessions.unix-http :as http]))

(def session-id #uuid "11111111-1111-1111-1111-111111111111")

(defn- fake-docker
  [calls]
  (fn [_socket method path & [_data options]]
    (swap! calls conj [method (first (string/split path #"\?")) options])
    (cond
      (string/ends-with? path "/json") {:status 404 :body ""}
      (string/starts-with? path "/images/create") {:status 200 :body ""}
      :else {:status 201 :body ""})))

(deftest starting-never-pulls
  (let [calls
        (atom [])

        docker
        (docker/create (:settings (fixtures/system)))]
    (with-redefs [http/request (fake-docker calls)]
      (backend/start! docker {:id session-id :sub "bob"} fixtures/sandbox))
    (is
     (= [[:get (str "/containers/opencode-" session-id "/json")]
         [:post "/containers/create"]
         [:post (str "/containers/opencode-" session-id "/start")]]
        (mapv #(vec (take 2 %)) @calls)))))

(deftest pulling-may-take-ten-minutes
  (let [calls
        (atom [])

        docker
        (docker/create (:settings (fixtures/system)))]
    (with-redefs [http/request (fake-docker calls)]
      (is
       (false?
        (backend/image-ready? docker "opencode:test")))
      (backend/pull-image! docker "opencode:test"))
    (is
     (= [:post "/images/create" {:timeout-ms docker/pull-timeout-ms}]
        (last @calls)))))
