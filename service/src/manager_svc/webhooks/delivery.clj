(ns manager-svc.webhooks.delivery
  "One request to /hooks/<tenant>/<webhook-id>, from lookup to the started
   run. Unknown, deleted, disabled and other tenants' webhooks answer 404
   alike. Every other request is checked (source address 403, auth 401,
   inputs 422), starts a platform run of the target workflow in the team's
   scope (202 {:run-id :delivery-id}) and is recorded as a delivery with
   secret headers redacted and the body cut short. Callers never see why
   auth failed; the delivery log says.

   Rate limits answer 429 and record nothing: accepted requests draw on the
   webhook's bucket, refused ones on a bucket of their source address, so
   someone without the secret can neither shut the real caller out nor
   fill the log.

   The manager's own test invocation carries the process's test token in
   the test header; only that marks a delivery as a test and lets it skip
   the source address check.

   ports are the side effects, so tests replace them:
     {:lookup (fn [tenant webhook-id] webhook-or-nil)
      :start-run! (fn [run-id attrs])
      :record! (fn [webhook-id delivery])
      :evaluate (fn [webhook request] {:inputs :errors :valid})
      :open (fn [webhook] {:secret :username})
      :limits atom
      :test-token string
      :now-ms (fn [])}"
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.auth :as auth]
            [manager-svc.time :as time]
            [manager-svc.webhooks.limiter :as limiter]
            [manager-svc.webhooks.model :as model]
            [manager-svc.webhooks.verify :as verify])
  (:import [java.nio.charset StandardCharsets]))

(def max-logged-body 4096)
(def max-logged-headers 50)
(def max-logged-header-value 500)

(def test-header
  "Carries the process's test token on the manager's own test invocation."
  "x-manager-webhook-test")

