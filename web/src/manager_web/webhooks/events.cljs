(ns manager-web.webhooks.events
  "Webhooks: the create/edit form, its mapping editor, the Test panel and
   the curl examples. Pure functions hold the logic so tests reach it; the
   events wire them to the edge (PUT /api/webhooks/<id>, POST .../rotate,
   POST .../test) and to edd (:preview-webhook, :delete-webhook)."
  (:require [clojure.string :as string]
            [manager-web.components.confirm :as confirm]
            [manager-web.data :as data]
            [manager-web.format :as format]
            [re-frame.core :as rf]))

(def auth-types
  [[:header "Header auth" "A header carries a shared secret"]
   [:bearer "Bearer token" "Authorization: Bearer <token>"]
   [:basic "Basic auth" "Authorization: Basic with a username and password"]
   [:hmac "HMAC signature" "The caller signs the body with a shared secret (GitHub, Gitea, Bitbucket style)"]
   [:none "None" "Anyone who knows the URL can start the workflow"]])

(def sources
  [[:unmapped "Not mapped"]
   [:body "Body path"]
   [:header "Header"]
   [:query "Query parameter"]
   [:constant "Fixed value"]
   [:raw-body "Whole body as text"]])

(def source-hints
  {:body "$.pull_request.title"
   :header "X-Event-Key"
   :query "ref"
   :constant "Value"})

(def default-header-names
  {:header "X-Webhook-Token"
   :hmac "X-Hub-Signature-256"})

