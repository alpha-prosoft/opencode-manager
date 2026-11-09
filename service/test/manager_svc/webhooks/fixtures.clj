(ns manager-svc.webhooks.fixtures
  "A fake platform (tenant acme with teams alpha and beta, globex with its
   own alpha) and the webhooks domain on the mock DAL."
  (:require [edd.security :as security]
            [manager-svc.platform.api :as platform]
            [manager-svc.roles :as roles]
            [manager-svc.test-support :as support]
            [manager-svc.webhooks.core :as core]
            [manager-svc.webhooks.edge :as edge]))

(def settings
  (assoc support/settings :credentials-key "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="))

(def people
  "root administers acme; dave administers alpha, alice and bob are its
   members; erin administers beta; gina administers globex and its alpha."
  {"root" {:tenant-admin true}
   "dave" {:teams {"alpha" :admin}}
   "alice" {:teams {"alpha" :member}}
   "bob" {:teams {"alpha" :member}}
   "erin" {:teams {"beta" :admin}}
   "gina" {:tenant "globex" :tenant-admin true :teams {"alpha" :admin}}})

(def teams
  {"acme" [{:id "alpha" :name "Alpha" :projects [{:id "app" :name "App"} {:id "shop" :name "Shop"}]}
           {:id "beta" :name "Beta" :projects []}]
   "globex" [{:id "alpha" :name "Globex alpha" :projects []}]})

(def workflows
  "analyse and review are the team's own; triage belongs to project shop."
  {"analyse" {:name "Analyse" :activity "analysis" :agent "analyst" :harness "default"}
   "review" {:name "Review" :activity "review" :agent "reviewer" :harness "default"}
   "triage" {:name "Triage" :activity "analysis" :agent "analyst" :harness "default" :project "shop"}})

(defn- team
  [_system tenant team-id]
  (some #(when (= team-id (:id %)) %) (get teams tenant)))

(defn- definition
  [_system tenant team-id kind workflow-id]
  (when (and (= :workflows kind) (team nil tenant team-id))
    (when-let [spec (get workflows workflow-id)]
      {:kind :workflows :id workflow-id :spec spec :valid true})))

(defn- team-workflows
  [_system tenant team-id]
  (if (team nil tenant team-id)
    (vec (for [[id spec] workflows] {:kind :workflows :id id :spec spec :valid true}))
    []))

(defn- teams-of
  [_system caller]
  (let [tenant
        (:tenant caller)]
    (set (for [{:keys [id]} (get teams tenant)
               :when (roles/member? caller tenant id)]
           id))))

(defn with-platform*
  [f]
  (with-redefs [platform/team team
                platform/teams (fn [_system tenant] (get teams tenant))
                platform/teams-of teams-of
                platform/definition definition
                platform/workflows team-workflows]
    (f)))

(defmacro with-platform
  [& body]
  `(with-platform* (fn [] ~@body)))

(defn ctx
  []
  (security/register (core/register (support/ctx {:settings settings}))))

(defn as
  [username]
  (support/as-user (ctx) username (get people username {})))

(defn platform-ctx
  []
  (support/as-platform (ctx)))

(defn identity-of
  [username]
  {:sub username :tenant (get-in people [username :tenant] support/tenant)})

(def header-auth
  {:type "header" :header-name "X-Hook-Token" :secret "s3cret-token-1"})

(defn config
  "Save attrs of an alpha webhook targeting analyse, brief from the body."
  ([]
   (config {}))
  ([overrides]
   (merge {:name "Ticket intake"
           :scope {:kind "team" :team-id "alpha"}
           :target {:team-id "alpha" :workflow-id "analyse"}
           :auth header-auth
           :mapping [{:input "brief" :source "body" :value "$.issue.summary"}]}
          overrides)))

(defn save!
  "Saves attrs as the edge would for username: the secret sealed; returns
   {:success true} or {:error :status}."
  [username webhook-id attrs]
  (try
    (let [command
          (edge/save-command settings (identity-of username) webhook-id attrs)

          response
          (support/apply! (as username) command)]
      (if (:error response)
        {:error (:error response)}
        {:success true}))
    (catch clojure.lang.ExceptionInfo e
      {:error (ex-message e) :status (:status (ex-data e))})))

(defn attempt
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      {:error (ex-message e) :status (:status (ex-data e))})))
