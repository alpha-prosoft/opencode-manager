(ns manager-web.data
  "Query results, polling, commands and page state shared by pages.

   Query results live under [::loads key] as {:data :error :loading?}. A view
   renders `poll` (or `loaded`, which also renders the result) to load a
   query when it mounts and refresh it on an interval. Only the reply to the
   latest request of a key is kept, so a slow reply never overwrites a newer
   one. `::run` sends a command, an action-like query or an edge request with
   a busy flag, then re-fetches loads, navigates or dispatches follow-up
   events. Page state (tabs, filters) lives under [::ui page key]."
  (:require [manager-web.api :as api]
            [manager-web.components.layout :as layout]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(def ^:private request-keys
  [:query :command :commands :method :path :body :edd?])

(defn request
  "The part of a spec that says what to send."
  [spec]
  (select-keys spec request-keys))

(defn begin-fetch
  "db with a new request for key recorded, and its token; nil when the same
   request is already on its way and force? is not set."
  [db key request then force?]
  (let [{:keys [loading?] :as load}
        (get-in db [::loads key])]
    (when (or force? (not loading?) (not= request (:request load)))
      (let [token
            (inc (::requests db 0))]
        [(-> db
             (assoc ::requests token)
             (update-in [::loads key] assoc :request request :then then :token token :loading? true))
         token]))))

(defn- current?
  [db key token]
  (= token (get-in db [::loads key :token])))

(defn accept-result
  "db with the result of request token stored, or nil when a newer request
   (or a logout) has superseded it."
  [db key token result]
  (when (current? db key token)
    (update-in db [::loads key] assoc :data result :error nil :loading? false)))

(defn accept-failure
  "Like accept-result; the last data stays."
  [db key token message]
  (when (current? db key token)
    (update-in db [::loads key] assoc :error message :loading? false)))

(rf/reg-event-fx
 ::fetch
 (fn [{:keys [db]} [_ key request {:keys [then force?]}]]
   (when-let [[next-db token]
              (begin-fetch db key request then force?)]
     {:db next-db
      ::api/call (assoc request
                        :on-success [::loaded key token]
                        :on-failure [::failed key token])})))

(rf/reg-event-fx
 ::loaded
 (fn [{:keys [db]} [_ key token result]]
   (when-let [next-db
              (accept-result db key token result)]
     (let [then
           (get-in db [::loads key :then])]
       {:db next-db
        :fx [(when then [:dispatch (conj then result)])]}))))

(rf/reg-event-fx
 ::failed
 (fn [{:keys [db]} [_ key token message]]
   (when-let [next-db
              (accept-failure db key token message)]
     {:db next-db})))

(rf/reg-event-fx
 ::refetch
 (fn [{:keys [db]} [_ keys]]
   {:fx (for [key keys
              :let [{:keys [request then]}
                    (get-in db [::loads key])]
              :when request]
          [:dispatch [::fetch key request {:then then :force? true}]])}))

(defn load-of
  [db key]
  (get-in db [::loads key]))

(defn loaded-data
  [db key]
  (get-in db [::loads key :data]))

(rf/reg-sub
 ::load
 (fn [db [_ key]]
   (load-of db key)))

(rf/reg-sub
 ::data
 (fn [db [_ key]]
   (loaded-data db key)))

(defn- poller
  [{:keys [key request interval then]}]
  (r/with-let [fetch
               #(rf/dispatch [::fetch key request {:then then}])

               _
               (fetch)

               timer
               (when interval
                 (js/setInterval #(when-not (.-hidden js/document) (fetch)) interval))]
    nil
    (finally
      (when timer
        (js/clearInterval timer)))))

(defn poll
  "Loads :query (or an edge :request) into :key on mount and every :interval
   ms while the page is visible, until unmounted or the query or interval
   changes. :then is dispatched with each result."
  [{:keys [key query request interval] :as props}]
  (let [request
        (or request {:query query})]
    ^{:key (pr-str [key request interval])}
    [poller (assoc props :request request)]))

(defn loaded
  "Polls like `poll` and renders (render data) once data has arrived. A failed
   refresh keeps the data under an error alert. With :poll-while, polling
   stops once (poll-while data) is false."
  [{:keys [key interval poll-while error-el-id] :as props} render]
  (let [{:keys [data] :as load}
        @(rf/subscribe [::load key])

        interval
        (when (or (nil? data) (nil? poll-while) (poll-while data))
          interval)]
    [:<>
     [poll (assoc props :interval interval)]
     [layout/loadable load error-el-id render]]))

(defn run-effects
  "What happens after a successful ::run."
  [{:keys [refetch navigate on-success finally]} result]
  (cond-> []
    (seq refetch) (conj [:dispatch [::refetch refetch]])
    navigate (conj [:dispatch (into [:edd.events/navigate] navigate)])
    on-success (conj [:dispatch (conj on-success result)])
    finally (conj [:dispatch finally])))

(defn- busy-key
  [spec]
  (or (:busy spec) (:id spec)))

(defn- error-key
  [spec]
  (or (:error spec) (:id spec)))

(defn clear-error
  [db id]
  (update db ::errors dissoc id))

(defn start-run
  [db spec]
  (-> db
      (assoc-in [::busy (busy-key spec)] true)
      (clear-error (error-key spec))))

(defn finish-run
  ([db spec]
   (update db ::busy dissoc (busy-key spec)))
  ([db spec message]
   (-> (finish-run db spec)
       (assoc-in [::errors (error-key spec)] message))))

(rf/reg-event-fx
 ::run
 (fn [{:keys [db]} [_ spec]]
   {:db (start-run db spec)
    ::api/call (assoc (request spec)
                      :on-success [::ran spec]
                      :on-failure [::run-failed spec])}))

(rf/reg-event-fx
 ::ran
 (fn [{:keys [db]} [_ spec result]]
   {:db (finish-run db spec)
    :fx (run-effects spec result)}))

(rf/reg-event-fx
 ::run-failed
 (fn [{:keys [db]} [_ {:keys [on-failure finally] :as spec} message]]
   {:db (finish-run db spec message)
    :fx [(when on-failure [:dispatch (conj on-failure message)])
         (when finally [:dispatch finally])]}))

(rf/reg-event-db
 ::clear-error
 (fn [db [_ id]]
   (clear-error db id)))

(rf/reg-sub
 ::busy?
 (fn [db [_ id]]
   (boolean (get-in db [::busy id]))))

(rf/reg-sub
 ::error
 (fn [db [_ id]]
   (get-in db [::errors id])))

(defn set-ui
  [db page key value]
  (assoc-in db [::ui page key] value))

(defn reset-ui
  [db page]
  (update db ::ui dissoc page))

(defn ui
  [db page key default]
  (get-in db [::ui page key] default))

(rf/reg-event-db
 ::set-ui
 (fn [db [_ page key value]]
   (set-ui db page key value)))

(rf/reg-sub
 ::ui
 (fn [db [_ page key default]]
   (ui db page key default)))

(rf/reg-event-db
 ::enter-page
 (fn [db _]
   (reset-ui db (:edd.db/active-panel db))))

(defn route-id
  "A path parameter that names an aggregate, as a UUID when it is one."
  [value]
  (or (parse-uuid (str value)) value))