(def always-redacted
  #{"authorization" "proxy-authorization" "cookie" "x-api-key" "x-auth-token" test-header})

(def redacted "[redacted]")

(defn- secret-headers
  [{:keys [auth]}]
  (into always-redacted
        (keep #(some-> % string/lower-case))
        [(when (#{:header :hmac} (keyword (:type auth))) (:header-name auth))]))

(defn- clip
  [text limit]
  (if (> (count text) limit) (subs text 0 limit) text))

(defn redact-headers
  "[{:name :value}] of headers, sorted by name, secret ones redacted."
  [webhook headers]
  (let [secret
        (secret-headers webhook)]
    (->> headers
         (map (fn [[k v]]
                (let [lowered (string/lower-case (name k))]
                  {:name lowered
                   :value (if (contains? secret lowered)
                            redacted
                            (clip (str v) max-logged-header-value))})))
         (sort-by :name)
         (take max-logged-headers)
         vec)))

(defn delivery-record
  "What the delivery log keeps of a request and its outcome."
  [webhook {:keys [method headers query body-text source-ip test?]} {:keys [status run-id error]} delivery-id at]
  (let [body
        (str body-text)]
    (cond-> {:id (str delivery-id)
             :at at
             :method (name method)
             :source-ip source-ip
             :status status
             :headers (redact-headers webhook headers)
             :query (vec (sort-by :name (map (fn [[k v]] {:name (name k) :value (clip (str v) max-logged-header-value)}) query)))
             :body (clip body max-logged-body)
             :body-truncated (> (count body) max-logged-body)
             :test (boolean test?)}
      run-id (assoc :run-id (str run-id))
      error (assoc :error error))))

(defn- reply
  [status body]
  {:status status :body body})

(defn- refusal
  [status detail error]
  {:status status :body {:detail detail} :error error})

(defn- started
  [{:keys [start-run!]} webhook inputs delivery-id]
  (let [run-id
        (random-uuid)

        {:keys [team-id workflow-id]}
        (:target webhook)]
    (try
      (start-run! run-id {:tenant (:tenant webhook)
                          :team-id team-id
                          :workflow-id workflow-id
                          :inputs inputs
                          :trigger {:type "webhook"
                                    :webhook-id (str (:id webhook))
                                    :webhook-name (:name webhook)
                                    :delivery-id (str delivery-id)}
                          :created-by (str auth/platform-user ":webhook")})
      {:status 202 :body {:run-id (str run-id) :delivery-id (str delivery-id)} :run-id run-id}
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [status message]}
              (ex-data e)]
          (if (and (int? status) (< status 500))
            (refusal status (or message "The run could not start") (or message (ex-message e)))
            (do (log/warn e "Webhook run failed to start" (:id webhook))
                (refusal 500 "The run could not start" (ex-message e))))))
      (catch Exception e
        (log/warn e "Webhook run failed to start" (:id webhook))
        (refusal 500 "The run could not start" (ex-message e))))))

(defn- refused
  "Why the caller may not use the webhook, nil when they may."
  [{:keys [open now-ms]} webhook {:keys [source-ip test?] :as request}]
  (if-not (or test? (verify/ip-allowed? (:allowed-ips webhook) source-ip))
    (refusal 403 "Forbidden" (str "Source " source-ip " is not allowed"))
    (let [checked
          (verify/check (:auth webhook) (open webhook) request (quot (now-ms) 1000))]
      (when-not (:ok checked)
        (refusal (:status checked) "Unauthorized" (:reason checked))))))

(defn- accepted
  [{:keys [evaluate] :as ports} webhook request delivery-id]
  (let [{:keys [inputs errors valid]}
        (evaluate webhook request)]
    (if valid
      (started ports webhook inputs delivery-id)
      (assoc (reply 422 {:detail "The request doesn't map to valid inputs of the workflow"
                         :errors errors})
             :error (string/join "; " (map #(str (when (:field %) (str (:field %) ": ")) (:message %)) errors))))))

(defn- too-many
  [retry-after]
  (assoc (reply 429 {:detail "Too many requests"})
         :headers {"Retry-After" (str retry-after)}
         :unrecorded true))

(defn- outcome
  [{:keys [limits now-ms] :as ports} webhook request delivery-id]
  (let [denied
        (refused ports webhook request)

        bucket
        (if denied
          [(:id webhook) (:source-ip request)]
          (:id webhook))

        {:keys [allowed retry-after]}
        (limiter/take! limits bucket (:rate-limit-per-minute webhook) (now-ms))]
    (cond
      (not allowed) (too-many retry-after)
      denied denied
      :else (accepted ports webhook request delivery-id))))

(defn- guarded-outcome
  "outcome, with anything that goes wrong on the way a recorded 500."
  [ports webhook request delivery-id]
  (try
    (outcome ports webhook request delivery-id)
    (catch Exception e
      (log/warn e "Webhook delivery failed" (:id webhook))
      (refusal 500 "The request could not be processed" (ex-message e)))))

(defn- known
  [{:keys [lookup]} tenant webhook-id]
  (when-let [webhook
             (lookup tenant webhook-id)]
    (when (and (model/present? webhook) (:enabled webhook) (= tenant (:tenant webhook)))
      webhook)))

(def not-found
  (reply 404 {:detail "Not found"}))

(defn- test-invocation?
  [{:keys [test-token]} {:keys [headers]}]
  (boolean (verify/equal? (get headers test-header) test-token)))

(defn- full-request
  "request with its body read (:body is the bytes or a delay of them), as
   text too, and whether it is the manager's own test invocation."
  [ports {:keys [body] :as request}]
  (let [bytes
        (or (force body) (byte-array 0))]
    (assoc request
           :body bytes
           :body-text (String. ^bytes bytes StandardCharsets/UTF_8)
           :test? (test-invocation? ports request))))

(defn- record
  [{:keys [record!]} webhook request result delivery-id]
  (try
    (record! (:id webhook) (delivery-record webhook request result delivery-id (time/now)))
    (catch Exception e
      (log/warn e "Webhook delivery could not be recorded" (:id webhook)))))

(defn handle
  "{:status :body :headers?} of request {:tenant :webhook-id :method
   :headers :query :body (bytes, or a delay of them read only for a known
   webhook) :source-ip}."
  [ports {:keys [tenant webhook-id method] :as request}]
  (if-let [webhook
           (known ports tenant webhook-id)]
    (if (and (= :get method) (not (:allow-get webhook)))
      (reply 405 {:detail "Use POST or PUT"})
      (let [delivery-id
            (random-uuid)

            request
            (full-request ports request)

            result
            (guarded-outcome ports webhook request delivery-id)]
        (when-not (:unrecorded result)
          (record ports webhook request result delivery-id))
        (select-keys result [:status :body :headers])))
    not-found))
