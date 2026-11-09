(ns manager-web.webhooks.views
  "Webhooks of the tenant, a team or a project: the list, the create/edit
   dialog with its mapping editor, and a webhook's details with its curl
   example, Test panel and deliveries. The page /<tenant>/webhooks and the
   Webhooks tabs of team and project pages render `webhooks-section`."
  (:require ["@mui/icons-material/Add" :default AddIcon]
            ["@mui/icons-material/ContentCopy" :default CopyIcon]
            ["@mui/icons-material/WebhookOutlined" :default WebhookIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/DialogActions" :default DialogActions]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogTitle" :default DialogTitle]
            ["@mui/material/FormControlLabel" :default FormControlLabel]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/Link" :default Link]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Switch" :default Switch]
            ["@mui/material/TextField" :default TextField]
            ["@mui/material/Tooltip" :default Tooltip]
            ["@mui/material/Typography" :default Typography]
            [clojure.string :as string]
            [manager-web.components.layout :as layout]
            [manager-web.components.team-context :as team-context]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [manager-web.theme :as theme]
            [manager-web.webhooks.elements :as els]
            [manager-web.webhooks.events :as events]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(defn- copy-button
  [text el-id]
  (let [copied
        @(rf/subscribe [::data/ui :webhooks :copied nil])]
    [:> Tooltip {:title (events/copy-hint copied el-id)}
     [:> IconButton {:size "small" :aria-label "Copy" :data-testid (el/id el-id)
                     :data-copied (str (and (= el-id (:what copied)) (true? (:ok? copied))))
                     :on-click #(rf/dispatch [::events/copy el-id text])}
      [:> CopyIcon {:fontSize "small"}]]]))

(defn- origin
  []
  (.. js/window -location -origin))

(defn- text-field
  [props]
  [:> TextField (merge {:fullWidth true :size "small"} props)])

(defn- select-field
  [{:keys [field] :as props}]
  [layout/select (merge {:fullWidth true :option-el-id #(els/option field %)}
                        (dissoc props :field))])

(defn- set-field
  [k]
  #(rf/dispatch-sync [::events/set-field k %]))

(defn- set-keyword
  [k]
  #(rf/dispatch-sync [::events/set-field k (keyword %)]))

(defn- target-value
  [e]
  (.. e -target -value))

(defn- scope-fields
  [{:keys [scope-kind team-id project-id target-team-id]} targets editing?]
  (let [managed
        (events/managed-teams targets)

        team
        (events/team-of targets team-id)]
    [RawStack {:spacing 2}
     [select-field {:label "Belongs to" :field :scope :id (el/id els/dialog-scope) :disabled editing?
                    :helperText (if editing? "The scope of a webhook can't change." "Who manages it and where it is listed.")
                    :value scope-kind :options (events/scope-options targets)
                    :on-change (set-keyword :scope-kind)}]
     (when (#{:team :project} scope-kind)
       [select-field {:label "Team" :field :team :id (el/id els/dialog-team) :disabled editing?
                      :value team-id :options (mapv (juxt :id :name) managed)
                      :on-change (set-field :team-id)}])
     (when (= :project scope-kind)
       [select-field {:label "Project" :field :project :id (el/id els/dialog-project) :disabled editing?
                      :value project-id :options (mapv (juxt :id #(or (:name %) (:id %))) (:projects team))
                      :on-change (set-field :project-id)}])
     (when (= :tenant scope-kind)
       [select-field {:label "Team whose workflow it starts" :field :target-team :id (el/id els/dialog-team)
                      :value target-team-id :options (mapv (juxt :id :name) (:teams targets))
                      :on-change (set-field :target-team-id)}])]))

(defn- workflow-field
  [form targets]
  [select-field {:label "Starts workflow" :field :workflow :id (el/id els/dialog-workflow)
                 :helperText (str "Runs start as the platform in the team's scope, with the team's credentials."
                                  (when (= :project (:scope-kind form))
                                    " A project's webhook starts the project's workflows, or ones of no project."))
                 :value (:workflow-id form)
                 :options (mapv (juxt :id :name) (events/offered-workflows targets form))
                 :on-change (set-field :workflow-id)}])

(defn- secret-field
  [{:keys [auth-type secret hint]} editing?]
  [RawStack {:direction "row" :spacing 1 :sx (:start-aligned theme/sx)}
   [text-field {:label (case auth-type :basic "Password" :bearer "Token" "Secret")
                :id (el/id els/dialog-secret)
                :value secret
                :autoComplete "off"
                :helperText (if (and editing? hint)
                              (str "Stored: " hint ". Leave empty to keep it.")
                              "Stored encrypted; it is shown only now. Copy it to the caller.")
                :on-change #(rf/dispatch-sync [::events/set-field :secret (target-value %)])}]
   [:> Button {:variant "outlined" :data-testid (el/id els/dialog-generate) :sx (:no-shrink theme/sx)
               :on-click #(rf/dispatch [::events/generate-secret])}
    "Generate"]])

(defn- auth-fields
  [{:keys [auth-type header-name username algorithm timestamp-header tolerance-seconds] :as form} editing?]
  [RawStack {:spacing 2}
   [select-field {:label "Authentication" :field :auth :id (el/id els/dialog-auth-type)
                  :helperText (some (fn [[id _ hint]] (when (= id auth-type) hint)) events/auth-types)
                  :value auth-type :options (mapv (juxt first second) events/auth-types)
                  :on-change (set-keyword :auth-type)}]
   (when (= :none auth-type)
     [RawAlert {:severity "warning" :el-id els/dialog-none-warning}
      "Anyone who learns the URL can start this workflow. Prefer a secret, or at least limit the allowed addresses."])
   (when (#{:header :hmac} auth-type)
     [text-field {:label (if (= :hmac auth-type) "Signature header" "Header name") :id (el/id els/dialog-header-name)
                  :value header-name :on-change #(rf/dispatch-sync [::events/set-field :header-name (target-value %)])}])
   (when (= :basic auth-type)
     [text-field {:label "Username" :id (el/id els/dialog-username) :value username :autoComplete "off"
                  :on-change #(rf/dispatch-sync [::events/set-field :username (target-value %)])}])
   (when (= :hmac auth-type)
     [RawStack {:direction {:xs "column" :sm "row"} :spacing 2}
      [select-field {:label "Algorithm" :field :algorithm :id (el/id els/dialog-algorithm)
                     :value algorithm :options [[:sha256 "HMAC-SHA256"] [:sha1 "HMAC-SHA1"]]
                     :on-change (set-keyword :algorithm)}]
      [text-field {:label "Timestamp header (optional)" :id (el/id els/dialog-timestamp-header)
                   :helperText "Signed as <timestamp>.<body>; refuses replays"
                   :value timestamp-header
                   :on-change #(rf/dispatch-sync [::events/set-field :timestamp-header (target-value %)])}]
      [text-field {:label "Tolerance (s)" :id (el/id els/dialog-tolerance) :type "number"
                   :value tolerance-seconds :disabled (string/blank? timestamp-header)
                   :on-change #(rf/dispatch-sync [::events/set-field :tolerance-seconds (target-value %)])}]])
   (when (events/secret-types auth-type)
     [secret-field form editing?])])

(defn- mapping-row
  [auth-type {:keys [input title description required]} {:keys [source value]}]
  (let [source
        (or source :unmapped)]
    [RawStack {:direction {:xs "column" :sm "row"} :spacing 1.5 :sx (:webhook-mapping-row theme/sx)}
     [RawBox {:sx (:webhook-mapping-label theme/sx)}
      [RawTypography {:variant "body2" :sx (:strong theme/sx)} (str title (when required " *"))]
      [RawTypography {:variant "caption" :color "text.secondary"} [layout/mono input]]]
     [select-field {:label "From" :field [:source input] :id (el/id (els/mapping-source input))
                    :helperText description
                    :value source :options (events/offered-sources auth-type source)
                    :on-change #(rf/dispatch-sync [::events/set-mapping input :source (keyword %)])}]
     (when-not (#{:unmapped :raw-body} source)
       [text-field {:label (if (= :body source) "Path" "Value") :id (el/id (els/mapping-value input))
                    :placeholder (get events/source-hints source)
                    :value (or value "")
                    :on-change #(rf/dispatch-sync [::events/set-mapping input :value (target-value %)])}])]))

(defn- mapping-editor
  [{:keys [mapping] :as form} targets]
  (let [workflow
        (events/workflow-of targets (events/target-team-id form) (:workflow-id form))

        fields
        (events/input-fields workflow)]
    (when workflow
      [RawStack {:spacing 1.5}
       [RawTypography {:variant "h3"} "Inputs"]
       [RawTypography {:variant "body2" :color "text.secondary"}
        (str "Where each input of the workflow comes from. Body paths look like $.pull_request.title or /issue/key."
             (when (events/hmac? (:auth-type form))
               " An HMAC signature covers only the body, so headers and query parameters aren't offered."))]
       (if (seq fields)
         (into [:<>]
               (for [field fields]
                 ^{:key (:input field)}
                 [mapping-row (:auth-type form) field (get mapping (:input field))]))
         [RawTypography {:variant "body2"} "The workflow takes no inputs."])])))

(defn- limits-fields
  [{:keys [allowed-ips allow-get rate-limit-per-minute enabled auth-type]}]
  [RawStack {:spacing 2}
   [text-field {:label "Allowed addresses (optional)" :id (el/id els/dialog-allowed-ips) :multiline true :minRows 2
                :helperText "IPs or CIDR ranges, one per line. Empty allows every address."
                :value allowed-ips :on-change #(rf/dispatch-sync [::events/set-field :allowed-ips (target-value %)])}]
   [RawStack {:direction {:xs "column" :sm "row"} :spacing 2 :sx (:centered-row theme/sx)}
    [text-field {:label "Requests a minute" :id (el/id els/dialog-rate-limit) :type "number"
                 :value rate-limit-per-minute
                 :on-change #(rf/dispatch-sync [::events/set-field :rate-limit-per-minute (target-value %)])}]
    (when-not (events/hmac? auth-type)
      [:> FormControlLabel {:label "Accept GET (query parameters)"
                            :control (r/as-element [:> Switch {:checked allow-get :id (el/id els/dialog-allow-get)
                                                               :on-change #(rf/dispatch [::events/set-field :allow-get (.. % -target -checked)])}])}])
    [:> FormControlLabel {:label "Enabled"
                          :control (r/as-element [:> Switch {:checked enabled :id (el/id els/dialog-enabled)
                                                             :on-change #(rf/dispatch [::events/set-field :enabled (.. % -target -checked)])}])}]]])

(defn- curl-example
  [url auth body el-id copy-el-id]
  (let [command
        (events/curl-command {:url url :auth auth :body body})]
    [RawStack {:spacing 0.5}
     [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
      [RawTypography {:variant "label" :sx (:grow theme/sx)} "Example call"]
      [copy-button command copy-el-id]]
     [RawBox {:component "pre" :sx (:code-block-short theme/sx) :data-testid (el/id el-id)} command]]))

(defn webhook-dialog
  [targets]
  (let [{:keys [mode id form] :as dialog}
        @(rf/subscribe [::data/ui :webhooks :dialog nil])

        busy?
        @(rf/subscribe [::data/busy? ::events/save])

        error
        @(rf/subscribe [::data/error ::events/save])

        editing?
        (= :edit mode)

        problems
        (when form (events/form-problems form))

        close
        #(when-not busy? (rf/dispatch [::events/close]))]
    [:> Dialog {:open (some? dialog) :on-close close :fullWidth true :maxWidth "md"
                :data-testid (el/id els/dialog)}
     (when form
       [:form {:on-submit (fn [e]
                            (.preventDefault e)
                            (when (empty? problems)
                              (rf/dispatch [::events/save])))}
        [:> DialogTitle (if editing? (str "Edit " (:name form)) "New webhook")]
        [:> DialogContent
         [RawStack {:spacing 3 :sx (:form-top theme/sx)}
          [RawStack {:spacing 2}
           [text-field {:label "Name" :id (el/id els/dialog-name) :required true :value (:name form)
                        :on-change #(rf/dispatch-sync [::events/set-field :name (target-value %)])}]
           [text-field {:label "Description" :id (el/id els/dialog-description) :value (:description form)
                        :on-change #(rf/dispatch-sync [::events/set-field :description (target-value %)])}]]
          [scope-fields form targets editing?]
          [workflow-field form targets]
          [auth-fields form editing?]
          [mapping-editor form targets]
          [limits-fields form]
          [curl-example (str (origin) "/hooks/" (:tenant targets) "/" id)
           (:auth (events/payload form))
           (events/sample-body (:mapping form))
           els/dialog-curl els/dialog-copy-curl]
          (when (and (seq problems) (not (string/blank? (:name form))))
            [RawAlert {:severity "info" :el-id els/dialog-problems} (string/join " · " problems)])
          (when error
            [RawAlert {:severity "error" :el-id els/dialog-error} error])]]
        [:> DialogActions
         [:> Button {:on-click close :disabled busy? :data-testid (el/id els/dialog-cancel)} "Cancel"]
         [:> Button {:type "submit" :variant "contained" :loading busy? :disabled (boolean (seq problems))
                     :data-testid (el/id els/dialog-save)}
          (if editing? "Save" "Create webhook")]]])]))

(defn- last-delivery-chip
  [{:keys [id last-delivery]}]
  (if last-delivery
    [:> Chip {:color (events/status-color (:status last-delivery))
              :label (str (:status last-delivery) " · " (format/relative-time (:at last-delivery)))
              :data-testid (el/id (els/last-status id))
              :data-status (str (:status last-delivery))}]
    [:> Chip {:label "No calls yet" :data-testid (el/id (els/last-status id)) :data-status "none"}]))

(defn- team-name
  [me team-id]
  (roles/team-name me team-id))

(defn- workflow-name
  [targets {:keys [team-id workflow-id]}]
  (or (:name (events/workflow-of targets team-id workflow-id)) workflow-id))

(defn- webhook-row
  [me targets refetch selected {:keys [id name enabled scope target auth-type can-manage] :as webhook}]
  [layout/table-row {:data-testid (el/id (els/row id)) :hover true
                     :selected (= selected (str id)) :data-selected (str (= selected (str id)))}
   [layout/cell
    [:> Link {:component "button" :type "button" :sx (:webhook-name-link theme/sx) :data-testid (el/id (els/open id))
              :on-click #(rf/dispatch [::events/select id])}
     name]
    [RawTypography {:variant "caption" :color "text.secondary" :component "div"}
     (str (events/scope-label #(team-name me %) scope) " · starts " (team-name me (:team-id target)) " / "
          (workflow-name targets target))]]
   [layout/cell (events/auth-label auth-type)]
   [layout/cell [:> Chip {:label (if enabled "Enabled" "Disabled") :color (if enabled "success" "default")
                          :data-testid (el/id (els/enabled id)) :data-state (if enabled "enabled" "disabled")}]]
   [layout/cell [last-delivery-chip webhook]]
   [layout/cell {:align "right" :sx (:nowrap theme/sx)}
    (when can-manage
      [:<>
       [:> Button {:size "small" :data-testid (el/id (els/edit id))
                   :loading @(rf/subscribe [::data/busy? [::events/edit id]])
                   :on-click #(rf/dispatch [::events/edit id refetch])}
        "Edit"]
       [:> Button {:size "small" :color "error" :data-testid (el/id (els/delete id))
                   :on-click #(rf/dispatch [::events/delete webhook refetch])}
        "Delete"]])]])

(defn- webhook-table
  [me targets refetch selected webhooks]
  (if (empty? webhooks)
    [layout/empty-state {:title "No webhooks yet" :el-id els/empty-list}
     "A webhook gives other systems a URL that starts a workflow, with the credentials you choose."]
    [layout/table {:el-id els/table}
     (layout/head "Webhook" "Auth" "State" "Last call" "")
     (for [webhook webhooks]
       ^{:key (str (:id webhook))}
       [webhook-row me targets refetch selected webhook])]))

(defn- auth-text
  [{:keys [type header-name username algorithm timestamp-header hint]}]
  (let [type
        (format/kw type)]
    (str (events/auth-label type)
         (case type
           :header (str " · header " header-name)
           :basic (str " · user " username)
           :hmac (str " · " (some-> algorithm name) " in " header-name
                      (when timestamp-header (str ", timestamp in " timestamp-header)))
           "")
         (when hint (str " · secret " hint)))))

(defn- revealed-secret
  [webhook-id]
  (when-let [{:keys [id secret]}
             @(rf/subscribe [::data/ui :webhooks :revealed nil])]
    (when (= id (str webhook-id))
      [RawAlert {:severity "success" :el-id els/revealed-secret
                 :action (r/as-element [:> Button {:size "small" :data-testid (el/id els/revealed-dismiss)
                                                   :on-click #(rf/dispatch [::events/dismiss-secret])}
                                        "Done"])}
       [RawStack {:spacing 0.5}
        [RawTypography {:variant "body2"} "The new secret, shown only now. The old one stopped working."]
        [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
         [RawTypography {:variant "mono" :sx (:mono-wrap theme/sx) :data-secret secret} secret]
         [copy-button secret els/copy-secret]]]])))

(defn- preview-table
  [fields mapping preview]
  (let [rows
        (events/preview-rows fields mapping preview)]
    [RawStack {:spacing 1 :el-id els/preview-result :data-valid (str (boolean (:valid preview)))}
     [layout/table {}
      (layout/head "Input" "From" "Value")
      (for [{:keys [input source value error]} rows]
        ^{:key input}
        [layout/table-row {:data-testid (el/id (els/preview-input input)) :data-state (if error "error" "ok")}
         [layout/cell [layout/mono input]]
         [layout/cell (some (fn [[id label]] (when (= id source) label)) events/sources)]
         [layout/cell
          (if (some? value) [RawTypography {:variant "body2" :sx (:mono-wrap theme/sx)} (str value)] "—")
          (when error
            [RawTypography {:variant "caption" :color "error" :component "div"} error])]])]
     (for [message (events/general-errors preview)]
       ^{:key message}
       [RawAlert {:severity "warning"} message])]))

(defn- test-result
  [{:keys [status body request] :as result}]
  (let [run-id
        (events/run-id-of result)]
    [RawStack {:spacing 1 :el-id els/test-result}
     [layout/row
      [:> Chip {:color (events/status-color status) :label (str "HTTP " status)
                :data-testid (el/id els/test-status) :data-status (str status)}]
      [RawTypography {:variant "body2" :color "text.secondary"}
       (str (:method request) " " (:path request))]
      (when run-id
        [layout/link {:page :run :params {:run-id run-id} :el-id els/test-run-link} "Open the run"])]
     [layout/code-block {:short? true} (format/json body)]]))

(defn- test-panel
  [{:keys [id target mapping allow-get can-manage]} fields]
  (let [sample
        (or @(rf/subscribe [::data/ui :webhooks :sample nil])
            {:method :post
             :body (events/sample-body (into {} (map (juxt (comp name :input) #(select-keys % [:source :value]))) mapping))
             :headers ""
             :query ""})

        set-sample
        (fn [k]
          #(rf/dispatch-sync [::events/set-sample sample k (target-value %)]))

        previewing?
        @(rf/subscribe [::data/busy? ::events/preview])

        sending?
        @(rf/subscribe [::data/busy? ::events/send-test])

        preview
        @(rf/subscribe [::data/ui :webhooks :preview nil])

        result
        @(rf/subscribe [::data/ui :webhooks :test-result nil])

        preview-error
        @(rf/subscribe [::data/error ::events/preview])

        test-error
        @(rf/subscribe [::data/error ::events/send-test])]
    [layout/section {:title "Test" :el-id els/test-panel
                     :description (str "Preview maps the sample to inputs without starting anything."
                                       (when can-manage " Send test makes a real call from the manager to its own listener, signed with the stored secret, and starts a real run; it does not go through your router, so make one call from the other system too."))}
     [:> Paper {:sx (:panel theme/sx)}
      [RawStack {:spacing 2}
       (when allow-get
         [select-field {:label "Method" :field :method :id (el/id els/test-method)
                        :value (:method sample) :options [[:post "POST"] [:get "GET"]]
                        :on-change #(rf/dispatch [::events/set-sample sample :method (keyword %)])}])
       [text-field {:label "Body (JSON)" :id (el/id els/test-body) :multiline true :minRows 6
                    :value (:body sample) :on-change (set-sample :body)
                    :slotProps {:htmlInput {:sx (:mono-wrap theme/sx)}}}]
       [RawStack {:direction {:xs "column" :sm "row"} :spacing 2}
        [text-field {:label "Headers" :id (el/id els/test-headers) :multiline true :minRows 2
                     :placeholder "X-Event-Key: push"
                     :helperText "One Name: value per line. The service reads names in lower case and drops X-Forwarded-For."
                     :value (:headers sample) :on-change (set-sample :headers)}]
        [text-field {:label "Query" :id (el/id els/test-query) :placeholder "ref=main&dry=1"
                     :value (:query sample) :on-change (set-sample :query)}]]
       [layout/row
        [:> Button {:variant "outlined" :loading previewing? :data-testid (el/id els/preview)
                    :on-click #(rf/dispatch [::events/preview id target mapping sample])}
         "Preview inputs"]
        (when can-manage
          [:> Button {:variant "contained" :loading sending? :data-testid (el/id els/send-test)
                      :on-click #(rf/dispatch [::events/send-test id sample])}
           "Send test"])]
       [layout/error-alert preview-error els/preview-error]
       (when preview
         [preview-table fields mapping preview])
       [layout/error-alert test-error els/test-error]
       (when result
         [test-result result])]]]))

(defn- delivery-row
  [open? {:keys [id at status source-ip run-id error method test] :as delivery}]
  [:<>
   [layout/table-row {:data-testid (el/id (els/delivery id))}
    [layout/cell {:sx (:nowrap theme/sx)} (format/relative-time at)]
    [layout/cell [:> Chip {:color (events/status-color status) :label (str status)
                           :data-testid (el/id (els/delivery-status id)) :data-status (str status)}]]
    [layout/cell (str method (when test " · test"))]
    [layout/cell [layout/mono source-ip]]
    [layout/cell
     (if run-id
       [layout/link {:page :run :params {:run-id run-id} :el-id (els/delivery-run id)} "Run"]
       (or error "—"))]
    [layout/cell {:align "right"}
     [:> Button {:size "small" :data-testid (el/id (els/delivery-toggle id)) :on-click #(rf/dispatch [::events/toggle-delivery id])}
      (if open? "Hide" "Request")]]]
   (when open?
     [layout/table-row {}
      [layout/cell {:colSpan 6}
       [RawStack {:spacing 1 :el-id (els/delivery-details id)}
        (when (and run-id error) [RawTypography {:variant "body2"} error])
        [layout/code-block {:short? true}
         (string/join "\n" (for [{:keys [name value]} (:headers delivery)] (str name ": " value)))]
        (when (seq (:query delivery))
          [layout/code-block {:short? true}
           (string/join "&" (for [{:keys [name value]} (:query delivery)] (str name "=" value)))])
        [layout/code-block {:short? true}
         (str (:body delivery) (when (:body-truncated delivery) "\n… (cut short)"))]]]])])

(defn- deliveries
  [items]
  (let [open
        @(rf/subscribe [::data/ui :webhooks :open-deliveries #{}])]
    [layout/section {:title "Recent calls" :description "The last 50 calls, newest first. Secret headers are never logged."}
     (if (empty? items)
       [layout/empty-state {:title "No calls yet" :el-id els/deliveries-empty}
        "Calls show here with their answer and the run they started."]
       [RawBox {:data-testid (el/id els/deliveries) :data-statuses (string/join "," (map :status items))}
        [layout/table {}
         (layout/head "When" "Answer" "Method" "From" "Outcome" "")
         (for [delivery items]
           ^{:key (:id delivery)}
           [delivery-row (contains? open (:id delivery)) delivery])]])]))

(defn- brought-into-view
  "children, scrolled to when they appear: the details open below a list
   that may fill the screen."
  [& children]
  (r/with-let [show
               #(some-> % (.scrollIntoView #js {:behavior "smooth" :block "nearest"}))]
    (into [:div {:ref show}] children)))

(defn- detail-view
  [targets {:keys [id name description auth endpoint-path can-manage enabled mapping] :as webhook}]
  (let [url
        (str (origin) endpoint-path)

        rotating?
        @(rf/subscribe [::data/busy? ::events/rotate])

        rotate-error
        @(rf/subscribe [::data/error ::events/rotate])

        workflow
        (events/workflow-of targets (get-in webhook [:target :team-id]) (get-in webhook [:target :workflow-id]))

        mapping-form
        (into {} (map (juxt (comp clojure.core/name :input) #(select-keys % [:source :value]))) mapping)]
    [RawStack {:spacing 3 :el-id els/detail}
     [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
      [RawBox {:sx (:grow theme/sx)}
       [RawTypography {:variant "h2" :el-id els/detail-title} name]
       (when-not (string/blank? description)
         [RawTypography {:variant "body2" :color "text.secondary"} description])]
      (when-not enabled
        [:> Chip {:label "Disabled"}])
      [:> Button {:data-testid (el/id els/detail-close) :on-click #(rf/dispatch [::events/select nil])} "Close"]]
     [:> Paper {:sx (:panel theme/sx)}
      [RawStack {:spacing 1.5}
       [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
        [RawTypography {:variant "label"} "URL"]
        [RawTypography {:variant "mono" :sx (:mono-wrap theme/sx) :data-testid (el/id els/endpoint)} url]
        [copy-button url els/copy-endpoint]]
       [RawStack {:direction "row" :spacing 1 :sx (:centered-row theme/sx)}
        [RawTypography {:variant "body2" :sx (:grow theme/sx) :el-id els/auth-summary} (auth-text auth)]
        (when (and can-manage (events/secret-types (format/kw (:type auth))))
          [:> Button {:size "small" :variant "outlined" :loading rotating? :data-testid (el/id els/rotate)
                      :on-click #(rf/dispatch [::events/rotate webhook])}
           "Regenerate secret"])]
       [layout/error-alert rotate-error els/rotate-error]
       [revealed-secret id]
       [curl-example url auth (events/sample-body mapping-form) els/curl els/copy-curl]]]
     [test-panel webhook (events/input-fields workflow)]
     [deliveries (:deliveries webhook)]]))

(defn- selected-detail
  [targets selected]
  (when selected
    ^{:key selected}
    [brought-into-view
     [data/loaded {:key (events/detail-key selected)
                   :query {:query-id :get-webhook :webhook-id selected}
                   :interval 10000
                   :error-el-id els/detail-error}
      #(vector detail-view targets %)]]))

(defn webhooks-section
  "The webhooks of team-id and project-id (both optional), with the dialog
   and the selected webhook's details."
  [{:keys [team-id project-id title]}]
  (let [me
        @(rf/subscribe [::session/me])

        {targets :data targets-error :error}
        @(rf/subscribe [::data/load events/targets-key])

        selected
        @(rf/subscribe [::data/ui :webhooks :selected nil])

        key
        (events/list-key team-id project-id)

        refetch
        [key]

        action-error
        @(rf/subscribe [::data/error ::events/action])]
    [RawStack {:spacing 3 :el-id els/section}
     [data/poll {:key events/targets-key :query events/targets-query}]
     [layout/section {:title (or title "Webhooks")
                      :description "URLs other systems call to start a workflow. Runs start as the platform in the team's scope; people fork their sessions to continue."
                      :action (when (events/may-create? targets)
                                [:> Button {:variant "contained" :startIcon (r/as-element [:> AddIcon])
                                            :data-testid (el/id els/create)
                                            :on-click #(rf/dispatch [::events/open-create targets team-id project-id refetch])}
                                 "New webhook"])}
      [layout/error-alert action-error els/action-error]
      (when (and targets-error (nil? targets))
        [layout/error-alert (str "Could not read which workflows a webhook may start, so none can be added or edited: " targets-error)
         els/targets-error])
      [data/loaded {:key key
                    :query (cond-> {:query-id :list-webhooks}
                             team-id (assoc :team-id team-id)
                             project-id (assoc :project-id project-id))
                    :interval 15000
                    :error-el-id els/list-error}
       #(vector webhook-table me targets refetch selected %)]]
     [selected-detail targets selected]
     (when targets
       [webhook-dialog targets])]))

(defn- project-filter
  [targets team-id project-id]
  (let [projects
        (:projects (events/team-of targets team-id))]
    (when (seq projects)
      [select-field {:label "Project" :field :project-filter :id (el/id els/project-filter)
                     :sx (:filter-select theme/sx) :fullWidth false
                     :value (or project-id "all")
                     :options (into [["all" "All projects"]] (mapv (juxt :id #(or (:name %) (:id %))) projects))
                     :on-change #(layout/navigate! :webhooks nil (cond-> (team-context/query team-id)
                                                                   (not= "all" %) (assoc :project %)))}])))

(defn main-panel
  []
  (let [me
        @(rf/subscribe [::session/me])

        team-id
        (team-context/selected)

        project-id
        (:project @(rf/subscribe [:edd.subs/url-params]))

        targets
        @(rf/subscribe [::data/data events/targets-key])]
    [layout/page-stack
     [layout/page-header {:title "Webhooks"
                          :title-el-id els/title
                          :el-id els/page
                          :description "Let other systems start workflows: each webhook has its own URL, credentials and input mapping."
                          :actions [[layout/scope-chip (if team-id [:team (roles/team-name me team-id)] [:tenant (:tenant me)])
                                     els/scope]
                                    [:> WebhookIcon {:color "action"}]]}]
     (when team-id
       [project-filter targets team-id project-id])
     ^{:key (str team-id "/" project-id)}
     [webhooks-section {:team-id team-id :project-id (when team-id project-id) :title "All webhooks"}]
     (when-not team-id
       [:> Typography {:variant "caption" :color "text.secondary" :data-testid (el/id els/team-hint)}
        "Choose a team in the sidebar to see only its webhooks."])]))
