(ns manager-web.admin.events
  (:require [clojure.string :as string]
            [manager-web.components.credential-form :as credential-form]
            [manager-web.format :as format]
            [manager-web.data :as data]
            [manager-web.components.required-credentials :as required-credentials]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::init
 (fn [db _]
   (credential-form/close (data/enter db))))

(defn maintenance-spec
  [action]
  (let [[cmd-id done]
        (case action
          :sync [:sync-tenant "Asked to sync the tenant's onboarding repository and its team repositories."]
          :idle [:stop-idle-sessions "Asked the reaper to stop idle sessions."])]
    {:id [::maintenance action]
     :busy ::maintenance
     :command {:cmd-id cmd-id}
     :refetch [:admin-overview]
     :on-success [::report action true done]
     :on-failure [::report action false]}))

(defn asked
  "db with action asked for. A sync remembers when the tenant last synced,
   to tell when the one it asks for is done."
  [db action]
  (cond-> (data/set-ui db :admin :running action)
    (= :sync action) (data/set-ui :admin :sync {:before (get-in (data/loaded-data db :admin-overview) [:status :synced-at])})))

(rf/reg-event-fx
 ::maintenance
 (fn [{:keys [db]} [_ action]]
   {:db (asked db action)
    :fx [[:dispatch [::data/run (maintenance-spec action)]]]}))

(defn- team-failed?
  [{:keys [status error-message]}]
  (or (not (string/blank? error-message))
      (= :error (format/kw status))))

(defn sync-outcome
  "How the sync asked for on this page went, from the tenant's sync status
   and its teams: nil when none was asked, {:state :pending} until the
   tenant's sync time moves on from the one remembered, then {:state :synced
   or :failed, :teams, :failed (teams whose repository didn't sync),
   :message (why the tenant's own sync failed)}."
  [asked status teams]
  (when asked
    (if (= (:before asked) (:synced-at status))
      {:state :pending}
      (let [failed
            (count (filter team-failed? teams))

            message
            (:error-message status)]
        {:state (if (or message (pos? failed)) :failed :synced)
         :teams (count teams)
         :failed failed
         :message message}))))

(defn sync-outcome-text
  [{:keys [state teams failed message]}]
  (cond
    (= :pending state) "The sync is running. This says how it went once it has finished."
    message (str "The sync failed: " message)
    (pos? failed) (str "Synced, but " failed " of " teams " team repositories failed.")
    :else (str "Synced: all " teams " team repositories are up to date.")))

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

(defn team-totals
  "Usage rows ({:team-id :calls :prompt-tokens :completion-tokens :errors :cost})
   summed per team, most tokens first. Cost stays nil for a team without any
   priced model."
  [rows]
  (->> (group-by :team-id rows)
       (map (fn [[team-id team-rows]]
              (let [costs
                    (keep :cost team-rows)]
                {:team-id team-id
                 :calls (reduce + 0 (keep :calls team-rows))
                 :tokens (reduce + 0 (mapcat (juxt #(or (:prompt-tokens %) 0) #(or (:completion-tokens %) 0)) team-rows))
                 :errors (reduce + 0 (keep :errors team-rows))
                 :cost (when (seq costs) (reduce + costs))})))
       (sort-by (juxt (comp - :tokens) (comp str :team-id)))
       vec))

(defn required-text
  "Who requires each person's own value of an admin credential row, or nil:
   tenant.json, and the teams whose team.json does."
  [tenant team-name {:keys [required required-by-teams]}]
  (let [requirers
        (concat (when required [(str tenant " (tenant.json, everyone in the teams it applies to)")])
                (when (seq required-by-teams)
                  [(str (required-credentials/join-names (map team-name required-by-teams))
                        " (team.json, " (if (< 1 (count required-by-teams)) "their" "its") " members)")]))]
    (when (seq requirers)
      (str "Required by " (required-credentials/join-names requirers)))))
