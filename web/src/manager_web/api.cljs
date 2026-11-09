(ns manager-web.api
  "Calls to the manager service. The UI and the service share an origin, so
   requests carry the login cookie.

     [::api/call {:query {:query-id :get-team :team-id \"demo\"}
                  :on-success [::team-loaded]
                  :on-failure [::team-failed]}]

   A request is an edd :query, :command or :commands (sent to /api/query or
   /api/command), or a plain {:method :path :body} call to one of the edge's
   own endpoints; :edd? true reads the reply of such a call as an edd
   response. :on-success gets the result appended, :on-failure the error
   message. Without :on-failure the error is shown in the global snackbar.
   A 401 drops everything cached for the person and sends them to the login
   page."
  (:require [clojure.string :as string]
            [clojure.walk :as walk]
            [edd.json :as json]
            [re-frame.core :as rf]))

(defn- envelope
  [request]
  (merge {:request-id (random-uuid)
          :interaction-id (random-uuid)}
         request))

(defn- edd-json
  [body]
  (.stringify js/JSON (clj->js (json/encode-custom-fields body)
                               :keyword-fn (fn [k] (if (namespace k)
                                                     (str (namespace k) "/" (name k))
                                                     (name k))))))

(defn- plain-json
  [body]
  (.stringify js/JSON (clj->js body)))

(defn request-of
  "The HTTP request for a call: method, path, JSON payload and whether the
   reply is an edd response."
  [{:keys [query command commands method path body edd?]}]
  (cond
    query {:method "POST" :path "/api/query" :payload (edd-json (envelope {:query query})) :edd? true}
    command {:method "POST" :path "/api/command" :payload (edd-json (envelope {:commands [command]})) :edd? true}
    commands {:method "POST" :path "/api/command" :payload (edd-json (envelope {:commands commands})) :edd? true}
    :else {:method (or method "GET") :path path :payload (some-> body plain-json) :edd? (boolean edd?)}))

(defn error-message
  "A readable message from an error response."
  [{:keys [error exception detail message]}]
  (let [value
        (or error exception detail message)]
    (cond
      (string? value) value
      (string? (:message value)) (:message value)
      (map? value) (or (some-> value :error error-message) (pr-str value))
      (some? value) (str value)
      :else "Request failed")))

(defn edd-result
  "[:ok result] or [:error message] for a parsed edd response."
  [parsed]
  (if (and (map? parsed) (contains? parsed :result) (nil? (:error parsed)) (nil? (:exception parsed)))
    [:ok (:result parsed)]
    [:error (error-message parsed)]))

(defn http-error
  [status body]
  (if (map? body)
    (error-message body)
    (str "Request failed (HTTP " status ")")))

(defn- success-event
  [on-success result]
  (when on-success
    (conj on-success result)))

(defn- failure-event
  [on-failure message]
  (if on-failure
    (conj on-failure message)
    [::show-error message]))

(defn- fail
  [on-failure message]
  (rf/dispatch (failure-event on-failure message)))

(defn- read-body
  "The JSON body of a response as Clojure data, or nil when it has none."
  [^js response]
  (let [content-type
        (or (.. response -headers (get "content-type")) "")]
    (if (string/includes? content-type "json")
      (-> (.json response)
          (.then #(js->clj % :keywordize-keys true))
          (.catch (constantly nil)))
      (js/Promise.resolve nil))))

(defn- read-field
  [value]
  (cond
    (keyword? value) (or (json/decode-json-special value) value)
    (not (string? value)) value
    (re-matches #":[^\s:]\S*" value) (keyword (subs value 1))
    (string/starts-with? value "#") (or (parse-uuid (subs value 1)) value)
    :else value))

(defn read-fields
  "An edd reply with its keywords (\":running\") and UUIDs (\"#<uuid>\") read.
   Any other text stays text, also when it starts like one: a Markdown
   document opens with \"# Heading\"."
  [body]
  (walk/postwalk read-field body))

(defn response-event
  "The event a response leads to, nil when there is nothing to do.
   :on-not-found, when given, handles a 404 (an endpoint the server doesn't offer)."
  [status body {:keys [on-success on-failure on-not-found]} edd?]
  (cond
    (= 401 status) [::unauthenticated]
    (and (= 404 status) on-not-found) on-not-found
    (not (<= 200 status 299)) (failure-event on-failure (http-error status body))
    (not edd?) (success-event on-success body)
    (nil? body) (failure-event on-failure (http-error status body))
    :else (let [[outcome value]
                (edd-result (read-fields body))]
            (if (= :ok outcome)
              (success-event on-success value)
              (failure-event on-failure value)))))

(defn- respond
  [^js response body props edd?]
  (when-let [event
             (response-event (.-status response) body props edd?)]
    (rf/dispatch event)))

(def ^:private csrf-header
  "Marks state-changing requests as the UI's own; the edge refuses them from
   another origin without it."
  {"X-Requested-With" "manager-web"})

(defn headers
  [method payload]
  (cond-> {}
    (not= "GET" method) (merge csrf-header)
    payload (assoc "Content-Type" "application/json")))

(defn call
  [{:keys [on-failure] :as props}]
  (let [{:keys [method path payload edd?]}
        (request-of props)]
    (-> (js/fetch path (clj->js (cond-> {:method method
                                         :credentials "same-origin"
                                         :headers (headers method payload)}
                                  payload (assoc :body payload))))
        (.then (fn [response]
                 (.then (read-body response)
                        #(respond response % props edd?))))
        (.catch (fn [e]
                  (fail on-failure (str "Network error: " (.-message e))))))))

(rf/reg-fx ::call call)

(rf/reg-event-db
 ::show-error
 (fn [db [_ message]]
   (assoc db ::error message)))

(rf/reg-event-db
 ::clear-error
 (fn [db _]
   (dissoc db ::error)))

(rf/reg-sub
 ::error
 (fn [db _]
   (::error db)))

(defn- app-key?
  [k]
  (and (keyword? k)
       (some-> (namespace k) (string/starts-with? "manager-web."))))

(defn signed-out
  "db without anything this app keeps for a person: query results, busy
   flags, errors, dialogs and page state. edd's own keys stay."
  [db]
  (-> (into {} (remove (comp app-key? key)) db)
      (assoc :manager-web.session/status :anonymous)))

(defn- login-without-completion
  "The login page's URL without complete=1, nil when it doesn't carry it."
  [search]
  (let [params
        (js/URLSearchParams. (or search ""))]
    (when (.has params "complete")
      (.delete params "complete")
      (let [query
            (.toString params)]
        (str "/login" (when-not (string/blank? query) (str "?" query)))))))

(defn unauthenticated-effects
  "After a 401. Elsewhere everything kept for the person goes and they are
   sent to log in. On the login page nothing they started is still running,
   and a provider login that didn't complete is not tried again."
  [db pathname search]
  (if (= "/login" pathname)
    {:db (-> db
             (assoc :manager-web.session/status :anonymous)
             (dissoc :manager-web.data/busy))
     :fx [(when-let [url
                     (login-without-completion search)]
            [:dispatch [:edd.events/navigate {:url url :replace? true}]])]}
    {:db (signed-out db)
     :fx [[:dispatch [:edd.events/navigate {:url (str "/login?next=" (js/encodeURIComponent (str pathname search)))
                                            :replace? true}]]]}))

(rf/reg-event-fx
 ::unauthenticated
 (fn [{:keys [db]} _]
   (let [location
         (.-location js/window)]
     (unauthenticated-effects db (.-pathname location) (.-search location)))))
