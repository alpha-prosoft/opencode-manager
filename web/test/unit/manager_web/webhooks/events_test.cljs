(ns manager-web.webhooks.events-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [manager-web.data :as data]
            [manager-web.routes :as routes]
            [manager-web.webhooks.events :as events]))

(def targets
  {:tenant "acme"
   :tenant-scope false
   :teams [{:id "demo" :name "Demo" :manage true
            :projects [{:id "demo-service" :name "Demo service"}]
            :workflows [{:id "requirements" :name "Requirements"
                         :inputs-schema {:properties {:brief {:title "Brief" :type "string" :description "What"}}
                                         :required ["brief"]}}]}
           {:id "ops" :name "Ops" :manage false :projects [] :workflows []}]})

(deftest a-new-form-starts-in-the-teams-scope-the-person-manages
  (let [form
        (events/new-form targets "demo" "demo-service")]
    (is
     (= [:project "demo" "demo-service" "demo" :header "X-Webhook-Token"]
        ((juxt :scope-kind :team-id :project-id :target-team-id :auth-type :header-name) form))))
  (is
   (= [:team "demo"]
      ((juxt :scope-kind :team-id) (events/new-form targets "ops" nil))))
  (is
   (= [[:team "Team"] [:project "Project"]]
      (events/scope-options targets)))
  (is
   (= [[:tenant "Tenant"] [:team "Team"] [:project "Project"]]
      (events/scope-options (assoc targets :tenant-scope true))))
  (is
   (false? (events/may-create? (update targets :teams (partial mapv #(assoc % :manage false)))))))

(deftest changing-the-team-resets-what-depends-on-it
  (let [form
        (-> (events/new-form targets "demo" nil)
            (assoc :workflow-id "requirements" :mapping {"brief" {:source :body :value "$.a"}})
            (#(events/set-field targets % :team-id "ops")))]
    (is
     (= ["ops" nil {}]
        ((juxt :target-team-id :workflow-id :mapping) form))))
  (is
   (= "X-Hub-Signature-256"
      (:header-name (events/set-field targets (events/new-form targets "demo" nil) :auth-type :hmac)))))

(def form
  (assoc (events/new-form targets "demo" nil)
         :name " Jira intake "
         :workflow-id "requirements"
         :secret "s3cret-value"
         :mapping {"brief" {:source :body :value "$.issue.fields.summary"}
                   "unused" {:source :unmapped}}
         :allowed-ips "10.0.0.0/8\n203.0.113.4, "))

(deftest the-payload-carries-the-mapping-and-the-secret-for-the-edge-to-seal
  (is
   (= {:name "Jira intake"
       :description ""
       :scope {:kind :team :team-id "demo"}
       :target {:team-id "demo" :workflow-id "requirements"}
       :auth {:type :header :header-name "X-Webhook-Token" :secret "s3cret-value"}
       :mapping [{:input "brief" :source :body :value "$.issue.fields.summary"}]
       :allowed-ips ["10.0.0.0/8" "203.0.113.4"]
       :allow-get false
       :rate-limit-per-minute 60
       :enabled true}
      (events/payload form)))
  (testing "editing without a new secret keeps the stored one"
    (is
     (nil? (get-in (events/payload (assoc form :secret "")) [:auth :secret])))
    (is
     (= []
        (events/form-problems (assoc form :secret "" :hint "…alue")))))
  (is
   (= ["Give the webhook a name" "Enter or generate a secret"]
      (events/form-problems (assoc form :name "" :secret "")))))

(deftest editing-starts-from-the-saved-webhook
  (let [webhook
        {:name "Hook" :scope {:kind "project" :team-id "demo" :project-id "demo-service"}
         :target {:team-id "demo" :workflow-id "requirements"}
         :auth {:type "hmac" :header-name "X-Gitea-Signature" :algorithm "sha256" :hint "…abcd"}
         :mapping [{:input "brief" :source "raw-body"}]
         :allowed-ips ["10.0.0.0/8"]
         :enabled false}

        edited
        (events/form-of webhook)]
    (is
     (= [:project :hmac "X-Gitea-Signature" "" "…abcd" {"brief" {:source :raw-body :value ""}} false]
        ((juxt :scope-kind :auth-type :header-name :secret :hint :mapping :enabled) edited)))
    (is
     (= [{:input "brief" :source :raw-body}]
        (:mapping (events/payload edited))))))

(deftest a-sample-body-holds-every-body-path
  (is
   (= {"issue" {"fields" {"summary" "Example Brief from the caller"}}}
      (js->clj (js/JSON.parse (events/sample-body {"brief" {:source :body :value "$.issue.fields.summary"}})))))
  (is
   (= {"example" "value"}
      (js->clj (js/JSON.parse (events/sample-body {"brief" {:source :header :value "X-A"}}))))))

(deftest test-samples-and-previews
  (is
   (= {"X-Event" "push" "X-Two" "a: b"}
      (events/parse-headers "X-Event: push\n\nnot a header\nX-Two: a: b")))
  (is
   (= {"ref" "main" "q" "a b"}
      (events/parse-query "?ref=main&q=a%20b")))
  (is
   (= {:query-id :preview-webhook
       :target {:team-id "demo" :workflow-id "requirements"}
       :mapping [{:input "brief" :source :header :value "X-Brief"}]
       :request {:body "{}" :headers {"x-brief" "hello" "content-type" "application/json"} :query {"a" "1"}}}
      (events/preview-query {:team-id "demo" :workflow-id "requirements"}
                            [{:input "brief" :source :header :value "X-Brief"}]
                            {:body "{}" :headers "X-Brief: hello" :query "a=1"})))
  (testing "a preview row per input with its value and error"
    (let [fields
          (events/input-fields (events/workflow-of targets "demo" "requirements"))]
      (is
       (= [{:input "brief" :source :header :required true :value "short"
            :error "should be at least 10 characters"}]
          (events/preview-rows fields
                               [{:input "brief" :source "header" :value "X-Brief"}]
                               {:inputs {:brief "short"}
                                :errors [{:field "brief" :message "should be at least 10 characters"}]})))
      (is
       (= ["Workflow gone"]
          (events/general-errors {:errors [{:field nil :message "Workflow gone"} {:field "brief" :message "x"}]})))))
  (is
   (= {:method "POST" :body "{}" :headers {"X-A" "1"} :query {}}
      (events/test-body {:method :post :body "{}" :headers "X-A: 1" :query ""})))
  (is
   (= "r-1"
      (events/run-id-of {:status 202 :body {:run-id "r-1"}})))
  (is
   (nil? (events/run-id-of {:status 401 :body {:detail "Unauthorized"}}))))

(def url
  "https://manager.example/hooks/acme/1b2c")

(deftest curl-examples-for-every-auth-type
  (is
   (= (str "BODY='{\"a\":\"it'\\''s\"}'\n"
           "curl -X POST 'https://manager.example/hooks/acme/1b2c' \\\n"
           "  -H \"Content-Type: application/json\" \\\n"
           "  -H \"X-Webhook-Token: $WEBHOOK_SECRET\" \\\n"
           "  --data \"$BODY\"")
      (events/curl-command {:url url :auth {:type :header :header-name "X-Webhook-Token"} :body "{\"a\":\"it's\"}"})))
  (is
   (string/includes? (events/curl-command {:url url :auth {:type "bearer"} :body "{}"})
                     "-H \"Authorization: Bearer $WEBHOOK_TOKEN\""))
  (is
   (string/includes? (events/curl-command {:url url :auth {:type :basic :username "ci"} :body "{}"})
                     "-u \"ci:$WEBHOOK_PASSWORD\""))
  (is
   (= (str "BODY='{}'\n"
           "TIMESTAMP=$(date +%s)\n"
           "SIGNATURE=$(printf '%s' \"$TIMESTAMP.$BODY\" | openssl dgst -sha256 -hmac \"$WEBHOOK_SECRET\" | sed 's/^.* //')\n"
           "curl -X POST 'https://manager.example/hooks/acme/1b2c' \\\n"
           "  -H \"Content-Type: application/json\" \\\n"
           "  -H \"X-Hub-Signature-256: sha256=$SIGNATURE\" \\\n"
           "  -H \"X-Timestamp: $TIMESTAMP\" \\\n"
           "  --data \"$BODY\"")
      (events/curl-command {:url url :body "{}"
                            :auth {:type :hmac :header-name "X-Hub-Signature-256" :algorithm :sha256
                                   :timestamp-header "X-Timestamp"}})))
  (is
   (= "curl -X GET 'https://manager.example/hooks/acme/1b2c'"
      (events/curl-command {:url url :auth {:type :none} :method :get}))))

(deftest labels
  (is
   (= ["Tenant" "Team Demo" "Project demo-service · Demo"]
      (mapv #(events/scope-label {"demo" "Demo"} %)
            [{:kind "tenant"} {:kind :team :team-id "demo"} {:kind :project :team-id "demo" :project-id "demo-service"}])))
  (is
   (= ["success" "warning" "error" "default"]
      (mapv events/status-color [202 401 422 nil])))
  (is
   (= "HMAC signature"
      (events/auth-label "hmac"))))

(deftest the-page-lives-under-the-tenant
  (is
   (= "/acme/webhooks?project=demo-service&team=demo"
      (routes/path "acme" :webhooks nil {:team "demo" :project "demo-service"})))
  (is
   (contains? routes/reserved-team-ids "webhooks")))

(deftest numbers-are-checked-before-they-are-sent
  (is
   (= [60 5 nil nil nil nil]
      (mapv events/whole-number ["60" " 5 " "" "abc" "0" "1.5"])))
  (is
   (= ["Requests a minute is a whole number above zero"]
      (events/form-problems (assoc form :rate-limit-per-minute ""))))
  (is
   (= ["The tolerance is a whole number of seconds"]
      (events/form-problems (assoc form :auth-type :hmac :header-name "X-Sig" :timestamp-header "X-Time"
                                   :tolerance-seconds "soon")))))

(deftest an-hmac-webhook-takes-inputs-only-from-what-is-signed
  (testing "headers and query parameters aren't offered"
    (is
     (= [:unmapped :body :constant :raw-body]
        (mapv first (events/offered-sources :hmac :body))))
    (is
     (= (mapv first events/sources)
        (mapv first (events/offered-sources :header :body))))
    (is
     (= [:unmapped :body :header :constant :raw-body]
        (mapv first (events/offered-sources "hmac" :header)))
     "a saved mapping still shows as it is"))
  (testing "choosing HMAC drops what it can't take"
    (let [changed
          (events/set-field targets (assoc form :allow-get true
                                           :mapping {"brief" {:source :body :value "$.a"}
                                                     "event" {:source :header :value "X-Event"}
                                                     "ref" {:source :query :value "ref"}})
                            :auth-type :hmac)]
      (is
       (= [false {"brief" {:source :body :value "$.a"}} "X-Hub-Signature-256"]
          [(:allow-get changed) (:mapping changed) (:header-name changed)]))))
  (testing "a saved HMAC webhook with such a mapping can't be saved as it is"
    (is
     (= ["An HMAC signature covers only the body: take event from the body or a fixed value"
         "An HMAC webhook can't accept GET: there is no body to sign"]
        (events/form-problems (assoc form :auth-type :hmac :header-name "X-Sig" :allow-get true
                                     :mapping {"event" {:source :header :value "X-Event"}})))))
  (is
   (false? (:allow-get (events/payload (assoc form :auth-type :hmac :allow-get true))))))

(deftest the-scope-decides-what-the-form-keeps
  (is
   (= [nil nil]
      ((juxt :team-id :project-id) (events/set-field targets (assoc form :scope-kind :project :project-id "demo-service")
                                                     :scope-kind :tenant))))
  (is
   (= ["demo" nil]
      ((juxt :team-id :project-id) (events/set-field targets (assoc form :scope-kind :project :project-id "demo-service")
                                                     :scope-kind :team)))))

(defn- selected
  [id]
  (events/selecting {} id))

(deftest only-the-selected-webhook-takes-a-reply
  (let [db
        (selected "w1")]
    (is
     (= {:valid true}
        (data/ui (events/answered db "w1" :preview {:valid true}) :webhooks :preview nil)))
    (is
     (= db
        (events/answered db "w2" :preview {:valid true}))
     "a reply about a webhook left behind")
    (is
     (= db
        (events/answered (selected "w1") #uuid "00000000-0000-0000-0000-000000000002" :test-result {:status 202})))))

(deftest selecting-another-webhook-clears-what-was-shown-of-the-last
  (let [db
        (-> (selected "w1")
            (data/set-ui :webhooks :preview {:valid true})
            (data/set-ui :webhooks :test-result {:status 202})
            (data/set-ui :webhooks :revealed {:id "w1" :secret "s"})
            (data/set-ui :webhooks :open-deliveries #{"d1"})
            (data/finish-run {:id ::events/rotate} "Refused")
            (events/selecting "w2"))]
    (is
     (= ["w2" nil nil nil #{}]
        (mapv #(data/ui db :webhooks % nil) [:selected :preview :test-result :revealed :open-deliveries])))
    (is
     (nil? (get-in db [::data/errors ::events/rotate])))))

(deftest regenerating-a-secret-asks-first
  (let [request
        (events/rotate-request {:id "w1" :name "Jira intake"})]
    (is
     (= ["Regenerate the secret of Jira intake?" "Regenerate"]
        [(:title request) (:confirm request)]))
    (is
     (= ["POST" "/api/webhooks/w1/rotate" [::events/rotated "w1"]]
        ((juxt :method :path :on-success) (:run request))))))

(deftest a-test-call-that-started-a-run-links-to-it
  (is
   (= "r1"
      (events/run-id-of {:status 202 :body {:run-id "r1"}})))
  (is
   (nil? (events/run-id-of {:status 422 :body {:run-id "r1"}}))))

(deftest deliveries-open-and-close
  (is
   (= [#{"d1"} #{} #{"d1" "d2"}]
      [(events/toggled nil "d1") (events/toggled #{"d1"} "d1") (events/toggled #{"d1"} "d2")])))

(deftest the-copy-button-says-what-happened
  (is
   (= ["Copy" "Copy" "Copied" "Couldn't copy here; select the text instead"]
      [(events/copy-hint nil :url)
       (events/copy-hint {:what :curl :ok? true} :url)
       (events/copy-hint {:what :url :ok? true} :url)
       (events/copy-hint {:what :url :ok? false} :url)])))

(deftest a-projects-webhook-starts-its-own-workflows-or-ones-of-no-project
  (let [targets
        {:teams [{:id "demo" :manage true
                  :workflows [{:id "any" :name "Any"}
                              {:id "api-review" :name "API review" :project "api"}
                              {:id "web-review" :name "Web review" :project "web"}]}]}

        form
        {:scope-kind :project :team-id "demo" :project-id "api" :workflow-id "api-review"
         :mapping {"brief" {:source :body :value "$.a"}}}]
    (is
     (= ["any" "api-review"]
        (mapv :id (events/offered-workflows targets form))))
    (is
     (= ["any" "api-review" "web-review"]
        (mapv :id (events/offered-workflows targets (assoc form :scope-kind :team)))))
    (testing "moving to another project drops a workflow that isn't offered there"
      (is
       (= [nil {}]
          ((juxt :workflow-id :mapping) (events/set-field targets form :project-id "web"))))
      (is
       (= "any"
          (:workflow-id (events/set-field targets (assoc form :workflow-id "any") :project-id "web")))))
    (testing "widening the scope keeps it"
      (is
       (= "api-review"
          (:workflow-id (events/set-field targets form :scope-kind :team)))))))

(deftest an-unsaveable-mapping-comes-back-as-general-errors
  (is
   (= ["A signed (HMAC) webhook maps inputs from the body only"]
      (events/general-errors {:inputs {} :errors [{:field nil :message "A signed (HMAC) webhook maps inputs from the body only"}
                                                  {:field "brief" :message "is required"}]}))))

(deftest a-changed-sample-drops-the-preview-of-the-earlier-one
  (let [db
        (-> (events/selecting {} "w1")
            (data/set-ui :webhooks :preview {:valid true})
            (data/finish-run {:id ::events/preview} "Refused")
            (events/sample-changed {:body "{}"}))]
    (is
     (= [{:body "{}"} nil nil]
        [(data/ui db :webhooks :sample nil) (data/ui db :webhooks :preview nil) (get-in db [::data/errors ::events/preview])]))))
