(ns manager-web.routes
  "Where every page lives.

   Tenant pages live under the tenant: /acme is its Home, /acme/runs,
   /acme/runs/<id>, /acme/sessions/<id>, /acme/projects, /acme/admin...
   Like an owner and its repositories, a team is /acme/<team> and its project
   /acme/<team>/<project>. The first segment after the tenant names a page
   (tenant-sections), so those words are never team ids. Operator pages live
   under /platform. The team context of lists stays a query (?team=demo).

   URLs of earlier versions (/runs/<id>, /teams/<id>, /tenants...) redirect
   to the person's tenant. A tenant URL of another tenant than the person's
   shows a page saying so, without loading anything."
  (:require [clojure.string :as string]
            [re-frame.core :as rf]))

(def tenant-sections
  "Pages under a tenant, by the path segment naming them."
  {"runs" :runs
   "sessions" :sessions
   "teams" :teams
   "projects" :projects
   "harnesses" :harnesses
   "activities" :activities
   "accounts" :accounts
   "admin" :admin
   "webhooks" :webhooks})

(def reserved-team-ids
  "Team ids that can't have a page of their own: they name a tenant page."
  (set (keys tenant-sections)))

(def legacy-pages
  "Pages of earlier versions, at their old paths, and where they are now."
  {:legacy/runs [["/runs"] :runs]
   :legacy/run [["/runs/:run-id"] :run]
   :legacy/sessions [["/sessions"] :sessions]
   :legacy/session [["/sessions/:session-id"] :session]
   :legacy/teams [["/teams"] :teams]
   :legacy/team [["/teams/:team-id"] :team]
   :legacy/harnesses [["/harnesses"] :harnesses]
   :legacy/activities [["/activities"] :activities]
   :legacy/accounts [["/accounts"] :accounts]
   :legacy/admin [["/admin"] :admin]
   :legacy/tenants [["/tenants"] :tenants]})

(def redirect-pages
  "Pages that only send the person on: the root, /platform, the old URLs and
   /<tenant>/teams/<team>."
  (into #{:root :platform :team-alias} (keys legacy-pages)))

(def tenant-pages
  #{:home :runs :run :sessions :session :teams :team :team-alias :project :projects :harnesses :activities
    :accounts :admin :webhooks})

(def routes
  "Every route is marked conflicting, so reitit matches them in this order:
   fixed paths before the tenant's, a tenant's pages before its teams."
  [(into ["" {:conflicting true}]
         (concat [["/" :root]
                  ["/login" :login]
                  ["/setup" :setup]
                  ["/platform" :platform]
                  ["/platform/tenants" :tenants]]
                 (for [[page [[path] _]] (sort-by key legacy-pages)]
                   [path page])
                 [["/:tenant" :home]]
                 (for [[segment page] (sort-by key tenant-sections)]
                   [(str "/:tenant/" segment) page])
                 [["/:tenant/runs/:run-id" :run]
                  ["/:tenant/sessions/:session-id" :session]
                  ["/:tenant/teams/:team-id" :team-alias]
                  ["/:tenant/:team-id" :team]
                  ["/:tenant/:team-id/:project-id" :project]]))])

(defn- segment
  [value]
  (js/encodeURIComponent (str value)))

(defn query-string
  [query]
  (let [pairs
        (for [[k v] (sort-by key query)
              :when (some? v)]
          (str (segment (name k)) "=" (segment v)))]
    (if (seq pairs) (str "?" (string/join "&" pairs)) "")))

(defn page-path
  "The path of page under tenant; tenant pages without a tenant (operators)
   lead to /."
  [tenant page params]
  (let [base
        (str "/" (segment tenant))]
    (cond
      (= :root page) "/"
      (= :login page) "/login"
      (= :setup page) "/setup"
      (= :tenants page) "/platform/tenants"
      (nil? tenant) "/"
      (= :home page) base
      (= :run page) (str base "/runs/" (segment (:run-id params)))
      (= :session page) (str base "/sessions/" (segment (:session-id params)))
      (= :team page) (str base "/" (segment (:team-id params)))
      (= :project page) (str base "/" (segment (:team-id params)) "/" (segment (:project-id params)))
      :else (str base "/" (name page)))))

(defn path
  "The URL of page under tenant with params and query."
  ([tenant page params]
   (path tenant page params nil))
  ([tenant page params query]
   (str (page-path tenant page params) (query-string query))))

(defn tenant-page?
  [panel]
  (contains? tenant-pages panel))

(defn foreign-tenant?
  "True when the URL names a tenant other than the person's own."
  [me panel path-params]
  (and (tenant-page? panel)
       (not= (:tenant me) (:tenant path-params))))

(defn redirect
  "Where a redirect page sends the person, as a URL: their tenant's page
   with the same params and query; the platform's tenants for operators
   outside any tenant."
  [me panel path-params query]
  (let [tenant
        (:tenant me)

        [_ target]
        (get legacy-pages panel)]
    (cond
      (or (= :platform panel) (= :tenants target)) (path nil :tenants nil)
      (nil? tenant) (path nil :tenants nil)
      (= :root panel) (path tenant :home nil query)
      (= :team-alias panel) (path tenant :team path-params query)
      target (path tenant target path-params query)
      :else nil)))

(defn destination
  "What a URL leads to for the person: [:foreign] for another tenant's
   page, [:redirect url] for a page that only sends them on, else [:page]."
  [me panel path-params query]
  (let [url
        (when (contains? redirect-pages panel)
          (redirect me panel path-params query))]
    (cond
      (foreign-tenant? me panel path-params) [:foreign]
      url [:redirect url]
      :else [:page])))

(defn tenant-of-db
  [db]
  (get-in db [:manager-web.session/me :tenant]))

(rf/reg-sub
 ::tenant
 (fn [db _]
   (tenant-of-db db)))

(rf/reg-sub
 ::path-params
 (fn [db _]
   (:edd.db/path-params db)))

(rf/reg-event-fx
 ::navigate
 (fn [{:keys [db]} [_ page params query {:keys [replace?]}]]
   {:fx [[:dispatch [:edd.events/navigate {:url (path (tenant-of-db db) page params query)
                                           :replace? replace?}]]]}))

(rf/reg-event-fx
 ::entered
 (fn [_ _]
   {}))
