(ns manager-web.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [manager-web.api :as api]))

(deftest edd-requests-go-to-query-and-command
  (let [{:keys [method path edd?]}
        (api/request-of {:query {:query-id :get-me}})]
    (is
     (= ["POST" "/api/query" true]
        [method path edd?])))
  (is
   (= "/api/command"
      (:path (api/request-of {:command {:cmd-id :start-session}})))))

(deftest edge-requests-send-plain-json
  (let [{:keys [method path payload edd?]}
        (api/request-of {:method "PUT" :path "/api/credentials/x" :body {:secret ":not-a-keyword"} :edd? true})]
    (is
     (= ["PUT" "/api/credentials/x" true]
        [method path edd?]))
    (is
     (= "{\"secret\":\":not-a-keyword\"}"
        payload)))
  (is
   (= {:method "GET" :path "/api/auth/personas" :payload nil :edd? false}
      (api/request-of {:path "/api/auth/personas"}))))

(deftest edd-results
  (is
   (= [:ok {:a 1}]
      (api/edd-result {:result {:a 1}})))
  (is
   (= [:error "Nope"]
      (api/edd-result {:result nil :error "Nope"})))
  (is
   (= [:error "Bad"]
      (api/edd-result {:exception {:message "Bad"}}))))

(deftest replies-keep-text-that-only-looks-like-a-custom-field
  (is
   (= {:status :running
       :id #uuid "11111111-1111-1111-1111-111111111111"
       :document {:markdown "# Requirements\n\nA heading opens the document."}
       :note "#42 is the change"
       :time "12:30"
       :label ": a colon first"}
      (api/read-fields {:status ":running"
                        :id "#11111111-1111-1111-1111-111111111111"
                        :document {:markdown "# Requirements\n\nA heading opens the document."}
                        :note "#42 is the change"
                        :time "12:30"
                        :label ": a colon first"}))))

(deftest http-errors-say-what-happened
  (is
   (= "Invalid username"
      (api/http-error 422 {:detail "Invalid username"})))
  (is
   (= "Request failed (HTTP 502)"
      (api/http-error 502 nil))))

(deftest signing-out-drops-everything-of-the-app
  (is
   (= {:edd.db/active-panel :runs
       :manager-web.session/status :anonymous}
      (api/signed-out {:edd.db/active-panel :runs
                       :manager-web.data/loads {:runs {:data [1]}}
                       :manager-web.data/busy {[:x] true}
                       :manager-web.session/me {:sub "bob"}
                       :manager-web.session/status :ready
                       :manager-web.components.confirm/request {:title "x"}}))))

(deftest state-changing-requests-carry-the-csrf-header
  (is
   (= {"X-Requested-With" "manager-web" "Content-Type" "application/json"}
      (api/headers "POST" "{}")))
  (is
   (= {"X-Requested-With" "manager-web"}
      (api/headers "POST" nil)))
  (is
   (= {}
      (api/headers "GET" nil))))

(deftest every-state-changing-call-of-the-ui-carries-the-csrf-header
  (doseq [call [{:command {:cmd-id :stop-session}}
                {:query {:query-id :get-me}}
                {:method "PUT" :path "/api/credentials/JIRA_TOKEN" :body {:secret "s"}}
                {:method "PUT" :path "/api/admin/credentials/JIRA_TOKEN" :body {:secret "s"}}
                {:method "POST" :path "/api/auth/login" :body {:username "bob"}}
                {:method "POST" :path "/api/auth/logout"}
                {:method "DELETE" :path "/api/anything"}]
          :let [{:keys [method payload]}
                (api/request-of call)]]
    (is
     (= "manager-web"
        (get (api/headers method payload) "X-Requested-With")))))

(deftest reading-calls-carry-no-csrf-header
  (let [{:keys [method payload]}
        (api/request-of {:path "/api/auth/personas"})]
    (is
     (nil?
      (get (api/headers method payload) "X-Requested-With")))))

(deftest responses-lead-to-their-events
  (is
   (= [::api/unauthenticated]
      (api/response-event 401 nil {:on-failure [:failed]} false)))
  (is
   (= [:loaded []]
      (api/response-event 404 {:detail "Not found"} {:on-failure [:failed] :on-not-found [:loaded []]} false)))
  (is
   (= [:failed "Not found"]
      (api/response-event 404 {:detail "Not found"} {:on-failure [:failed]} false)))
  (is
   (= [:loaded [{:username "bob"}]]
      (api/response-event 200 [{:username "bob"}] {:on-success [:loaded]} false)))
  (is
   (= [:done {:a 1}]
      (api/response-event 200 {:result {:a 1}} {:on-success [:done]} true)))
  (is
   (= [::api/show-error "Bad"]
      (api/response-event 200 {:exception {:message "Bad"}} {} true)))
  (is
   (nil?
    (api/response-event 200 {:result {:a 1}} {} true))))

(deftest a-401-sends-people-to-log-in
  (let [db
        {:edd.db/active-panel :runs
         :manager-web.data/busy {:x true}
         :manager-web.session/me {:sub "bob"}}]
    (testing "from a page: everything kept for the person goes"
      (let [effects
            (api/unauthenticated-effects db "/acme/runs" "?team=ops")]
        (is
         (= {:edd.db/active-panel :runs :manager-web.session/status :anonymous}
            (:db effects)))
        (is
         (= [[:dispatch [:edd.events/navigate {:url "/login?next=%2Facme%2Fruns%3Fteam%3Dops" :replace? true}]]]
            (:fx effects)))))
    (testing "on the login page nothing is left running"
      (let [effects
            (api/unauthenticated-effects db "/login" "?next=%2Facme")]
        (is
         (= [:anonymous nil]
            [(get-in effects [:db :manager-web.session/status]) (get-in effects [:db :manager-web.data/busy])]))
        (is
         (= [nil]
            (:fx effects)))))
    (testing "a provider login that didn't complete is not tried again"
      (is
       (= [[:dispatch [:edd.events/navigate {:url "/login?next=%2Facme" :replace? true}]]]
          (:fx (api/unauthenticated-effects db "/login" "?next=%2Facme&complete=1"))))
      (is
       (= [[:dispatch [:edd.events/navigate {:url "/login" :replace? true}]]]
          (:fx (api/unauthenticated-effects db "/login" "?complete=1")))))))

(deftest a-refused-edge-call-shows-its-detail
  (is
   (= [:failed "Workflow review belongs to project api, not web"]
      (api/response-event 422 {:detail "Workflow review belongs to project api, not web"} {:on-failure [:failed]} true)))
  (is
   (= [:failed "Only team admins set team credentials"]
      (api/response-event 403 {:detail "Only team admins set team credentials"} {:on-failure [:failed]} true)))
  (is
   (= [:failed "Request failed (HTTP 502)"]
      (api/response-event 502 nil {:on-failure [:failed]} true)))
  (is
   (= "Not allowed"
      (api/http-error 403 {:exception {:status 403 :message "Not allowed"}}))))