(def secret-types
  #{:header :bearer :basic :hmac})

(def unsigned-sources
  "Sources an HMAC signature doesn't cover: it signs the body alone."
  #{:header :query})

(defn hmac?
  [auth-type]
  (= :hmac (format/kw auth-type)))

(defn offered-sources
  "The [id label] sources a mapping row offers under auth-type: an HMAC
   webhook takes inputs only from what the caller signed. current, the
   row's source, stays listed so that a saved mapping shows as it is."
  [auth-type current]
  (filterv (fn [[id]]
             (or (= id current)
                 (not (and (hmac? auth-type) (unsigned-sources id)))))
           sources))

(defn auth-label
  [auth-type]
  (or (some (fn [[id label]] (when (= id (format/kw auth-type)) label)) auth-types)
      "Unknown"))

(defn list-key
  [team-id project-id]
  [:webhooks team-id project-id])

(defn detail-key
  [webhook-id]
  [:webhook (str webhook-id)])

(def targets-key
  :webhook-targets)

(def targets-query
  {:query-id :list-webhook-targets})

(defn team-of
  [targets team-id]
  (some #(when (= team-id (:id %)) %) (:teams targets)))

(defn managed-teams
  [targets]
  (filterv :manage (:teams targets)))

(defn may-create?
  [targets]
  (boolean (or (:tenant-scope targets) (seq (managed-teams targets)))))

(defn scope-options
  "[kind label] of the scopes the person may add webhooks to."
  [targets]
  (cond-> []
    (:tenant-scope targets) (conj [:tenant "Tenant"])
    (seq (managed-teams targets)) (conj [:team "Team"] [:project "Project"])))

(defn target-team-id
  "The team whose workflow the form starts: the scope's, or the chosen one
   of a tenant webhook."
  [{:keys [scope-kind team-id target-team-id]}]
  (if (= :tenant scope-kind) target-team-id team-id))

(defn workflow-of
  [targets team-id workflow-id]
  (some #(when (= workflow-id (:id %)) %) (:workflows (team-of targets team-id))))

(defn offered-workflows
  "The workflows the form's webhook may start: those of its target team; a
   project's webhook only that project's and the ones of no project."
  [targets {:keys [scope-kind project-id] :as form}]
  (filterv #(or (not= :project scope-kind)
                (nil? (:project %))
                (= project-id (:project %)))
           (:workflows (team-of targets (target-team-id form)))))

(defn- kept-workflow
  "form without a workflow its scope no longer offers."
  [targets form]
  (if (some #(= (:workflow-id form) (:id %)) (offered-workflows targets form))
    form
    (assoc form :workflow-id nil :mapping {})))

(defn input-fields
  "[{:input :title :type :required}] of a workflow's inputs."
  [{:keys [inputs-schema]}]
  (let [required
        (set (map name (:required inputs-schema)))]
    (vec (for [[k property] (:properties inputs-schema)
               :let [input (name k)]]
           {:input input
            :title (or (:title property) (format/label input))
            :description (:description property)
            :required (contains? required input)}))))

(defn- first-managed-team
  [targets preferred]
  (let [managed
        (managed-teams targets)]
    (or (some #(when (= preferred (:id %)) (:id %)) managed)
        (:id (first managed)))))

(defn new-form
  "A blank form; scoped to team-id (and project-id) when given and managed."
  [targets team-id project-id]
  (let [team
        (first-managed-team targets team-id)

        kind
        (cond
          (and team project-id (= team team-id)) :project
          team :team
          :else :tenant)]
    {:name ""
     :description ""
     :scope-kind kind
     :team-id team
     :project-id (when (= :project kind) project-id)
     :target-team-id (or team (:id (first (:teams targets))))
     :workflow-id nil
     :auth-type :header
     :header-name (:header default-header-names)
     :username ""
     :secret ""
     :algorithm :sha256
     :timestamp-header ""
     :tolerance-seconds 300
     :mapping {}
     :allowed-ips ""
     :allow-get false
     :rate-limit-per-minute 60
     :enabled true}))

(defn form-of
  "The form editing webhook; the secret stays empty (kept unless replaced)."
  [{:keys [name description scope target auth mapping allowed-ips allow-get rate-limit-per-minute enabled]}]
  {:name (or name "")
   :description (or description "")
   :scope-kind (format/kw (:kind scope))
   :team-id (:team-id scope)
   :project-id (:project-id scope)
   :target-team-id (:team-id target)
   :workflow-id (:workflow-id target)
   :auth-type (format/kw (:type auth))
   :header-name (or (:header-name auth) (get default-header-names (format/kw (:type auth))) "")
   :username (or (:username auth) "")
   :secret ""
   :hint (:hint auth)
   :algorithm (or (format/kw (:algorithm auth)) :sha256)
   :timestamp-header (or (:timestamp-header auth) "")
   :tolerance-seconds (or (:tolerance-seconds auth) 300)
   :mapping (into {} (for [{:keys [input source value]} mapping]
                       [(clojure.core/name input) {:source (format/kw source) :value (or value "")}]))
   :allowed-ips (string/join "\n" allowed-ips)
   :allow-get (true? allow-get)
   :rate-limit-per-minute (or rate-limit-per-minute 60)
   :enabled (not (false? enabled))})

(defn- lines
  [text]
  (vec (remove string/blank? (map string/trim (string/split (str text) #"[\n,]+")))))

(defn mapping-list
  "The form's mapping rows the service takes; unmapped inputs are left out."
  [mapping]
  (vec (for [[input {:keys [source value]}] (sort-by key mapping)
             :when (and source (not= :unmapped source))]
         (cond-> {:input input :source source}
           (not= :raw-body source) (assoc :value (str value))))))

(defn whole-number
  "A field's value as a positive whole number, nil when it isn't one."
  [value]
  (let [text
        (string/trim (str value))]
    (when (re-matches #"[1-9]\d*" text)
      (js/parseInt text 10))))

(defn- auth-payload
  [{:keys [auth-type header-name username secret algorithm timestamp-header tolerance-seconds]}]
  (cond-> {:type auth-type}
    (#{:header :hmac} auth-type) (assoc :header-name (string/trim header-name))
    (= :basic auth-type) (assoc :username (string/trim username))
    (= :hmac auth-type) (assoc :algorithm algorithm)
    (and (= :hmac auth-type) (not (string/blank? timestamp-header)))
    (assoc :timestamp-header (string/trim timestamp-header) :tolerance-seconds (whole-number tolerance-seconds))
    (and (secret-types auth-type) (not (string/blank? secret))) (assoc :secret secret)))

(defn payload
  "The body of PUT /api/webhooks/<id> for a form."
  [{:keys [name description scope-kind team-id project-id workflow-id mapping allowed-ips allow-get
           rate-limit-per-minute enabled] :as form}]
  {:name (string/trim name)
   :description (string/trim description)
   :scope (cond-> {:kind scope-kind}
            (#{:team :project} scope-kind) (assoc :team-id team-id)
            (= :project scope-kind) (assoc :project-id project-id))
   :target {:team-id (target-team-id form) :workflow-id workflow-id}
   :auth (auth-payload form)
   :mapping (mapping-list mapping)
   :allowed-ips (lines allowed-ips)
   :allow-get (and (true? allow-get) (not (hmac? (:auth-type form))))
   :rate-limit-per-minute (whole-number rate-limit-per-minute)
   :enabled (true? enabled)})

(defn- unsigned-inputs
  "Inputs of an HMAC form mapped from what the signature doesn't cover."
  [{:keys [auth-type mapping]}]
  (when (hmac? auth-type)
    (sort (for [[input {:keys [source]}] mapping
                :when (unsigned-sources source)]
            input))))

(defn form-problems
  "What keeps a form from being saved, checked before it is sent."
  [{:keys [name scope-kind team-id project-id workflow-id auth-type header-name username secret hint
           rate-limit-per-minute timestamp-header tolerance-seconds allow-get]
    :as form}]
  (let [team
        (target-team-id form)

        unsigned
        (unsigned-inputs form)]
    (vec (remove nil?
                 [(when (string/blank? name) "Give the webhook a name")
                  (when (and (#{:team :project} scope-kind) (nil? team-id)) "Choose a team")
                  (when (and (= :project scope-kind) (nil? project-id)) "Choose a project")
                  (when (or (nil? team) (nil? workflow-id)) "Choose the workflow it starts")
                  (when (and (#{:header :hmac} auth-type) (string/blank? header-name)) "Name the header")
                  (when (and (= :basic auth-type) (string/blank? username)) "Basic auth needs a username")
                  (when (and (secret-types auth-type) (string/blank? secret) (string/blank? hint))
                    "Enter or generate a secret")
                  (when (seq unsigned)
                    (str "An HMAC signature covers only the body: take " (string/join ", " unsigned)
                         " from the body or a fixed value"))
                  (when (and (hmac? auth-type) allow-get)
                    "An HMAC webhook can't accept GET: there is no body to sign")
                  (when (and (hmac? auth-type) (not (string/blank? timestamp-header)) (nil? (whole-number tolerance-seconds)))
                    "The tolerance is a whole number of seconds")
                  (when (nil? (whole-number rate-limit-per-minute))
                    "Requests a minute is a whole number above zero")]))))

(defn generated-secret
  "A random token from 32 bytes of the browser's CSPRNG."
  []
  (let [bytes
        (js/Uint8Array. 32)]
    (.getRandomValues js/crypto bytes)
    (-> (js/btoa (apply str (map #(js/String.fromCharCode %) (array-seq bytes))))
        (string/replace "+" "-")
        (string/replace "/" "_")
        (string/replace "=" ""))))

(defn- path-segments
  [path]
  (let [cleaned
        (-> (str path)
            (string/replace #"^\$\.?" "")
            (string/replace #"\[(\d+)\]" ".$1")
            (string/replace #"^/" "")
            (string/replace #"^\." ""))]
    (when-not (string/blank? cleaned)
      (vec (string/split cleaned #"[./]")))))

(defn sample-body
  "A sample JSON body holding a placeholder at every body path of mapping."
  [mapping]
  (let [data
        (reduce (fn [acc {:keys [input source value]}]
                  (let [segments
                        (when (= :body source) (path-segments value))]
                    (if (seq segments)
                      (try
                        (assoc-in acc segments (str "Example " (format/label input) " from the caller"))
                        (catch :default _
                          acc))
                      acc)))
                {}
                (mapping-list mapping))]
    (.stringify js/JSON (clj->js (if (seq data) data {:example "value"})) nil 2)))

(defn parse-headers
  "{name value} of \"Name: value\" lines."
  [text]
  (into {}
        (for [line (string/split-lines (str text))
              :let [[header-name value] (string/split line #":" 2)]
              :when (and value (not (string/blank? header-name)))]
          [(string/trim header-name) (string/trim value)])))

(defn parse-query
  "{name value} of a query string (\"a=1&b=2\", with or without ?)."
  [text]
  (into {}
        (for [pair (string/split (string/replace (str text) #"^\?" "") #"&")
              :let [[k v] (string/split pair #"=" 2)]
              :when (not (string/blank? k))]
          [(js/decodeURIComponent (string/trim k)) (js/decodeURIComponent (string/trim (or v "")))])))

(defn- lower-keys
  [m]
  (into {} (map (fn [[k v]] [(string/lower-case (name k)) v])) m))

(defn preview-query
  "The :preview-webhook query of a target and mapping for a sample request."
  [target mapping {:keys [body headers query]}]
  {:query-id :preview-webhook
   :target target
   :mapping mapping
   :request {:body (str body)
             :headers (assoc (lower-keys (parse-headers headers)) "content-type" "application/json")
             :query (parse-query query)}})

(defn preview-rows
  "A row per input of the workflow: where it comes from, the value the
   sample maps to and its error."
  [fields mapping {:keys [inputs errors]}]
  (let [by-input
        (into {} (map (juxt (comp name :input) identity)) mapping)

        error-of
        (fn [input]
          (some #(when (= input (some-> (:field %) name)) (:message %)) errors))]
    (vec (for [{:keys [input required]} fields
               :let [row (get by-input input)
                     value (get inputs (keyword input) (get inputs input))]]
           {:input input
            :source (or (format/kw (:source row)) :unmapped)
            :required required
            :value value
            :error (error-of input)}))))

(defn general-errors
  "Errors of a preview that name no input."
  [{:keys [errors]}]
  (vec (keep #(when (nil? (:field %)) (:message %)) errors)))

(defn- shell-quote
  [text]
  (str "'" (string/replace (str text) "'" "'\\''") "'"))

(defn- header-flag
  [header value]
  (str "-H \"" header ": " value "\""))

(defn curl-command
  "A copyable curl command calling the webhook at url with auth (secrets as
   shell variables) and body."
  [{:keys [url auth body method]}]
  (let [{:keys [type header-name username algorithm timestamp-header]}
        auth

        type
        (format/kw type)

        get?
        (= :get (format/kw method))

        algorithm
        (name (or algorithm :sha256))

        stamp?
        (and (= :hmac type) (not (string/blank? timestamp-header)))

        flags
        (remove nil?
                [(str "curl -X " (if get? "GET" "POST") " " (shell-quote url))
                 (when-not get? (header-flag "Content-Type" "application/json"))
                 (case type
                   :header (header-flag header-name "$WEBHOOK_SECRET")
                   :bearer (header-flag "Authorization" "Bearer $WEBHOOK_TOKEN")
                   :basic (str "-u \"" username ":$WEBHOOK_PASSWORD\"")
                   :hmac (header-flag header-name (str algorithm "=$SIGNATURE"))
                   nil)
                 (when stamp? (header-flag timestamp-header "$TIMESTAMP"))
                 (when-not get? "--data \"$BODY\"")])

        prelude
        (remove nil?
                [(when-not get? (str "BODY=" (shell-quote (or body "{}"))))
                 (when stamp? "TIMESTAMP=$(date +%s)")
                 (when (= :hmac type)
                   (str "SIGNATURE=$(printf '%s' \"" (when stamp? "$TIMESTAMP.") "$BODY\" | openssl dgst -" algorithm
                        " -hmac \"$WEBHOOK_SECRET\" | sed 's/^.* //')"))])]
    (string/join "\n" (concat prelude [(string/join " \\\n  " flags)]))))

(defn scope-label
  "Where a webhook belongs, as people read it."
  [team-name {:keys [kind team-id project-id]}]
  (case (format/kw kind)
    :tenant "Tenant"
    :team (str "Team " (team-name team-id))
    :project (str "Project " project-id " · " (team-name team-id))
    "—"))

(defn status-color
  [status]
  (cond
    (nil? status) "default"
    (<= 200 status 299) "success"
    (#{401 403 429} status) "warning"
    :else "error"))

(defn test-body
  "The body of POST /api/webhooks/<id>/test for the Test panel's sample."
  [{:keys [method body headers query]}]
  {:method (if (= :get (format/kw method)) "GET" "POST")
   :body (str body)
   :headers (parse-headers headers)
   :query (parse-query query)})

(defn run-id-of
  "The run a test invocation started, from its answer."
  [{:keys [status body]}]
  (when (= 202 status)
    (:run-id body)))

(defn- ui
  [db k default]
  (data/ui db :webhooks k default))

(rf/reg-event-db
 ::init
 (fn [db _]
   (data/reset-ui (data/enter db) :webhooks)))

(defn- open-dialog
  [db dialog]
  (-> db
      (data/set-ui :webhooks :dialog dialog)
      (data/clear-error ::save)))

(rf/reg-event-db
 ::open-create
 (fn [db [_ targets team-id project-id refetch]]
   (open-dialog db {:mode :create
                    :id (random-uuid)
                    :refetch refetch
                    :form (new-form targets team-id project-id)})))

(rf/reg-event-db
 ::open-edit
 (fn [db [_ webhook refetch]]
   (open-dialog db {:mode :edit
                    :id (:id webhook)
                    :refetch refetch
                    :form (form-of webhook)})))

(rf/reg-event-fx
 ::edit
 (fn [_ [_ id refetch]]
   {:fx [[:dispatch [::data/run {:id [::edit id]
                                 :error ::action
                                 :query {:query-id :get-webhook :webhook-id id}
                                 :on-success [::edit-loaded refetch]}]]]}))

(rf/reg-event-fx
 ::edit-loaded
 (fn [_ [_ refetch webhook]]
   {:fx [[:dispatch [::open-edit webhook refetch]]]}))

(rf/reg-event-db
 ::close
 (fn [db _]
   (data/set-ui db :webhooks :dialog nil)))

(defn- signed-mapping
  "mapping without the sources an HMAC signature doesn't cover."
  [mapping]
  (into {} (remove (fn [[_ {:keys [source]}]] (unsigned-sources source))) mapping))

(defn set-field
  "The form with k set to value, and what follows from it. targets says
   which workflows a project's webhook may keep."
  [targets form k value]
  (let [changed
        (assoc form k value)]
    (case k
      :scope-kind (kept-workflow targets
                                 (cond-> changed
                                   (= :tenant value) (assoc :team-id nil :project-id nil)
                                   (not= :project value) (assoc :project-id nil)))
      :project-id (kept-workflow targets changed)
      :team-id (assoc changed :project-id nil :target-team-id value :workflow-id nil :mapping {})
      :target-team-id (assoc changed :workflow-id nil :mapping {})
      :workflow-id (assoc changed :mapping {})
      :auth-type (cond-> (assoc changed :header-name (get default-header-names value (:header-name form)))
                   (hmac? value) (assoc :allow-get false :mapping (signed-mapping (:mapping form))))
      changed)))

(rf/reg-event-db
 ::set-field
 (fn [db [_ k value]]
   (update-in db [::data/ui :webhooks :dialog :form]
              #(set-field (data/loaded-data db targets-key) % k value))))

(rf/reg-event-db
 ::set-mapping
 (fn [db [_ input k value]]
   (assoc-in db [::data/ui :webhooks :dialog :form :mapping input k] value)))

(rf/reg-event-db
 ::generate-secret
 (fn [db _]
   (assoc-in db [::data/ui :webhooks :dialog :form :secret] (generated-secret))))

(defn save-spec
  [{:keys [id form refetch]}]
  {:id ::save
   :method "PUT"
   :path (str "/api/webhooks/" id)
   :body (payload form)
   :edd? true
   :refetch (into [targets-key (detail-key id)] refetch)
   :on-success [::saved id]})

(rf/reg-event-fx
 ::save
 (fn [{:keys [db]} _]
   {:fx [[:dispatch [::data/run (save-spec (ui db :dialog nil))]]]}))

(defn selecting
  "db with webhook id (nil: none) selected: what the Test panel showed of
   another webhook, a secret revealed for it and its errors go."
  [db id]
  (reduce data/clear-error
          (-> db
              (data/set-ui :webhooks :selected (some-> id str))
              (data/set-ui :webhooks :sample nil)
              (data/set-ui :webhooks :preview nil)
              (data/set-ui :webhooks :test-result nil)
              (data/set-ui :webhooks :revealed nil)
              (data/set-ui :webhooks :open-deliveries #{}))
          [::preview ::send-test ::rotate]))

(rf/reg-event-db
 ::saved
 (fn [db [_ id]]
   (-> (selecting db id)
       (data/set-ui :webhooks :dialog nil))))

(defn delete-request
  [{:keys [id name]} refetch]
  {:title (str "Delete the webhook " name "?")
   :body "Callers of its URL get 404 from now on. Runs it started stay."
   :confirm "Delete"
   :run {:id [::delete id]
         :error ::action
         :command {:cmd-id :delete-webhook :id id}
         :refetch refetch
         :on-success [::deleted id]}})

(rf/reg-event-fx
 ::delete
 (fn [_ [_ webhook refetch]]
   {:fx [[:dispatch [::confirm/ask (delete-request webhook refetch)]]]}))

(defn selected?
  [db id]
  (= (some-> id str) (ui db :selected nil)))

(rf/reg-event-db
 ::deleted
 (fn [db [_ id]]
   (if (selected? db id)
     (selecting db nil)
     db)))

(rf/reg-event-db
 ::select
 (fn [db [_ id]]
   (selecting db id)))

(defn rotate-request
  "Asked before a webhook's secret is replaced: callers are refused at once."
  [{:keys [id name]}]
  {:title (str "Regenerate the secret of " name "?")
   :body "Callers still using the current secret are refused at once. The new secret is shown once, for you to give to them."
   :confirm "Regenerate"
   :run {:id ::rotate
         :method "POST"
         :path (str "/api/webhooks/" id "/rotate")
         :body {}
         :refetch [(detail-key id)]
         :on-success [::rotated id]}})

(rf/reg-event-fx
 ::rotate
 (fn [_ [_ webhook]]
   {:fx [[:dispatch [::confirm/ask (rotate-request webhook)]]]}))

(defn answered
  "db with the reply about webhook id stored under k; a reply about a
   webhook that is no longer the selected one changes nothing."
  [db id k value]
  (if (selected? db id)
    (data/set-ui db :webhooks k value)
    db))

(rf/reg-event-db
 ::rotated
 (fn [db [_ id {:keys [secret]}]]
   (answered db id :revealed {:id (str id) :secret secret})))

(rf/reg-event-db
 ::dismiss-secret
 (fn [db _]
   (data/set-ui db :webhooks :revealed nil)))

(defn sample-changed
  "db with the Test panel's sample request changed: a preview of the
   earlier one no longer says what this one maps to."
  [db sample]
  (-> db
      (data/set-ui :webhooks :sample sample)
      (data/set-ui :webhooks :preview nil)
      (data/clear-error ::preview)))

(rf/reg-event-db
 ::set-sample
 (fn [db [_ sample k value]]
   (sample-changed db (assoc sample k value))))

(rf/reg-event-fx
 ::preview
 (fn [_ [_ id target mapping sample]]
   {:fx [[:dispatch [::data/run {:id ::preview
                                 :query (preview-query target mapping sample)
                                 :on-success [::previewed id]}]]]}))

(rf/reg-event-db
 ::previewed
 (fn [db [_ id result]]
   (answered db id :preview result)))

(rf/reg-event-fx
 ::send-test
 (fn [{:keys [db]} [_ id sample]]
   {:db (-> (sample-changed db sample)
            (data/set-ui :webhooks :test-result nil))
    :fx [[:dispatch [::data/run {:id ::send-test
                                 :method "POST"
                                 :path (str "/api/webhooks/" id "/test")
                                 :body (test-body sample)
                                 :refetch [(detail-key id)]
                                 :on-success [::tested id]}]]]}))

(rf/reg-event-db
 ::tested
 (fn [db [_ id result]]
   (answered db id :test-result result)))

(defn toggled
  "open with id added, or removed when it was there."
  [open id]
  (let [open
        (or open #{})]
    (if (contains? open id) (disj open id) (conj open id))))

(rf/reg-event-db
 ::toggle-delivery
 (fn [db [_ id]]
   (data/set-ui db :webhooks :open-deliveries (toggled (ui db :open-deliveries #{}) id))))

(rf/reg-fx
 ::write-clipboard
 (fn [{:keys [text what]}]
   (if-let [clipboard
            (some-> js/navigator .-clipboard)]
     (-> (.writeText clipboard text)
         (.then #(rf/dispatch [::copied what true]))
         (.catch #(rf/dispatch [::copied what false])))
     (rf/dispatch [::copied what false]))))

(rf/reg-event-fx
 ::copy
 (fn [_ [_ what text]]
   {::write-clipboard {:what what :text text}}))

(rf/reg-event-db
 ::copied
 (fn [db [_ what ok?]]
   (data/set-ui db :webhooks :copied {:what what :ok? ok?})))

(defn copy-hint
  "The tooltip of the copy button named what, after the last copy."
  [{:keys [ok?] :as copied} what]
  (cond
    (not= what (:what copied)) "Copy"
    ok? "Copied"
    :else "Couldn't copy here; select the text instead"))
