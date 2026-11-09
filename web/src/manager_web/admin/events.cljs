(ns manager-web.admin.events
  (:require [manager-web.components.confirm :as confirm]
            [manager-web.components.credential-form :as credential-form]
            [manager-web.data :as data]
            [manager-web.format :as format]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::init
 (fn [db _]
   (-> db
       (data/reset-ui :admin)
       (credential-form/close))))

(defn maintenance-spec
  [action]
  (let [[cmd-id done]
        (case action
          :sync [:sync-platform "Synced the onboarding repository and all team repositories."]
          :idle [:stop-idle-sessions "Asked the reaper to stop idle sessions."])]
    {:id [::maintenance action]
     :busy ::maintenance
     :command {:cmd-id cmd-id}
     :refetch [:admin-overview]
     :on-success [::report action true done]
     :on-failure [::report action false]}))

(rf/reg-event-fx
 ::maintenance
 (fn [{:keys [db]} [_ action]]
   {:db (data/set-ui db :admin :running action)
    :fx [[:dispatch [::data/run (maintenance-spec action)]]]}))

(rf/reg-event-db
 ::report
 (fn [db [_ action ok? text]]
   (data/set-ui db :admin :report {:action action :ok? ok? :text text})))

(defn test-result
  [{:keys [ok latency-ms message]}]
  (if ok
    {:state :ok :text (str "Answered in " latency-ms " ms")}
    {:state :failed :text (or message "Failed")}))

(defn- record-test
  [db connection-id result]
  (data/set-ui db :admin :tests (assoc (data/ui db :admin :tests {}) connection-id result)))

(defn test-spec
  [connection-id]
  {:id [::test connection-id]
   :query {:query-id :test-llm-connection :connection-id connection-id}
   :on-success [::tested connection-id]
   :on-failure [::test-failed connection-id]})

(rf/reg-event-fx
 ::test-connection
 (fn [{:keys [db]} [_ connection-id]]
   {:db (record-test db connection-id nil)
    :fx [[:dispatch [::data/run (test-spec connection-id)]]]}))

(rf/reg-event-db
 ::tested
 (fn [db [_ connection-id result]]
   (record-test db connection-id (test-result result))))

(rf/reg-event-db
 ::test-failed
 (fn [db [_ connection-id message]]
   (record-test db connection-id {:state :failed :text message})))

(defn clear-request
  [{:keys [id name]}]
  {:title (str "Clear the platform value of " name "?")
   :body "Anything using the shared value stops working until it is set again. Values from secret files stay in place."
   :confirm "Clear"
   :run {:id [::clear id]
         :error ::credentials-action
         :command {:cmd-id :delete-platform-credential :attrs {:credential-id id}}
         :refetch [:admin-credentials]}})

(rf/reg-event-fx
 ::clear-credential
 (fn [_ [_ credential]]
   {:fx [[:dispatch [::confirm/ask (clear-request credential)]]]}))

(defn credential-help
  [{:keys [provided-by]}]
  (if (= :platform-or-user (format/kw provided-by))
    "Used by everyone who hasn't added their own, and by automated runs."
    "Used by everyone and by automated runs."))
