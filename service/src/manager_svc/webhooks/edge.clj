(ns manager-svc.webhooks.edge
  "Webhook endpoints of the public listener:

     POST|PUT|GET /hooks/<tenant>/<webhook-id>   the webhook itself, for
        machines: no cookies, no login; see manager-svc.webhooks.delivery
     PUT  /api/webhooks/<id>          create or change a webhook; a secret
        in the body (auth.secret) is sealed here, so edd only sees it sealed
     POST /api/webhooks/<id>/rotate   a new random secret, answered once
     POST /api/webhooks/<id>/test     a real invocation of the public
        endpoint from the manager, signed with the stored secret, for the
        Test panel: {:status :body :request}

   A refused /api/webhooks request answers its HTTP status with {:detail
   message}, whether the edge or a command refused it.

   The caller's address is the TCP peer; X-Forwarded-For counts only when
   the peer is one of MANAGER_TRUSTED_PROXIES (addresses, CIDRs, or host
   names standing for their current addresses)."
  (:require [clojure.string :as string]
            [manager-svc.client :as client]
            [manager-svc.edge.api :as edge-api]
            [manager-svc.edge.server :as server]
            [manager-svc.errors :as errors]
            [manager-svc.http :as http]
            [manager-svc.webhooks.delivery :as delivery]
            [manager-svc.webhooks.secrets :as secrets]
            [manager-svc.webhooks.verify :as verify]
            [manager-svc.webhooks.workflows :as workflows]
            [manager-svc.workers :as workers])
  (:import [io.undertow.server HttpServerExchange]
           [io.undertow.util HeaderValues]
           [java.io InputStream]
           [java.net InetAddress InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

(def ^:private uuid-pattern
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(def ^:private hook-pattern
  (re-pattern (str "^/hooks/([a-z0-9][a-z0-9-]{0,62})/(" uuid-pattern ")/?$")))

(def ^:private api-pattern
  (re-pattern (str "^/api/webhooks/(" uuid-pattern ")(/rotate|/test)?$")))

(defn hook-path
  "{:tenant :webhook-id} of a webhook's public path, or nil."
  [path]
  (when-let [[_ tenant webhook-id]
             (re-find hook-pattern (str path))]
    {:tenant tenant :webhook-id (parse-uuid webhook-id)}))

(defn api-path
  "{:webhook-id :action nil|:rotate|:test} of a webhook API path, or nil."
  [path]
  (when-let [[_ webhook-id action]
             (re-find api-pattern (str path))]
    {:webhook-id (parse-uuid webhook-id)
     :action (some-> action (subs 1) keyword)}))

(defn- body-bytes
  ^bytes [{:keys [body]}]
  (if body
    (.readAllBytes ^InputStream body)
    (byte-array 0)))

(defn- peer
  [{:keys [remote-addr exchange]}]
  (or remote-addr
      (when exchange
        (let [^InetSocketAddress address
              (.getSourceAddress ^HttpServerExchange exchange)]
          (some-> address .getAddress .getHostAddress)))))

(defn- forwarded-for
  "Every X-Forwarded-For line of the request, joined in order."
  [{:keys [headers exchange]}]
  (if-let [^HeaderValues lines
           (when exchange
             (.get (.getRequestHeaders ^HttpServerExchange exchange) "X-Forwarded-For"))]
    (string/join "," lines)
    (get headers "x-forwarded-for")))

(def proxy-lookup-ms
  "How long the addresses of a proxy named by host are kept."
  30000)

(defn- addresses-of
  [host]
  (try
    (mapv #(.getHostAddress ^InetAddress %) (InetAddress/getAllByName host))
    (catch Exception _
      [])))

(defn- named-proxy
  [lookups host now-ms]
  (let [{:keys [at addresses]}
        (get @lookups host)]
    (if (and at (< (- now-ms at) proxy-lookup-ms))
      addresses
      (let [found
            (addresses-of host)]
        (swap! lookups assoc host {:at now-ms :addresses found})
        found))))

(defn trusted-proxies
  "The CIDRs of MANAGER_TRUSTED_PROXIES: an entry that is no address or
   CIDR is a host name (Traefik's in compose) and stands for its current
   addresses, looked up again every proxy-lookup-ms."
  [{:keys [settings] :as system} now-ms]
  (let [lookups
        (or (:webhooks/proxy-lookups system) (atom {}))]
    (vec (mapcat (fn [entry]
                   (let [proxy
                         (string/trim entry)]
                     (cond
                       (string/blank? proxy) []
                       (verify/valid-cidr? proxy) [proxy]
                       :else (named-proxy lookups proxy now-ms))))
                 (:trusted-proxies settings)))))

(defn- lookup
  [settings tenant webhook-id]
  (:webhook (client/query settings {:query-id :get-webhook-for-delivery :tenant tenant :webhook-id webhook-id})))

(defn- open
  [settings {:keys [tenant id sealed]}]
  (when sealed
    (secrets/open settings tenant id sealed)))

(defn ports
  "The delivery's side effects against the running service."
  [system]
  (let [settings
        (:settings system)]
    {:lookup (partial lookup settings)
     :open (partial open settings)
     :evaluate (fn [webhook request]
                 (workflows/evaluate system (:tenant webhook) webhook request))
     :start-run! (fn [run-id attrs]
                   (client/command settings {:cmd-id :start-run :id run-id :attrs attrs})
                   (workers/kick!))
     :record! (fn [webhook-id record]
                (client/command settings {:cmd-id :record-webhook-delivery :id webhook-id :attrs {:delivery record}}))
     :limits (:webhooks/limits system)
     :test-token (:webhooks/test-token system)
     :now-ms #(System/currentTimeMillis)}))

(def ^:private delivery-methods
  #{:post :put :get})

(defn- deliver-hook
  [system {:keys [method headers query path] :as request}]
  (if-not (contains? delivery-methods method)
    (server/error 405 "Use POST or PUT")
    (let [{:keys [status body] :as response}
          (delivery/handle (ports system)
                           (merge (hook-path path)
                                  {:method method
                                   :headers headers
                                   :query (or query {})
                                   :body (when-not (= :get method) (delay (body-bytes request)))
                                   :source-ip (verify/source-ip (trusted-proxies system (System/currentTimeMillis))
                                                                (peer request)
                                                                (forwarded-for request))}))]
      (-> (server/json status body)
          (update :headers merge (:headers response) {"Cache-Control" "no-store"})))))

(defn- text
  [value]
  (when-not (string/blank? (str value))
    (str value)))

(defn- auth-fields
  [auth]
  (when (map? auth)
    (select-keys auth [:type :header-name :username :algorithm :timestamp-header :tolerance-seconds])))

(defn- mapping-rows
  [mapping]
  (when (sequential? mapping)
    (mapv #(if (map? %) (select-keys % [:input :source :value]) {}) mapping)))

(defn- save-attrs
  "The fields of body a webhook is saved from, and nothing else: neither
   the secret nor whatever else the body holds reaches edd's request log."
  [body]
  (let [nested
        (fn [k fields]
          (when (map? (get body k))
            (select-keys (get body k) fields)))]
    (into {}
          (remove (comp nil? val))
          (merge (select-keys body [:name :description :enabled :allowed-ips :allow-get :rate-limit-per-minute])
                 {:scope (nested :scope [:kind :team-id :project-id])
                  :target (nested :target [:team-id :workflow-id])
                  :auth (auth-fields (:auth body))
                  :mapping (mapping-rows (:mapping body))}))))

(defn save-command
  "The command saving body as webhook-id for identity, its secret sealed."
  [settings identity webhook-id body]
  (let [auth
        (when (map? (:auth body))
          (:auth body))

        secret
        (text (:secret auth))

        username
        (text (:username auth))

        sealed
        (when secret
          (secrets/seal settings (:tenant identity) webhook-id
                        (cond-> {:secret secret}
                          username (assoc :username username))))]
    {:cmd-id :save-webhook
     :id webhook-id
     :attrs (cond-> (save-attrs body)
              sealed (assoc :sealed sealed))}))

(defn- save
  [{:keys [settings]} request]
  (let [[token identity]
        (edge-api/person! settings request)

        {:keys [webhook-id]}
        (api-path (:path request))

        body
        (edge-api/json-object! request "Send the webhook as a JSON object")]
    (edge-api/command! settings token [(save-command settings identity webhook-id body)])))

(defn- rotate
  "A new secret, answered once the command stored it; the command refuses
   whoever doesn't manage the webhook."
  [{:keys [settings]} request]
  (let [[token identity]
        (edge-api/person! settings request)

        {:keys [webhook-id]}
        (api-path (:path request))

        secret
        (secrets/generate)

        sealed
        (secrets/seal settings (:tenant identity) webhook-id {:secret secret})]
    (edge-api/command! settings token [{:cmd-id :set-webhook-secret
                                        :id webhook-id
                                        :attrs {:sealed sealed}}])
    (assoc (server/json {:secret secret}) :headers {"Content-Type" "application/json" "Cache-Control" "no-store"})))

(defn- visible-webhook
  [settings token webhook-id]
  (client/query-as settings token {:query-id :get-webhook :webhook-id webhook-id}))

(defn- manageable!
  [webhook]
  (when-not (:can-manage webhook)
    (errors/forbidden! "Only admins of the webhook's scope can do this"))
  webhook)

(defn- local-url
  [{:keys [public-port-internal public-bind-host]} path query]
  (let [host
        (if (or (string/blank? public-bind-host) (= "0.0.0.0" public-bind-host) (= "::" public-bind-host))
          "127.0.0.1"
          public-bind-host)]
    (str "http://" host ":" public-port-internal path
         (when (seq query) (str "?" (http/query-string query))))))

(def ^:private unsendable-headers
  "Sample headers a test never sends: the connection's own, and those that
   would claim another source or a test."
  #{"host" "content-length" "cookie" "connection" "x-forwarded-for" delivery/test-header})

(defn- plain-headers
  "Sample headers by lower-case name, so the ones auth adds replace them."
  [headers]
  (into {}
        (for [[k v] headers
              :let [header-name (string/lower-case (string/trim (name k)))]
              :when (and (re-matches #"[a-z0-9-]{1,64}" header-name)
                         (not (contains? unsendable-headers header-name)))]
          [header-name (str v)])))

(defn test-request
  "The request the Test panel sends for webhook with sample {:body :headers
   :query :method}: the sample headers, those auth needs and the process's
   test-token, which marks it as the manager's own."
  [settings webhook secret {:keys [body headers query method]} test-token now-seconds]
  (let [method
        (if (= "GET" (some-> method name string/upper-case)) :get :post)

        body-text
        (if (= :get method) "" (str body))

        signed
        (verify/sign-headers (:auth webhook) secret (.getBytes body-text StandardCharsets/UTF_8) now-seconds)]
    {:method method
     :url (local-url settings (str "/hooks/" (:tenant webhook) "/" (:id webhook)) query)
     :headers (merge {"content-type" "application/json"}
                     (plain-headers headers)
                     (plain-headers signed)
                     {delivery/test-header test-token})
     :body (when-not (= :get method) body-text)
     :timeout-seconds 30}))

(defn- answer-body
  [{:keys [body]}]
  (try
    (http/read-json body)
    (catch Exception _
      (subs (str body) 0 (min 2000 (count (str body)))))))

(defn- test-send
  [{:keys [settings] :as system} request]
  (let [[token identity]
        (edge-api/person! settings request)

        {:keys [webhook-id]}
        (api-path (:path request))

        _
        (manageable! (visible-webhook settings token webhook-id))

        webhook
        (or (lookup settings (:tenant identity) webhook-id)
            (errors/not-found! "Webhook not found"))

        sample
        (edge-api/json-object! request "Send the sample request as a JSON object")

        sent
        (test-request settings webhook (open settings webhook) sample (:webhooks/test-token system)
                      (quot (System/currentTimeMillis) 1000))

        response
        (http/request! sent)]
    (server/json {:status (:status response)
                  :body (answer-body response)
                  :request {:method (string/upper-case (name (:method sent)))
                            :path (subs (:url sent) (string/index-of (:url sent) "/hooks/"))
                            :headers (delivery/redact-headers webhook (:headers sent))}})))

(defn- action-of
  [request]
  (when-let [{:keys [action]}
             (api-path (:path request))]
    [(or action :save)]))

(def routes
  [{:method :any :prefix "/hooks/" :match #(hook-path (:path %)) :handler deliver-hook}
   {:method :put :prefix "/api/webhooks/" :match #(= [:save] (action-of %)) :handler save}
   {:method :post :prefix "/api/webhooks/" :match #(= [:rotate] (action-of %)) :handler rotate}
   {:method :post :prefix "/api/webhooks/" :match #(= [:test] (action-of %)) :handler test-send}])
