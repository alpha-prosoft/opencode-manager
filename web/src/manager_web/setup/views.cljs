(ns manager-web.setup.views
  (:require ["@mui/material/Button" :default Button]
            ["@mui/material/Container" :default Container]
            ["@mui/material/Paper" :default Paper]
            ["@mui/material/Step" :default Step]
            ["@mui/material/StepContent" :default StepContent]
            ["@mui/material/StepLabel" :default StepLabel]
            ["@mui/material/Stepper" :default Stepper]
            [clojure.string :as string]
            [manager-web.components.credential-form :as credential-form]
            [manager-web.components.credential-scope :as scope]
            [manager-web.components.layout :as layout]
            [manager-web.components.required-credentials :as required-credentials]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [manager-web.setup.elements :as els]
            [manager-web.setup.events :as events]
            [manager-web.setup.subs :as subs]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawStack RawTypography]]))

(defn step-hint
  [me {:keys [personal-only] :as credential}]
  (cond
    (required-credentials/required? credential) (required-credentials/required-by-text me credential)
    personal-only "Needed by the tools that use it. Each person uses their own."
    :else (str "Optional. " (scope/explanation credential))))

(defn- skip-button
  [{:keys [id state]} finishing?]
  [:> Button {:data-testid (el/id (els/skip id))
              :loading finishing?
              :on-click #(rf/dispatch [::events/advance])}
   (if (format/shared-source? state) "Use the shared account" "Skip for now")])

(defn- credential-step
  "A Step element itself, not a component: the Stepper numbers its direct children."
  [me {:keys [id name description kind help test] :as credential} saved finishing?]
  [:> Step {:completed (contains? saved id) :data-testid (el/id (els/step id))
            :data-required (str (events/owed? credential saved))}
   [:> StepLabel {:optional (r/as-element [RawTypography {:variant "caption"
                                                          :color (if (events/owed? credential saved) "warning.main" "text.secondary")}
                                           (step-hint me credential)])}
    name]
   [:> StepContent
    [RawStack {:spacing 2 :sx (:form-top theme/sx)}
     (when-not (string/blank? description)
       [RawTypography {} description])
     ^{:key id}
     [credential-form/credential-form
      {:name name
       :kind kind
       :help help
       :checks? (some? test)
       :scope :mine
       :credential-id id
       :on-saved [::events/saved id]
       :extra-actions (if (events/owed? credential saved)
                        []
                        [[skip-button credential finishing?]])}]]]])

(defn- steps
  [credentials]
  (let [pending
        (events/pending-credentials credentials)

        step
        @(rf/subscribe [::subs/step])

        saved
        @(rf/subscribe [::subs/saved])

        finishing?
        (or @(rf/subscribe [::data/busy? ::events/finish])
            @(rf/subscribe [::data/busy? ::events/leave]))

        me
        @(rf/subscribe [::session/me])

        owes?
        (events/owes-required? pending saved)]
    (if (empty? pending)
      [RawAlert {:severity "success" :el-id els/all-set
                 :action (r/as-element [:> Button {:data-testid (el/id els/continue)
                                                   :on-click #(rf/dispatch [::events/go-next])}
                                        "Continue"])}
       "Everything is set up."]
      [:> Paper {:sx (:setup-paper theme/sx)}
       (into [:> Stepper {:activeStep step :orientation "vertical"}]
             (for [credential pending]
               (with-meta (credential-step me credential saved finishing?) {:key (str (:id credential))})))
       [RawStack {:direction "row" :spacing 2 :sx (:setup-later theme/sx)}
        [:> Button {:data-testid (el/id els/later) :loading finishing? :disabled owes? :sx (:one-line theme/sx)
                    :on-click #(rf/dispatch [::events/finish])}
         "Do this later"]
        (when owes?
          [RawTypography {:variant "body2" :color "text.secondary" :el-id els/required-note}
           "Your teams require the credentials marked required. The platform opens once you have added them."])]])))

(defn- leave-error
  []
  (when-let [message
             @(rf/subscribe [::data/error ::events/leave])]
    [RawAlert {:severity "error" :el-id els/leave-error
               :action (r/as-element [:> Button {:color "inherit" :size "small" :data-testid (el/id els/leave-retry)
                                                 :on-click #(rf/dispatch [::events/go-next])}
                                      "Try again"])}
     (str "Could not check your credentials before going on: " message)]))

(defn- signed-in
  "Who is being set up and the ways out: setup has no navigation frame.
   Those who administer the tenant or the platform keep those pages."
  []
  (let [me
        @(rf/subscribe [::session/me])]
    [RawStack {:direction "row" :spacing 1 :sx (:setup-identity theme/sx)}
     (when me
       [RawTypography {:variant "body2" :color "text.secondary" :el-id els/signed-in}
        (str "Signed in as " (session/display-name me) " · " (roles/tenant-label me))])
     (when (roles/tenant-admin? me)
       [:> Button (merge (layout/nav-props :admin) {:size "small" :data-testid (el/id els/admin-link)})
        "Admin"])
     (when (roles/operator? me)
       [:> Button (merge (layout/nav-props :tenants) {:size "small" :data-testid (el/id els/tenants-link)})
        "Tenants"])
     [:> Button {:size "small" :data-testid (el/id els/logout)
                 :loading @(rf/subscribe [::data/busy? ::session/logout])
                 :on-click #(rf/dispatch [::session/logout])}
      "Sign out"]]))

(defn main-panel
  []
  [:> Container {:maxWidth "lg" :sx (:container theme/sx)}
   [layout/page-stack
    [layout/page-header {:title "Connect your accounts"
                         :el-id els/page
                         :description "Some tools your teams use act on your behalf, so they need your own credentials. You can change these any time under Accounts."}]
    [signed-in]
    [leave-error]
    [data/loaded {:key :my-credentials :query required-credentials/query :error-el-id els/error}
     steps]]])
