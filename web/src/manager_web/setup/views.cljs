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
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.setup.elements :as els]
            [manager-web.setup.events :as events]
            [manager-web.setup.subs :as subs]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawAlert RawBox RawStack RawTypography]]))

(defn step-hint
  [{:keys [required state]}]
  (cond
    required "Required for the tools that use it. Each person uses their own."
    (= :platform (format/kw state)) "Optional: a shared account is set up. Add yours to act as yourself."
    :else "Optional."))

(defn- credential-step
  "A Step element itself, not a component: the Stepper numbers its direct children."
  [{:keys [id name description kind help test state] :as credential} saved finishing?]
  [:> Step {:completed (contains? saved id) :data-testid (el/id (els/step id))}
   [:> StepLabel {:optional (r/as-element [RawTypography {:variant "caption"} (step-hint credential)])} name]
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
       :extra-actions [[:> Button {:data-testid (el/id (els/skip id))
                                   :loading finishing?
                                   :on-click #(rf/dispatch [::events/advance])}
                        (if (= :platform (format/kw state)) "Use the shared account" "Skip for now")]]}]]]])

(defn- steps
  [credentials]
  (let [pending
        (events/pending-credentials credentials)

        step
        @(rf/subscribe [::subs/step])

        saved
        @(rf/subscribe [::subs/saved])

        finishing?
        @(rf/subscribe [::data/busy? ::events/finish])]
    (if (empty? pending)
      [RawAlert {:severity "success" :el-id els/all-set
                 :action (r/as-element [:> Button {:data-testid (el/id els/continue)
                                                   :on-click #(rf/dispatch [::events/go-next])}
                                        "Continue"])}
       "Everything is set up."]
      [:> Paper {:sx (:setup-paper theme/sx)}
       (into [:> Stepper {:activeStep step :orientation "vertical"}]
             (for [credential pending]
               (with-meta (credential-step credential saved finishing?) {:key (str (:id credential))})))
       [RawBox {:sx (:setup-later theme/sx)}
        [:> Button {:data-testid (el/id els/later) :loading finishing?
                    :on-click #(rf/dispatch [::events/finish])}
         "Do this later"]]])))

(defn main-panel
  []
  [:> Container {:maxWidth "lg" :sx (:container theme/sx)}
   [layout/page-stack
    [layout/page-header {:title "Connect your accounts"
                         :el-id els/page
                         :description "Some tools your teams use act on your behalf, so they need your own credentials. You can change these any time under Accounts."}]
    [data/loaded {:key :my-credentials :query {:query-id :get-my-credentials} :error-el-id els/error}
     steps]]])
