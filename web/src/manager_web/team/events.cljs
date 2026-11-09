(ns manager-web.team.events
  (:require [manager-web.components.credential-form :as credential-form]
            [manager-web.components.workflows :as workflows]
            [manager-web.data :as data]
            [manager-web.format :as format]
            [re-frame.core :as rf]))

(def tabs
  [[:workflows "Workflows"]
   [:runs "Runs"]
   [:sessions "Sessions"]
   [:setup "Agents & harnesses"]
   [:usage "Usage"]
   [:webhooks "Webhooks"]
   [:settings "Team settings"]])

(rf/reg-event-db
 ::init
 (fn [db [_ {:keys [team-id]}]]
   (-> (data/enter db :webhooks)
       (credential-form/close)
       (data/set-ui :team :team-id team-id))))

(defn interactive-workflows
  "The team's valid workflows people work in (their activity is interactive)."
  [team catalog]
  (vec (for [{:keys [workflow] :as item} (workflows/team-workflows team)
             :let [activity (workflows/find-activity catalog (get-in workflow [:spec :activity]))]
             :when (and (:valid workflow) (:interactive activity))]
         (assoc item :activity activity))))

(defn member-credential-state
  "[state label] of a team credential as a member sees it (:list-team-credentials)."
  [{:keys [set]}]
  (if set
    [:team "Team value set"]
    [:missing "No team value"]))

(defn member-credential-note
  [{:keys [provided-by]}]
  (let [scopes
        (format/scopes provided-by)]
    (str "Team admins set the team's value."
         (when (contains? scopes :tenant) " Without it, the tenant's value is used where one is set.")
         (when (contains? scopes :user) " You may add your own value instead."))))

(defn running-count
  [team-id sessions]
  (count (filter #(and (= team-id (:team-id %)) (format/session-live? (:status %))) sessions)))

(defn budget-percent
  [{:keys [daily-tokens used-today]}]
  (if (and daily-tokens (pos? daily-tokens))
    (min 100 (/ (* 100 (or used-today 0)) daily-tokens))
    0))

(defn limit-items
  "[id label value hint] of the team's budget, with what is used of it."
  [{:keys [budget]} running]
  (let [{:keys [daily-tokens max-running-sessions idle-minutes used-today]}
        budget]
    [[:daily-tokens "Tokens today"
      (format/tokens (or used-today 0))
      (if daily-tokens (str "of " (format/tokens daily-tokens) " a day") "No daily limit")]
     [:running-sessions "Running sessions"
      (str running)
      (if max-running-sessions (str "of at most " max-running-sessions) "No limit")]
     [:idle-minutes "Idle timeout"
      (if idle-minutes (str idle-minutes " min") "Default")
      (if idle-minutes "Idle sessions stop after this" "From each harness")]]))
