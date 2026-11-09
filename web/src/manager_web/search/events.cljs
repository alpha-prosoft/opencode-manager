(ns manager-web.search.events
  "The global search (Ctrl+K, Cmd+K on a Mac): the :search query runs
   debounced-ms after the last keystroke; its grouped results form one list
   the person moves through with the arrow keys and opens with Enter. Only
   the reply to the latest text is kept."
  (:require [clojure.string :as string]
            [manager-web.api :as api]
            [manager-web.routes :as routes]
            [re-frame.core :as rf]))

(def debounce-ms
  200)

(def groups
  "[result key, row kind, heading] in the order the dialog shows them."
  [[:projects :project "Projects"]
   [:teams :team "Teams"]
   [:sessions :session "Sessions"]])

(defn rows
  "The results as one list of {:kind :item}, group by group."
  [results]
  (vec (for [[key kind] groups
             item (get results key)]
         {:kind kind :item item})))

(defn moved
  "The selected index after moving by delta through count rows, wrapping."
  [index count delta]
  (if (pos? count)
    (mod (+ (or index 0) delta) count)
    0))

(defn target
  "Where a row leads, as routes/navigate arguments; nil for a team the
   person isn't in, whose page is closed to them."
  [{:keys [kind item]}]
  (case kind
    :project [:project {:team-id (:team-id item) :project-id (:id item)}]
    :team (when (:member item) [:team {:team-id (:id item)}])
    :session [:session {:session-id (str (:id item))}]))

(defn shortcut?
  "Ctrl+K or Cmd+K."
  [{:keys [key ctrl? meta?]}]
  (and (= "k" (some-> key string/lower-case))
       (boolean (or ctrl? meta?))))

(defn typed
  "The state after the person typed text: a new token, and the request to
   send once they pause (nil for blank text, which clears the results)."
  [state text]
  (let [token
        (inc (:token state 0))

        blank?
        (string/blank? text)]
    [(cond-> (assoc state :text text :token token :index 0 :error nil :enter? false)
       blank? (assoc :results nil :loading? false)
       (not blank?) (assoc :loading? true))
     (when-not blank? token)]))

(defn answered
  "The state with the reply to token, or nil when a newer text superseded it."
  [state token results]
  (when (= token (:token state))
    (assoc state :results results :loading? false :index 0)))

(defn entered
  "What Enter does, as [state row]: the selected row is opened, but while a
   reply is on its way the choice waits for it, so a result of an earlier
   text is never opened."
  [state]
  (if (:loading? state)
    [(assoc state :enter? true) nil]
    [state (get (rows (:results state)) (or (:index state) 0))]))

(defn awaited
  "The row to open once results have arrived, when Enter was pressed
   while they were on their way."
  [state results]
  (when (:enter? state)
    (first (rows results))))

(rf/reg-sub
 ::state
 (fn [db _]
   (::state db)))

(rf/reg-event-db
 ::open
 (fn [db _]
   (assoc db ::state {:open? true :text "" :token (inc (get-in db [::state :token] 0)) :index 0})))

(rf/reg-event-db
 ::close
 (fn [db _]
   (update db ::state assoc :open? false)))

(rf/reg-event-fx
 ::set-text
 (fn [{:keys [db]} [_ text]]
   (let [[state token]
         (typed (::state db) text)]
     {:db (assoc db ::state state)
      :fx [(when token
             [:dispatch-later {:ms debounce-ms :dispatch [::run token]}])]})))

(rf/reg-event-fx
 ::run
 (fn [{:keys [db]} [_ token]]
   (let [{:keys [text] :as state}
         (::state db)]
     (when (= token (:token state))
       {::api/call {:query {:query-id :search :text (string/trim text)}
                    :on-success [::found token]
                    :on-failure [::failed token]}}))))

(rf/reg-event-fx
 ::found
 (fn [{:keys [db]} [_ token results]]
   (when-let [state
              (answered (::state db) token results)]
     {:db (assoc db ::state (assoc state :enter? false))
      :fx [(when-let [row
                      (awaited (::state db) results)]
             [:dispatch [::choose row]])]})))

(rf/reg-event-db
 ::failed
 (fn [db [_ token message]]
   (if (= token (get-in db [::state :token]))
     (update db ::state assoc :error message :loading? false)
     db)))

(rf/reg-event-db
 ::move
 (fn [db [_ delta]]
   (let [state
         (::state db)]
     (assoc-in db [::state :index] (moved (:index state) (count (rows (:results state))) delta)))))

(rf/reg-event-db
 ::select
 (fn [db [_ index]]
   (assoc-in db [::state :index] index)))

(rf/reg-event-fx
 ::choose
 (fn [_ [_ row]]
   (when-let [destination
              (target row)]
     {:fx [[:dispatch [::close]]
           [:dispatch (into [::routes/navigate] destination)]]})))

(rf/reg-event-fx
 ::choose-selected
 (fn [{:keys [db]} _]
   (let [[state row]
         (entered (::state db))]
     {:db (assoc db ::state state)
      :fx [(when row [:dispatch [::choose row]])]})))
