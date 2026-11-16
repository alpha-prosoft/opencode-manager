(ns manager-svc.edge.api
  "The UI's edd endpoints and what the edge's own endpoints share with them.
   /api/query runs only the request's :query and /api/command only its
   :commands on edd, as the caller and with fresh request ids;
   failures reach the caller as {:exception {:status :message}} without
   internal details."
  (:require [clojure.string :as string]
            [lambda.util :as util]
            [manager-svc.client :as client]
            [manager-svc.edge.http :as edge-http]
            [manager-svc.effects :as effects]
            [manager-svc.errors :as errors]
            [manager-svc.http :as http]
            [manager-svc.workers :as workers]))

(defn- parsed-body
  [request]
  (let [text
        (edge-http/body-text request)]
    (when-not (string/blank? text)
      (try
        (util/to-edn text)
        (catch Exception _
          nil)))))

(defn json-object!
  "The request's JSON body as a map; 422 with message when it is anything
   else."
  [request message]
  (let [parsed
        (parsed-body request)]
    (if (map? parsed)
      parsed
      (errors/invalid! message))))

(defn- failure-of
  [body]
  (client/failure (try
                    (http/read-json body)
                    (catch Exception _
                      {:exception "edd answered with something other than JSON"}))))

(defn send!
  "Runs an edd request as the person token belongs to and answers with its
   response. A successful body passes through untouched; a failure becomes
   {:exception {:status :message}}."
  [settings path token request]
  (let [{:keys [status body]}
        (client/request-raw settings path token (util/to-json (client/envelope request)))

        failure
        (failure-of body)]
    {:status status
     :headers {"Content-Type" "application/json"}
     :body (if failure
             (util/to-json {:exception failure})
             body)}))

(defn command-done!
  "What follows every person's command: effects settle so the caller reads
   its own writes, and reactive jobs run."
  []
  (effects/settle!)
  (workers/kick!))

(defn command!
  "Runs commands on edd as the person token belongs to, for the edge's own
   endpoints, and answers with edd's response once effects settled. A
   failure throws {:status :message}, which the listener answers as that
   HTTP status with {:detail message}."
  [settings token commands]
  (let [{:keys [body]}
        (client/request-raw settings "/command" token (util/to-json (client/envelope {:commands (vec commands)})))

        failure
        (failure-of body)]
    (command-done!)
    (when failure
      (throw (ex-info (:message failure) failure)))
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body body}))

(defn- query
  [{:keys [settings]} {:keys [token] :as request}]
  (let [asked
        (:query (parsed-body request))]
    (if (map? asked)
      (send! settings "/query" token {:query asked})
      (edge-http/error 400 "The body must hold query"))))

(defn- commands
  [{:keys [settings]} {:keys [token] :as request}]
  (let [asked
        (:commands (parsed-body request))]
    (if (and (sequential? asked) (every? map? asked))
      (let [response
            (send! settings "/command" token {:commands (vec asked)})]
        (command-done!)
        response)
      (edge-http/error 400 "The body must hold commands"))))

(defn- healthy
  [_system _request]
  (edge-http/json {:status "ok"}))

(def routes
  [{:method :post :path "/api/query" :caller :person :handler query}
   {:method :post :path "/api/command" :caller :person :handler commands}
   {:method :get :path "/healthz" :handler healthy}])
