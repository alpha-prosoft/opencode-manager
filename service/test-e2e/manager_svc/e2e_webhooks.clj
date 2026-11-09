(ns manager-svc.e2e-webhooks
  "End-to-end checks of webhooks against a running platform: a team admin
   adds a webhook of every auth type for the demo team's requirements
   (analysis) workflow, real HTTP calls to /hooks/<tenant>/<id> pass or are
   refused, and a started run completes. manager-svc.e2e runs this section;
   alone:

     cd service && MANAGER_URL=http://localhost:8080 clojure -Sdeps '{:aliases {:wh {:extra-paths [\"test-e2e\"]}}}' -M:wh -m manager-svc.e2e-webhooks"
  (:require [clojure.string :as string]
            [lambda.util :as util]
            [manager-svc.e2e :as e2e]
            [manager-svc.webhooks.verify :as verify])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util Base64 UUID]))

(def ^:private http
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(def team "demo")
(def workflow "requirements")

(def brief
  "Members get 5% off orders over 100 EUR, stackable with other discounts up to 20% in total.")

(defn- body-of
  [text]
  (util/to-json {:issue {:summary text}}))

(defn- call-hook
  "{:status :data} of a call to the public endpoint."
  [tenant id {:keys [headers body method]}]
  (let [builder
        (reduce (fn [b [k v]] (.header b k v))
                (-> (HttpRequest/newBuilder (URI/create (str e2e/base-url "/hooks/" tenant "/" id)))
                    (.timeout (Duration/ofSeconds 60)))
                (merge {"Content-Type" "application/json"} headers))

        response
        (.send ^HttpClient http
               (.build (.method builder (or method "POST") (HttpRequest$BodyPublishers/ofString (str body))))
               (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :data (try (util/to-edn (.body response)) (catch Exception _ (.body response)))}))

(def saved
  "Ids of the webhooks a pass saved, deleted at its end."
  (atom #{}))

(defn- save!
  [person id attrs]
  (swap! saved conj id)
  (let [{:keys [status data]}
        (e2e/request person "PUT" (str "/api/webhooks/" id) attrs)]
    (and (= 200 status) (some? (:result data)) (nil? (:exception data)))))

(defn- config
  [auth]
  {:name (str "e2e " (name (:type auth)) " " (System/currentTimeMillis))
   :scope {:kind "team" :team-id team}
   :target {:team-id team :workflow-id workflow}
   :auth auth
   :mapping [{:input "brief" :source "body" :value "$.issue.summary"}]})

(defn- basic
  [user password]
  (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes (str user ":" password) StandardCharsets/UTF_8))))

(defn- signed
  [auth secret body now-seconds]
  (verify/sign-headers auth {:secret secret} (.getBytes ^String body StandardCharsets/UTF_8) now-seconds))

(defn- now-seconds
  []
  (quot (System/currentTimeMillis) 1000))

(def secret
  "e2e-webhook-secret-0042")

(def cases
  "[auth good-headers bad-headers] per auth type, the headers as functions of the body."
  [[{:type "header" :header-name "X-Webhook-Token" :secret secret}
    (fn [_] {"X-Webhook-Token" secret})
    (fn [_] {"X-Webhook-Token" "wrong"})]
   [{:type "bearer" :secret secret}
    (fn [_] {"Authorization" (str "Bearer " secret)})
    (fn [_] {"Authorization" "Bearer wrong"})]
   [{:type "basic" :username "ci" :secret secret}
    (fn [_] {"Authorization" (basic "ci" secret)})
    (fn [_] {"Authorization" (basic "ci" "wrong")})]
   [{:type "hmac" :header-name "X-Hub-Signature-256" :secret secret}
    (fn [body] (signed {:type :hmac :header-name "X-Hub-Signature-256"} secret body (now-seconds)))
    (fn [body] (signed {:type :hmac :header-name "X-Hub-Signature-256"} "wrong" body (now-seconds)))]
   [{:type "hmac" :header-name "X-Gitea-Signature" :timestamp-header "X-Timestamp" :tolerance-seconds 300 :secret secret}
    (fn [body] (signed {:type :hmac :header-name "X-Gitea-Signature" :timestamp-header "X-Timestamp"} secret body (now-seconds)))
    (fn [body] (signed {:type :hmac :header-name "X-Gitea-Signature" :timestamp-header "X-Timestamp"} secret body
                       (- (now-seconds) 3600)))]
   [{:type "hmac" :header-name "X-Hub-Signature" :algorithm "sha1" :secret secret}
    (fn [body] (signed {:type :hmac :header-name "X-Hub-Signature" :algorithm :sha1} secret body (now-seconds)))
    (fn [body] (signed {:type :hmac :header-name "X-Hub-Signature" :algorithm :sha1} "wrong" body (now-seconds)))]
   [{:type "none"}
    (fn [_] {})
    nil]])

(defn- auth-type
  [{:keys [type timestamp-header algorithm]}]
  (str type (when algorithm (str " " algorithm)) (when timestamp-header " with timestamp")))

(def short-body
  "A body that passes auth and fails the workflow's input schema (422), so a
   call proves it was let in without starting a run."
  (body-of "short"))

(defn- check-case
  "Checks one auth type. The first one starts a real run with its good call
   and returns its id; the others prove the same with a body the workflow
   refuses, so a pass spends the LLM on one run only."
  [person tenant starts-run? [auth good bad]]
  (let [id
        (UUID/randomUUID)

        label
        (auth-type auth)

        body
        (if starts-run? (body-of brief) short-body)]
    (when (e2e/check (str "webhook " label ": a team admin saves it") (save! person id (config auth)) id)
      (let [{:keys [status data]}
            (call-hook tenant id {:headers (good body) :body body})]
        (if starts-run?
          (e2e/check (str "webhook " label ": the right credentials start a run (202)") (= 202 status) [status data])
          (e2e/check (str "webhook " label ": the right credentials are let in (422 from the input check)")
                     (= 422 status)
                     [status data]))
        (when bad
          (let [refused
                (call-hook tenant id {:headers (bad body) :body body})]
            (e2e/check (str "webhook " label ": wrong credentials are refused (401)") (= 401 (:status refused)) refused)))
        (let [shown
              (e2e/result person {:query-id :get-webhook :webhook-id id})]
          (e2e/check (str "webhook " label ": the delivery log records calls and never the secret")
                     (and (seq (:deliveries shown)) (not (string/includes? (pr-str shown) secret)))
                     (count (:deliveries shown))))
        {:id id :run-id (e2e/track-run! person (some-> (:run-id data) parse-uuid))}))))

(def ^:private token
  {"X-Webhook-Token" secret})

(def ^:private header-auth
  {:type "header" :header-name "X-Webhook-Token" :secret secret})

(defn- add!
  "Saves a header-auth webhook with extras over the usual configuration; its id."
  [person extras]
  (let [id
        (UUID/randomUUID)]
    (when (save! person id (merge (config header-auth) extras))
      id)))

(defn- let-in
  [tenant id headers]
  (:status (call-hook tenant id {:headers headers :body short-body})))

(defn- listing
  [people id]
  (let [row
        (fn [person]
          (first (filter #(= (str id) (str (:id %))) (e2e/result person {:query-id :list-webhooks}))))]
    (e2e/check "webhooks: its team's admin lists it and may manage it" (true? (:can-manage (row (:alice people)))))
    (e2e/check "webhooks: a member lists it read-only" (false? (:can-manage (row (:bob people)))) (row (:bob people)))
    (e2e/check "webhooks: other teams and other tenants don't list it"
               (not-any? row (keep people [:henry :gina])))))

(defn- targets-and-preview
  [{:keys [alice bob henry]}]
  (let [demo
        (first (filter #(= team (:id %)) (:teams (e2e/result alice {:query-id :list-webhook-targets}))))

        preview
        (fn [person text]
          (e2e/query person {:query-id :preview-webhook
                             :target {:team-id team :workflow-id workflow}
                             :mapping [{:input "brief" :source "body" :value "$.issue.summary"}]
                             :request {:body (body-of text) :headers {} :query {}}}))

        mapped
        (:ok (preview bob brief))

        refused
        (:ok (preview bob "short"))]
    (e2e/check "webhooks: targets name the workflows a team admin may point one at"
               (and (:manage demo) (some #(= workflow (:id %)) (:workflows demo)))
               demo)
    (e2e/check "webhooks: a dry run maps a sample request to the workflow's inputs"
               (and (true? (:valid mapped)) (some #{brief} (vals (:inputs mapped))))
               mapped)
    (e2e/check "webhooks: a dry run names the input that fails the schema"
               (and (false? (:valid refused)) (= "brief" (:field (first (:errors refused)))))
               refused)
    (e2e/check "webhooks: people outside the target team can't try it"
               (= 403 (e2e/error-status (preview henry brief))))))

(defn- rotation-and-test-call
  [{:keys [alice bob]} tenant]
  (when-let [id
             (add! alice {})]
    (let [path
          (str "/api/webhooks/" id)

          sample
          {:method "POST" :body short-body :headers {} :query {}}

          rotated
          (e2e/request alice "POST" (str path "/rotate") {})

          new-secret
          (get-in rotated [:data :secret])]
      (e2e/check "webhooks: rotating shows a new secret once"
                 (and (= 200 (:status rotated)) (seq (str new-secret)) (not= secret new-secret))
                 (:status rotated))
      (e2e/check "webhooks: the old secret is refused afterwards" (= 401 (let-in tenant id token)))
      (e2e/check "webhooks: the new one is let in" (= 422 (let-in tenant id {"X-Webhook-Token" (str new-secret)})))
      (e2e/check "webhooks: the secret isn't shown again"
                 (not (string/includes? (pr-str (e2e/result alice {:query-id :get-webhook :webhook-id id})) (str new-secret))))
      (let [tested
            (e2e/request alice "POST" (str path "/test") sample)]
        (e2e/check "webhooks: a test call is a real request signed with the stored secret"
                   (and (= 200 (:status tested)) (= 422 (get-in tested [:data :status])))
                   (:body tested)))
      (e2e/check "webhooks: a member can neither rotate nor send test calls"
                 (= [403 403]
                    [(:status (e2e/request bob "POST" (str path "/rotate") {}))
                     (:status (e2e/request bob "POST" (str path "/test") sample))]))
      (e2e/check "webhooks: a member can't delete it"
                 (= 403 (e2e/error-status (e2e/command bob {:cmd-id :delete-webhook :id id})))))))

(defn- refusals
  [{:keys [alice]} tenant]
  (when-let [id
             (add! alice {:allowed-ips ["10.255.0.0/16"]})]
    (e2e/check "webhooks: a caller outside the allowed addresses is refused (403)" (= 403 (let-in tenant id token))))
  (when-let [id
             (add! alice {:rate-limit-per-minute 1})]
    (e2e/check "webhooks: calls above the rate limit are refused (429)"
               (= [422 429] [(let-in tenant id token) (let-in tenant id token)])))
  (when-let [id
             (add! alice {:enabled false})]
    (e2e/check "webhooks: a disabled webhook answers 404" (= 404 (let-in tenant id token))))
  (when-let [id
             (add! alice {:allow-get true})]
    (e2e/check "webhooks: GET is taken when allowed"
               (= 422 (:status (call-hook tenant id {:headers token :method "GET"}))))))

(defn- scopes
  [{:keys [alice bob]} tenant]
  (let [tenant-wide
        (add! alice {:scope {:kind "tenant"}})

        of-project
        (add! alice {:scope {:kind "project" :team-id team :project-id "demo-service"}
                     :target {:team-id team :workflow-id "implement-gerrit"}
                     :mapping [{:input "task" :source "body" :value "$.issue.summary"}]})

        listed
        (fn [query]
          (set (map (comp str :id) (e2e/result alice (assoc query :query-id :list-webhooks)))))]
    (e2e/check "webhooks: a tenant admin adds one for the whole tenant" (some? tenant-wide))
    (e2e/check "webhooks: a member can't" (not (save! bob (UUID/randomUUID) (assoc (config header-auth) :scope {:kind "tenant"}))))
    (e2e/check "webhooks: one of a project is listed with its project"
               (and of-project
                    (contains? (listed {:team-id team :project-id "demo-service"}) (str of-project))
                    (not (contains? (listed {:team-id team :project-id "demo-service"}) (str tenant-wide))))
               of-project)
    (e2e/check "webhooks: both take calls"
               (= [422 422] [(let-in tenant tenant-wide token) (let-in tenant of-project token)]))))

(defn delete-saved!
  [admin]
  (doseq [id @saved]
    (try
      (e2e/command admin {:cmd-id :delete-webhook :id id})
      (catch Exception _
        nil)))
  (reset! saved #{}))

(defn webhooks
  [people]
  (e2e/section "Webhooks")
  (let [admin
        (:alice people)

        member
        (:bob people)

        tenant
        "acme"

        started
        (vec (keep-indexed #(check-case admin tenant (zero? %1) %2) cases))

        {:keys [id]}
        (first started)

        body
        (body-of "too short")]
    (e2e/check "webhooks: a member can't add one"
               (not (save! member (UUID/randomUUID) (config {:type "none"}))))
    (when id
      (let [header {"X-Webhook-Token" secret}]
        (e2e/check "webhooks: inputs failing the workflow's schema are 422 with field errors"
                   (let [{:keys [status data]} (call-hook tenant id {:headers header :body (body-of "short")})]
                     (and (= 422 status) (= "brief" (get-in data [:errors 0 :field])))))
        (e2e/check "webhooks: another tenant's path is 404"
                   (= 404 (:status (call-hook "globex" id {:headers header :body body}))))
        (e2e/check "webhooks: GET is refused unless allowed"
                   (= 405 (:status (call-hook tenant id {:headers header :method "GET"}))))
        (e2e/check "webhooks: a member sees it without a secret hint"
                   (let [shown (e2e/result member {:query-id :get-webhook :webhook-id id})]
                     (and shown (nil? (get-in shown [:auth :hint])) (false? (:can-manage shown)))))
        (e2e/check "webhooks: someone outside the team gets 403"
                   (= 403 (e2e/error-status (e2e/query (:henry people) {:query-id :get-webhook :webhook-id id}))))))
    (when-let [run-id (:run-id (first started))]
      (let [run
            (e2e/wait-for-run admin run-id)]
        (e2e/check "webhooks: the started run is a platform run of the team, triggered by the webhook"
                   (and (= "webhook" (get-in run [:trigger :type])) (:platform-started run))
                   (select-keys run [:trigger :platform-started]))
        (e2e/check "webhooks: the run completes" (= :completed (:status run)) (select-keys run [:status :error-message]))))
    (try
      (when id
        (e2e/guarded "Webhook lists" #(listing people id)))
      (e2e/guarded "Webhook targets and preview" #(targets-and-preview people))
      (e2e/guarded "Webhook rotation and test call" #(rotation-and-test-call people tenant))
      (e2e/guarded "Webhook refusals" #(refusals people tenant))
      (e2e/guarded "Webhook scopes" #(scopes people tenant))
      (finally
        (delete-saved! admin)))
    (e2e/check "webhooks: a deleted webhook answers 404"
               (or (nil? id) (= 404 (:status (call-hook tenant id {:headers {"X-Webhook-Token" secret} :body body})))))))

(defn -main
  [& _args]
  (when-let [people
             (e2e/log-in ["alice" "bob" "henry" "gina"])]
    (try
      (e2e/guarded "Webhooks" #(webhooks people))
      (finally
        (delete-saved! (:alice people))
        (e2e/guarded "Cleanup" #(e2e/clean-up! people)))))
  (let [exit-code
        (e2e/summary!)]
    (shutdown-agents)
    (System/exit exit-code)))
